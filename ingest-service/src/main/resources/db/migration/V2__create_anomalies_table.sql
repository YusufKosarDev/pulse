-- Anomalies are low-volume compared to raw metrics, so a plain table is enough.
CREATE TABLE anomalies (
    time        TIMESTAMPTZ      NOT NULL,
    metric_name TEXT             NOT NULL,
    sensor_id   TEXT             NOT NULL,
    value       DOUBLE PRECISION NOT NULL,
    z_score     DOUBLE PRECISION NOT NULL,
    severity    TEXT             NOT NULL
);

CREATE INDEX idx_anomalies_time ON anomalies (time DESC);
CREATE INDEX idx_anomalies_metric_time ON anomalies (metric_name, time DESC);
