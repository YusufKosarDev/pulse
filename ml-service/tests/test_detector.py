import math
import random

from app.detector import EwmaDetector, ZScoreDetector

METRIC = "energy_kwh"
SENSOR = "sensor-energy-1"


def feed(detector, values):
    """Feed a sequence and return the list of (index, anomaly) results."""
    hits = []
    for i, v in enumerate(values):
        anomaly = detector.observe(METRIC, SENSOR, v)
        if anomaly is not None:
            hits.append((i, anomaly))
    return hits


def noisy_baseline(n, base=40.0, noise=1.0, seed=42):
    rng = random.Random(seed)
    return [base + rng.gauss(0, noise) for _ in range(n)]


def test_cold_start_produces_no_anomalies():
    detector = EwmaDetector(min_points=20)
    values = noisy_baseline(19) + [999.0]
    hits = feed(detector, values)
    assert hits == []


def test_spike_on_stable_baseline_is_critical():
    detector = EwmaDetector(min_points=20)
    values = noisy_baseline(60)
    feed(detector, values)
    anomaly = detector.observe(METRIC, SENSOR, 40.0 + 10.0)
    assert anomaly is not None
    assert anomaly.severity == "critical"


def test_winsorized_update_recovers_after_spike():
    detector = EwmaDetector(min_points=20)
    baseline = noisy_baseline(60)
    feed(detector, baseline)
    assert detector.observe(METRIC, SENSOR, 90.0) is not None
    # The spike must not drag the baseline along: normal values right after
    # the spike are not flagged.
    followers = noisy_baseline(10, seed=7)
    assert feed(detector, followers) == []


def test_smooth_steep_trend_not_flagged_by_ewma():
    detector = EwmaDetector(min_points=20)
    # Steady climb without noise: level tracking should absorb it entirely.
    values = noisy_baseline(30)
    values += [values[-1] + 0.5 * i for i in range(1, 60)]
    hits = [h for h in feed(detector, values) if h[0] >= 30]
    assert hits == []


def test_ewma_produces_fewer_false_positives_than_zscore_on_sine():
    # Steep sine + noise, no injected spikes: every flag is a false positive.
    rng = random.Random(1)
    n = 900  # three full cycles at 300 points per cycle
    values = [40.0 + 10.0 * math.sin(2 * math.pi * i / 300) + rng.gauss(0, 1.0)
              for i in range(n)]

    zscore_hits = feed(ZScoreDetector(), values)
    ewma_hits = feed(EwmaDetector(), values)

    assert len(ewma_hits) < len(zscore_hits)
