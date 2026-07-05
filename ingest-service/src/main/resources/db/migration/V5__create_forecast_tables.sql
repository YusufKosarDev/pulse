-- Forecast curve points, replaced wholesale on every refresh (one batch per
-- metric/sensor). Small by design: the horizon is downsampled before writing.
CREATE TABLE forecasts (
    metric_name  TEXT             NOT NULL,
    sensor_id    TEXT             NOT NULL,
    generated_at TIMESTAMPTZ      NOT NULL,
    target_time  TIMESTAMPTZ      NOT NULL,
    value        DOUBLE PRECISION NOT NULL,
    threshold    DOUBLE PRECISION,
    PRIMARY KEY (metric_name, sensor_id, target_time)
);

-- At most one active predicted alert per metric/sensor; the row is upserted
-- while the forecast keeps predicting a crossing and deleted when it stops,
-- so stale predictions expire on their own.
CREATE TABLE predicted_alerts (
    metric_name           TEXT             NOT NULL,
    sensor_id             TEXT             NOT NULL,
    threshold             DOUBLE PRECISION NOT NULL,
    predicted_value       DOUBLE PRECISION NOT NULL,
    predicted_crossing_at TIMESTAMPTZ      NOT NULL,
    first_predicted_at    TIMESTAMPTZ      NOT NULL,
    updated_at            TIMESTAMPTZ      NOT NULL,
    PRIMARY KEY (metric_name, sensor_id)
);
