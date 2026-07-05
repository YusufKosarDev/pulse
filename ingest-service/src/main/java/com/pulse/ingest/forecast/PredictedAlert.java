package com.pulse.ingest.forecast;

import java.time.Instant;

public record PredictedAlert(
        String metricName,
        String sensorId,
        double threshold,
        double predictedValue,
        Instant predictedCrossingAt,
        Instant updatedAt) {
}
