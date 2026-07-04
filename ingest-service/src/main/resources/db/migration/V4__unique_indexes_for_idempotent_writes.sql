-- Reclaimed stream entries can be processed twice (consumer crashed after the
-- write but before the ack). Unique indexes let inserts be idempotent via
-- ON CONFLICT DO NOTHING. Existing duplicates are removed first.

DELETE FROM metrics a USING metrics b
WHERE a.ctid < b.ctid
  AND a.time = b.time
  AND a.metric_name = b.metric_name
  AND a.sensor_id = b.sensor_id;

CREATE UNIQUE INDEX uq_metrics_metric_sensor_time
    ON metrics (metric_name, sensor_id, time);

DELETE FROM anomalies a USING anomalies b
WHERE a.ctid < b.ctid
  AND a.time = b.time
  AND a.metric_name = b.metric_name
  AND a.sensor_id = b.sensor_id
  AND a.detector = b.detector;

CREATE UNIQUE INDEX uq_anomalies_metric_sensor_detector_time
    ON anomalies (metric_name, sensor_id, detector, time);
