import math
import random

from app.forecaster import HoltWinters, first_crossing

SEASON = 20  # small season for fast tests


def make_model(**kwargs):
    defaults = dict(season_length=SEASON, alpha=0.3, beta=0.05, gamma=0.1, min_seasons=2)
    defaults.update(kwargs)
    return HoltWinters(**defaults)


def sine_series(n, base=22.0, amplitude=2.0, noise=0.0, seed=3):
    rng = random.Random(seed)
    return [base + amplitude * math.sin(2 * math.pi * i / SEASON) + rng.gauss(0, noise)
            for i in range(n)]


def test_not_ready_before_min_seasons():
    model = make_model()
    for v in sine_series(2 * SEASON - 1):
        model.observe(v)
    assert not model.ready
    model.observe(22.0)
    assert model.ready


def test_flat_series_predicts_no_crossing():
    model = make_model()
    for _ in range(4 * SEASON):
        model.observe(22.0)
    series = model.forecast(100)
    assert first_crossing(series, 26.0) is None
    assert all(abs(v - 22.0) < 0.5 for v in series)


def test_sine_without_trend_stays_below_outside_threshold():
    model = make_model()
    for v in sine_series(6 * SEASON, noise=0.2):
        model.observe(v)
    series = model.forecast(3 * SEASON)
    # Threshold sits outside the sine's normal band, so no crossing is predicted.
    assert first_crossing(series, 26.0) is None


def test_ramp_predicts_upcoming_crossing():
    model = make_model()
    values = sine_series(4 * SEASON, noise=0.1)
    for v in values:
        model.observe(v)
    # Sustained upward drift, as in the demo scenario.
    last = values[-1]
    for i in range(1, 2 * SEASON + 1):
        model.observe(last + 0.1 * i)
    series = model.forecast(10 * SEASON)
    hit = first_crossing(series, 30.0)
    assert hit is not None
    # Trend is ~0.1/step; the crossing must land in a plausible window, not "immediately".
    assert 5 <= hit <= 6 * SEASON


def test_no_crossing_when_threshold_missing():
    assert first_crossing([1.0, 2.0, 3.0], None) is None
