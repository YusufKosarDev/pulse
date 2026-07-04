CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE metrics (
    time        TIMESTAMPTZ      NOT NULL,
    metric_name TEXT             NOT NULL,
    sensor_id   TEXT             NOT NULL,
    value       DOUBLE PRECISION NOT NULL
);

SELECT create_hypertable('metrics', 'time');

CREATE INDEX idx_metrics_name_sensor_time
    ON metrics (metric_name, sensor_id, time DESC);
