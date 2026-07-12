CREATE TABLE metrics (
    time        TIMESTAMPTZ      NOT NULL,
    metric_name TEXT             NOT NULL,
    sensor_id   TEXT             NOT NULL,
    value       DOUBLE PRECISION NOT NULL
);

-- TimescaleDB is the preferred backend: turn metrics into a hypertable for
-- time-partitioned storage. When the extension is not available (a plain
-- PostgreSQL deployment), skip it gracefully — metrics stays an ordinary table
-- served by the btree index below, and every metrics query in the service is
-- written to run identically on both.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'timescaledb') THEN
        CREATE EXTENSION IF NOT EXISTS timescaledb;
        PERFORM create_hypertable('metrics', 'time');
    END IF;
END $$;

CREATE INDEX idx_metrics_name_sensor_time
    ON metrics (metric_name, sensor_id, time DESC);
