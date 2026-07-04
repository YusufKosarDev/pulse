-- Tag each anomaly with the detector that produced it, so detectors can run
-- side by side and be compared on the same data.
ALTER TABLE anomalies ADD COLUMN detector TEXT NOT NULL DEFAULT 'zscore';
