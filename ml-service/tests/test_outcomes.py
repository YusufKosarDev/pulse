from datetime import datetime, timedelta, timezone

import pytest

from app import outcomes


NOW = datetime(2026, 7, 6, 12, 0, tzinfo=timezone.utc)


@pytest.fixture
def recorded(monkeypatch):
    """Capture outcome rows instead of writing them to the database."""
    rows = []

    def fake_insert(metric, sensor, threshold, outcome, first_predicted_at,
                    predicted_crossing_at, last_predicted_crossing_at,
                    actual_crossing_at, error_minutes, lead_minutes):
        rows.append({
            "metric": metric, "outcome": outcome,
            "predicted_crossing_at": predicted_crossing_at,
            "actual_crossing_at": actual_crossing_at,
            "error_minutes": error_minutes, "lead_minutes": lead_minutes,
        })

    monkeypatch.setattr(outcomes.db, "insert_forecast_outcome", fake_insert)
    return rows


def make_tracker():
    # temperature_c threshold is 26.0 in the default config
    return outcomes.OutcomeTracker()


def test_real_crossing_after_prediction_is_a_hit(recorded):
    tracker = make_tracker()
    tracker.observe("temperature_c", "s1", 24.0, NOW)  # seed below-threshold state
    tracker.predicted("temperature_c", "s1", NOW + timedelta(minutes=4), NOW, 26.0)

    tracker.observe("temperature_c", "s1", 26.4, NOW + timedelta(minutes=5))

    assert [r["outcome"] for r in recorded] == ["hit"]
    assert recorded[0]["error_minutes"] == pytest.approx(1.0)   # 1 min later than predicted
    assert recorded[0]["lead_minutes"] == pytest.approx(5.0)    # warned 5 min ahead


def test_prediction_without_crossing_expires_as_miss(recorded):
    tracker = make_tracker()
    tracker.observe("temperature_c", "s1", 24.0, NOW)
    tracker.predicted("temperature_c", "s1", NOW + timedelta(minutes=4), NOW, 26.0)

    tracker.expire(NOW + timedelta(minutes=8))   # within grace: still open
    assert recorded == []
    tracker.expire(NOW + timedelta(minutes=10))  # past crossing + 5 min grace

    assert [r["outcome"] for r in recorded] == ["miss"]
    assert recorded[0]["actual_crossing_at"] is None


def test_reconfirmed_episode_expires_at_max_age(recorded, monkeypatch):
    # Cap the episode age well below the default so the test stays readable.
    monkeypatch.setattr(outcomes.config, "FORECAST_OUTCOME_MAX_AGE_MIN", 20)
    tracker = make_tracker()
    tracker.observe("temperature_c", "s1", 24.0, NOW)
    tracker.predicted("temperature_c", "s1", NOW + timedelta(minutes=4), NOW, 26.0)
    # A refresh keeps re-confirming and pushing the predicted crossing forward,
    # which would keep the grace deadline moving too.
    tracker.predicted("temperature_c", "s1", NOW + timedelta(minutes=30),
                      NOW + timedelta(minutes=15), 26.0)

    # Grace alone would not close it yet (last estimate + 5 min = 35 min), but
    # the episode is now older than the 20-minute cap from its raise time.
    tracker.expire(NOW + timedelta(minutes=21))

    assert [r["outcome"] for r in recorded] == ["miss"]


def test_crossing_without_prediction_is_unwarned(recorded):
    tracker = make_tracker()
    tracker.observe("temperature_c", "s1", 24.0, NOW)
    tracker.observe("temperature_c", "s1", 27.0, NOW + timedelta(minutes=1))

    assert [r["outcome"] for r in recorded] == ["unwarned"]


def test_hovering_above_threshold_counts_once(recorded):
    tracker = make_tracker()
    tracker.observe("temperature_c", "s1", 24.0, NOW)
    for minute in range(1, 5):  # crosses once, then stays above
        tracker.observe("temperature_c", "s1", 27.0, NOW + timedelta(minutes=minute))

    assert len(recorded) == 1


def test_first_observation_above_threshold_is_not_a_crossing(recorded):
    tracker = make_tracker()
    tracker.observe("temperature_c", "s1", 27.0, NOW)  # state seeding only
    assert recorded == []


def test_updated_estimates_keep_raise_time_error_baseline(recorded):
    tracker = make_tracker()
    tracker.observe("temperature_c", "s1", 24.0, NOW)
    tracker.predicted("temperature_c", "s1", NOW + timedelta(minutes=4), NOW, 26.0)
    # A later refresh revises the estimate; the raise-time estimate must stick.
    tracker.predicted("temperature_c", "s1", NOW + timedelta(minutes=7),
                      NOW + timedelta(minutes=1), 26.0)

    tracker.observe("temperature_c", "s1", 26.4, NOW + timedelta(minutes=5))

    assert recorded[0]["predicted_crossing_at"] == NOW + timedelta(minutes=4)
    assert recorded[0]["error_minutes"] == pytest.approx(1.0)


def test_metrics_without_threshold_are_ignored(recorded):
    tracker = make_tracker()
    tracker.observe("unknown_metric", "s1", 1.0, NOW)
    tracker.observe("unknown_metric", "s1", 1000.0, NOW + timedelta(minutes=1))
    assert recorded == []
