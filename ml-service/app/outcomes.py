import json
import logging
from dataclasses import dataclass
from datetime import datetime, timedelta

from . import config, db

log = logging.getLogger("ml.outcomes")


@dataclass
class _Episode:
    threshold: float
    first_predicted_at: datetime
    predicted_crossing_at: datetime      # the estimate when the alert was raised
    last_predicted_crossing_at: datetime  # freshest estimate before close


class OutcomeTracker:
    """Grades predicted threshold-breach alerts against what actually happened.

    An episode opens when a predicted alert is raised. It closes as:
      - hit      — the value really crossed the threshold (below -> above edge);
                   error is measured against the estimate at raise time
      - miss     — no crossing until a grace period past the last estimate
    A crossing with no open episode is recorded as `unwarned` — the forecaster
    failed to warn. Crossings only count on a below -> above edge, so a value
    hovering above the threshold is one event, not one per reading.
    """

    def __init__(self, publish=None):
        self._episodes: dict[tuple[str, str], _Episode] = {}
        self._above: dict[tuple[str, str], bool] = {}
        self._thresholds: dict[str, float] = json.loads(config.THRESHOLDS)
        self._grace = timedelta(minutes=config.FORECAST_OUTCOME_GRACE_MIN)
        self._publish = publish or (lambda payload: None)

    def predicted(self, metric: str, sensor_id: str, crossing_at: datetime,
                  now: datetime, threshold: float) -> None:
        key = (metric, sensor_id)
        episode = self._episodes.get(key)
        if episode is None:
            self._episodes[key] = _Episode(threshold, now, crossing_at, crossing_at)
        else:
            episode.last_predicted_crossing_at = crossing_at

    def observe(self, metric: str, sensor_id: str, value: float, ts: datetime) -> None:
        threshold = self._thresholds.get(metric)
        if threshold is None:
            return
        key = (metric, sensor_id)
        was_above = self._above.get(key)
        is_above = value >= threshold
        self._above[key] = is_above
        # The first observation only seeds the state; afterwards, count edges.
        if was_above is None or was_above or not is_above:
            return

        episode = self._episodes.pop(key, None)
        if episode is not None:
            error_min = (ts - episode.predicted_crossing_at).total_seconds() / 60
            lead_min = (ts - episode.first_predicted_at).total_seconds() / 60
            self._record(metric, sensor_id, threshold, "hit", episode,
                         actual_crossing_at=ts, error_minutes=error_min, lead_minutes=lead_min)
        else:
            self._record(metric, sensor_id, threshold, "unwarned", None,
                         actual_crossing_at=ts)

    def expire(self, now: datetime) -> None:
        """Close episodes whose predicted crossing is long past without a hit."""
        for key, episode in list(self._episodes.items()):
            if now > episode.last_predicted_crossing_at + self._grace:
                del self._episodes[key]
                self._record(key[0], key[1], episode.threshold, "miss", episode)

    def _record(self, metric: str, sensor_id: str, threshold: float, outcome: str,
                episode: _Episode | None, actual_crossing_at: datetime | None = None,
                error_minutes: float | None = None, lead_minutes: float | None = None) -> None:
        try:
            db.insert_forecast_outcome(
                metric, sensor_id, threshold, outcome,
                episode.first_predicted_at if episode else None,
                episode.predicted_crossing_at if episode else None,
                episode.last_predicted_crossing_at if episode else None,
                actual_crossing_at, error_minutes, lead_minutes)
        except Exception as e:
            log.error("Failed to persist %s outcome for %s %s: %s",
                      outcome, metric, sensor_id, e)
            return
        log.info("Forecast outcome [%s]: %s %s%s", outcome, metric, sensor_id,
                 f" error={error_minutes:+.1f}min lead={lead_minutes:.1f}min"
                 if outcome == "hit" else "")
        self._publish({"type": "forecast-outcomes-changed"})
