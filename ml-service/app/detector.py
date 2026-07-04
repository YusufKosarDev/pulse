import math
from collections import defaultdict, deque
from dataclasses import dataclass
from statistics import mean, stdev

from . import config


@dataclass
class Anomaly:
    z_score: float
    severity: str


def _classify(z: float, threshold: float, critical_factor: float) -> Anomaly | None:
    if abs(z) < threshold:
        return None
    severity = "critical" if abs(z) >= threshold * critical_factor else "warning"
    return Anomaly(z_score=round(z, 2), severity=severity)


class ZScoreDetector:
    """Rolling-window z-score detector with one window per (metric, sensor) pair.

    The z-score of a new value is computed against the statistics of the
    preceding window, then the value is appended (so a single outlier cannot
    mask itself, while persistent level shifts still adapt over time).
    No anomaly is reported until a window has at least MIN_POINTS values.
    """

    def __init__(self,
                 window_size: int = config.WINDOW_SIZE,
                 min_points: int = config.MIN_POINTS,
                 threshold: float = config.Z_THRESHOLD,
                 critical_factor: float = config.CRITICAL_FACTOR):
        self._windows: dict[tuple[str, str], deque[float]] = defaultdict(
            lambda: deque(maxlen=window_size))
        self._min_points = min_points
        self._threshold = threshold
        self._critical_factor = critical_factor

    def observe(self, metric: str, sensor_id: str, value: float) -> Anomaly | None:
        window = self._windows[(metric, sensor_id)]
        anomaly = None

        if len(window) >= self._min_points:
            mu = mean(window)
            sigma = stdev(window)
            if sigma > 0:
                z = (value - mu) / sigma
                anomaly = _classify(z, self._threshold, self._critical_factor)

        window.append(value)
        return anomaly


@dataclass
class _EwmaState:
    count: int = 0
    level: float = 0.0
    variance: float = 0.0


class EwmaDetector:
    """Exponentially weighted z-score detector, one state per (metric, sensor) pair.

    The level follows the series with EWMA smoothing, so smooth trends produce
    small residuals and are not flagged; sudden jumps still are. Each value is
    scored against the previous level/spread estimates. Before updating the
    estimates, the residual is winsorized (clamped to +/- winsor_k sigmas) so
    a single outlier cannot inflate the baseline, while persistent level
    shifts still adapt. During warm-up (first min_points values) the variance
    is a plain running average of squared residuals; after that it switches to
    EWMA smoothing, and no anomaly is reported until warm-up is complete.
    """

    def __init__(self,
                 alpha: float = config.EWMA_ALPHA,
                 beta: float = config.EWMA_BETA,
                 min_points: int = config.MIN_POINTS,
                 threshold: float = config.Z_THRESHOLD,
                 critical_factor: float = config.CRITICAL_FACTOR,
                 winsor_k: float = config.WINSOR_K):
        self._states: dict[tuple[str, str], _EwmaState] = defaultdict(_EwmaState)
        self._alpha = alpha
        self._beta = beta
        self._min_points = min_points
        self._threshold = threshold
        self._critical_factor = critical_factor
        self._winsor_k = winsor_k

    def observe(self, metric: str, sensor_id: str, value: float) -> Anomaly | None:
        state = self._states[(metric, sensor_id)]

        if state.count == 0:
            state.count = 1
            state.level = value
            return None

        residual = value - state.level
        sigma = math.sqrt(state.variance)

        anomaly = None
        if state.count >= self._min_points and sigma > 0:
            anomaly = _classify(residual / sigma, self._threshold, self._critical_factor)

        clamped = residual
        if sigma > 0:
            bound = self._winsor_k * sigma
            clamped = max(-bound, min(bound, residual))

        state.count += 1
        state.level += self._alpha * clamped
        if state.count <= self._min_points:
            # Warm-up: running average converges much faster than EWMA from zero.
            state.variance += (clamped ** 2 - state.variance) / state.count
        else:
            state.variance = self._beta * clamped ** 2 + (1 - self._beta) * state.variance

        return anomaly
