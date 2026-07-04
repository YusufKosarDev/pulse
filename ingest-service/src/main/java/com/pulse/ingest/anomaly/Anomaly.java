package com.pulse.ingest.anomaly;

import java.time.Instant;

public record Anomaly(
        Instant time,
        String metricName,
        String sensorId,
        double value,
        double zScore,
        String severity) {
}
