-- Grouped alerts: consecutive anomalies for one (metric, sensor) series fold
-- into a single alert row with an open/acknowledged/resolved lifecycle.
CREATE TABLE alerts (
    id              BIGSERIAL PRIMARY KEY,
    metric_name     TEXT NOT NULL,
    sensor_id       TEXT NOT NULL,
    severity        TEXT NOT NULL CHECK (severity IN ('warning', 'critical')),
    status          TEXT NOT NULL DEFAULT 'open'
                    CHECK (status IN ('open', 'acknowledged', 'resolved')),
    anomaly_count   INTEGER NOT NULL DEFAULT 1,
    first_seen      TIMESTAMPTZ NOT NULL,
    last_seen       TIMESTAMPTZ NOT NULL,
    last_value      DOUBLE PRECISION NOT NULL,
    max_z_score     DOUBLE PRECISION NOT NULL,
    acknowledged_at TIMESTAMPTZ,
    resolved_at     TIMESTAMPTZ
);

-- At most one live alert per series; new detections upsert into it via
-- ON CONFLICT against this partial index.
CREATE UNIQUE INDEX alerts_one_active_per_series
    ON alerts (metric_name, sensor_id)
    WHERE status <> 'resolved';

CREATE INDEX alerts_last_seen_idx ON alerts (last_seen DESC);
