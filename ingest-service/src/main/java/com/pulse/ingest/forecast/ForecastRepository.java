package com.pulse.ingest.forecast;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.pulse.ingest.metric.MetricPoint;

@Repository
public class ForecastRepository {

    private record ForecastRow(String sensorId, java.time.Instant generatedAt,
                               java.time.Instant targetTime, double value, Double threshold) {
    }

    private final JdbcTemplate jdbcTemplate;

    public ForecastRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ForecastSeries findByMetric(String metricName) {
        List<ForecastRow> rows = jdbcTemplate.query(
                """
                SELECT sensor_id, generated_at, target_time, value, threshold FROM forecasts
                WHERE metric_name = ?
                ORDER BY target_time ASC
                """,
                (rs, rowNum) -> new ForecastRow(
                        rs.getString("sensor_id"),
                        rs.getTimestamp("generated_at").toInstant(),
                        rs.getTimestamp("target_time").toInstant(),
                        rs.getDouble("value"),
                        rs.getObject("threshold", Double.class)),
                metricName);
        if (rows.isEmpty()) {
            return ForecastSeries.empty(metricName);
        }
        ForecastRow first = rows.get(0);
        List<MetricPoint> points = rows.stream()
                .map(r -> new MetricPoint(r.targetTime(), r.value()))
                .toList();
        return new ForecastSeries(metricName, first.sensorId(), first.generatedAt(),
                first.threshold(), points);
    }

    public List<PredictedAlert> findActiveAlerts() {
        // Rows stop being refreshed once the prediction is withdrawn, so stale
        // ones are filtered out here as a safety net.
        return jdbcTemplate.query(
                """
                SELECT metric_name, sensor_id, threshold, predicted_value,
                       predicted_crossing_at, updated_at
                FROM predicted_alerts
                WHERE updated_at > now() - interval '2 minutes'
                  AND predicted_crossing_at > now()
                ORDER BY predicted_crossing_at ASC
                """,
                (rs, rowNum) -> new PredictedAlert(
                        rs.getString("metric_name"),
                        rs.getString("sensor_id"),
                        rs.getDouble("threshold"),
                        rs.getDouble("predicted_value"),
                        rs.getTimestamp("predicted_crossing_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()));
    }
}
