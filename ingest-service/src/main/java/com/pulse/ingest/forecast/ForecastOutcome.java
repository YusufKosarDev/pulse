package com.pulse.ingest.forecast;

import java.time.Instant;

public record ForecastOutcome(
        long id,
        String metricName,
        String sensorId,
        double threshold,
        String outcome,
        Instant predictedCrossingAt,
        Instant actualCrossingAt,
        Double errorMinutes,
        Double leadMinutes,
        Instant closedAt) {
}
