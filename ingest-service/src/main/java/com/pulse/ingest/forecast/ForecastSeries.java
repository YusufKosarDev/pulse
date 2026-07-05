package com.pulse.ingest.forecast;

import java.time.Instant;
import java.util.List;

import com.pulse.ingest.metric.MetricPoint;

public record ForecastSeries(
        String metricName,
        String sensorId,
        Instant generatedAt,
        Double threshold,
        List<MetricPoint> points) {

    public static ForecastSeries empty(String metricName) {
        return new ForecastSeries(metricName, null, null, null, List.of());
    }
}
