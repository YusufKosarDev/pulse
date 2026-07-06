-- Graded prediction episodes: how the forecaster's threshold-breach warnings
-- compared against what actually happened.
CREATE TABLE forecast_outcomes (
    id                         BIGSERIAL PRIMARY KEY,
    metric_name                TEXT NOT NULL,
    sensor_id                  TEXT NOT NULL,
    threshold                  DOUBLE PRECISION NOT NULL,
    outcome                    TEXT NOT NULL
                               CHECK (outcome IN ('hit', 'miss', 'unwarned')),
    first_predicted_at         TIMESTAMPTZ,
    predicted_crossing_at      TIMESTAMPTZ,  -- estimate when the alert was raised
    last_predicted_crossing_at TIMESTAMPTZ,  -- freshest estimate before close
    actual_crossing_at         TIMESTAMPTZ,
    error_minutes              DOUBLE PRECISION,  -- actual minus raise-time estimate
    lead_minutes               DOUBLE PRECISION,  -- warning time before the crossing
    closed_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX forecast_outcomes_closed_idx ON forecast_outcomes (closed_at DESC);
