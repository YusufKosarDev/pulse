import json
import logging
from collections import defaultdict
from datetime import datetime, timedelta, timezone

from . import config, db

log = logging.getLogger("ml.forecaster")


class HoltWinters:
    """Hand-rolled additive Holt-Winters: level + trend + seasonal components.

    The first full season only accumulates observations; the components are
    then initialised from it (level = season mean, seasonals = deviations
    from that mean, trend = 0) and updated per observation afterwards.
    Forecasts are refused until `min_seasons` full seasons have been seen,
    so an under-trained model never produces predictions.
    """

    def __init__(self,
                 season_length: int,
                 alpha: float = config.FORECAST_ALPHA,
                 beta: float = config.FORECAST_BETA,
                 gamma: float = config.FORECAST_GAMMA,
                 min_seasons: int = config.FORECAST_MIN_SEASONS):
        self.m = season_length
        self._alpha = alpha
        self._beta = beta
        self._gamma = gamma
        self._min_samples = min_seasons * season_length
        self.n = 0
        self.level = 0.0
        self.trend = 0.0
        self.seasonals: list[float] = []
        self._first_season: list[float] = []

    @property
    def ready(self) -> bool:
        return self.n >= self._min_samples

    def observe(self, value: float) -> None:
        if self.n < self.m:
            self._first_season.append(value)
            self.n += 1
            if self.n == self.m:
                mean = sum(self._first_season) / self.m
                self.level = mean
                self.trend = 0.0
                self.seasonals = [v - mean for v in self._first_season]
                self._first_season = []
            return

        i = self.n % self.m
        seasonal = self.seasonals[i]
        previous_level = self.level
        self.level = (self._alpha * (value - seasonal)
                      + (1 - self._alpha) * (self.level + self.trend))
        self.trend = (self._beta * (self.level - previous_level)
                      + (1 - self._beta) * self.trend)
        self.seasonals[i] = (self._gamma * (value - self.level)
                             + (1 - self._gamma) * seasonal)
        self.n += 1

    def fitted(self) -> float:
        """Model's estimate for 'now' — used as the anchor point of the curve."""
        return self.level + self.seasonals[self.n % self.m]

    def forecast(self, steps: int) -> list[float]:
        return [self.level + h * self.trend + self.seasonals[(self.n + h - 1) % self.m]
                for h in range(1, steps + 1)]


def first_crossing(series: list[float], threshold: float | None) -> int | None:
    """1-based index of the first forecast step at or above the threshold."""
    if threshold is None:
        return None
    for i, value in enumerate(series):
        if value >= threshold:
            return i + 1
    return None


class ForecastEngine:
    """One Holt-Winters model per (metric, sensor); periodically persists the
    forecast curve and raises/clears predicted alerts on threshold crossings."""

    def __init__(self, publish=None, tracker=None):
        season_length = int(config.SEASON_SECONDS / config.FORECAST_SAMPLE_INTERVAL_S)
        self._models: dict[tuple[str, str], HoltWinters] = defaultdict(
            lambda: HoltWinters(season_length))
        self._confirmations: dict[tuple[str, str], int] = defaultdict(int)
        self._alert_active: set[tuple[str, str]] = set()
        self._thresholds: dict[str, float] = json.loads(config.THRESHOLDS)
        self._publish = publish or (lambda payload: None)
        self._tracker = tracker

    def observe(self, metric: str, sensor_id: str, value: float) -> None:
        self._models[(metric, sensor_id)].observe(value)

    def warm_start(self) -> None:
        """Replay recent history from the metrics table so forecasts are
        available shortly after startup instead of after two live seasons."""
        seconds = int(config.SEASON_SECONDS * (config.FORECAST_MIN_SEASONS + 1))
        try:
            rows = db.fetch_recent_metrics(seconds)
        except Exception as e:
            log.warning("Forecast warm start skipped (history unavailable): %s", e)
            return
        for metric, sensor_id, value in rows:
            self.observe(metric, sensor_id, value)
        ready = sum(1 for m in self._models.values() if m.ready)
        log.info("Forecast warm start: replayed %d rows, %d/%d series ready",
                 len(rows), ready, len(self._models))

    def refresh(self) -> None:
        now = datetime.now(timezone.utc)
        step = timedelta(seconds=config.FORECAST_SAMPLE_INTERVAL_S)
        steps = int(config.FORECAST_HORIZON_MIN * 60 / config.FORECAST_SAMPLE_INTERVAL_S)
        stride = max(1, int(config.FORECAST_POINT_INTERVAL_S / config.FORECAST_SAMPLE_INTERVAL_S))

        for (metric, sensor_id), model in self._models.items():
            if not model.ready:
                continue
            series = model.forecast(steps)
            threshold = self._thresholds.get(metric)
            points = [(now, model.fitted())]
            points += [(now + step * h, series[h - 1]) for h in range(stride, steps + 1, stride)]
            try:
                db.replace_forecast(metric, sensor_id, now, points, threshold)
            except Exception as e:
                log.error("Failed to persist forecast for %s %s: %s", metric, sensor_id, e)
                continue

            self._evaluate_crossing(metric, sensor_id, series, threshold, now, step)
            # After crossing evaluation, so a refetch sees the fresh predicted alert too.
            self._publish({"type": "forecast-changed", "metricName": metric})

    def _evaluate_crossing(self, metric: str, sensor_id: str, series: list[float],
                           threshold: float | None, now: datetime, step: timedelta) -> None:
        key = (metric, sensor_id)
        hit = first_crossing(series, threshold)
        if hit is not None:
            self._confirmations[key] += 1
            if self._confirmations[key] < config.FORECAST_CONFIRMATIONS:
                return
            crossing_at = now + step * hit
            try:
                db.upsert_predicted_alert(metric, sensor_id, threshold,
                                          series[hit - 1], crossing_at, now)
            except Exception as e:
                log.error("Failed to persist predicted alert for %s %s: %s", metric, sensor_id, e)
                return
            self._alert_active.add(key)
            if self._tracker is not None:
                self._tracker.predicted(metric, sensor_id, crossing_at, now, threshold)
            minutes = (crossing_at - now).total_seconds() / 60
            log.info("Predicted alert: %s %s expected to reach %.2f at %s (~%.0f min)",
                     metric, sensor_id, threshold, crossing_at.isoformat(timespec="seconds"),
                     minutes)
        else:
            if key in self._alert_active:
                try:
                    db.delete_predicted_alert(metric, sensor_id)
                    self._alert_active.discard(key)
                    log.info("Predicted alert cleared: %s %s", metric, sensor_id)
                except Exception as e:
                    log.error("Failed to clear predicted alert for %s %s: %s",
                              metric, sensor_id, e)
            self._confirmations[key] = 0
