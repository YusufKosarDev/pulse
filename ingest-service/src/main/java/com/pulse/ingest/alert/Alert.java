package com.pulse.ingest.alert;

import java.time.Instant;

public record Alert(
        long id,
        String metricName,
        String sensorId,
        String severity,
        String status,
        int anomalyCount,
        Instant firstSeen,
        Instant lastSeen,
        double lastValue,
        double maxZScore,
        Instant acknowledgedAt,
        Instant resolvedAt) {
}
