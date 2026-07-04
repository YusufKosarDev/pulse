package com.pulse.ingest.metric;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MetricRepository {

    private final JdbcTemplate jdbcTemplate;

    public MetricRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Instant time, String metricName, String sensorId, double value) {
        jdbcTemplate.update(
                "INSERT INTO metrics (time, metric_name, sensor_id, value) VALUES (?, ?, ?, ?)",
                Timestamp.from(time), metricName, sensorId, value);
    }
}
