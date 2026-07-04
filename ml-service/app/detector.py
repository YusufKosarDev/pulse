from collections import defaultdict, deque
from dataclasses import dataclass
from statistics import mean, stdev

from . import config


@dataclass
class Anomaly:
    z_score: float
    severity: str


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
                if abs(z) >= self._threshold:
                    severity = ("critical"
                                if abs(z) >= self._threshold * self._critical_factor
                                else "warning")
                    anomaly = Anomaly(z_score=round(z, 2), severity=severity)

        window.append(value)
        return anomaly
