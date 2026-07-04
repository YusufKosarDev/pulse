package com.pulse.ingest.metric;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MetricRepository {

    private final JdbcTemplate jdbcTemplate;

    public MetricRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Instant time, String metricName, String sensorId, double value) {
        // Idempotent: a reclaimed stream entry may be processed twice.
        jdbcTemplate.update(
                """
                INSERT INTO metrics (time, metric_name, sensor_id, value) VALUES (?, ?, ?, ?)
                ON CONFLICT (metric_name, sensor_id, time) DO NOTHING
                """,
                Timestamp.from(time), metricName, sensorId, value);
    }

    public List<MetricPoint> findRecent(String metricName, int minutes) {
        return jdbcTemplate.query(
                """
                SELECT time, value FROM metrics
                WHERE metric_name = ? AND time >= now() - make_interval(mins => ?)
                ORDER BY time ASC
                """,
                (rs, rowNum) -> new MetricPoint(rs.getTimestamp("time").toInstant(), rs.getDouble("value")),
                metricName, minutes);
    }

    public List<String> findMetricNames() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT metric_name FROM metrics ORDER BY metric_name", String.class);
    }
}
