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

    // Longer ranges are averaged into time_bucket windows sized so a chart
    // gets roughly this many points regardless of the range.
    private static final int TARGET_POINTS = 300;
    // At or below the sensor cadence bucketing would be a no-op; serve raw rows.
    private static final int RAW_CADENCE_SECONDS = 2;

    static int bucketSeconds(int minutes) {
        return (int) Math.ceil(minutes * 60 / (double) TARGET_POINTS);
    }

    public List<MetricPoint> findRecent(String metricName, int minutes) {
        int bucketSeconds = bucketSeconds(minutes);
        if (bucketSeconds <= RAW_CADENCE_SECONDS) {
            return jdbcTemplate.query(
                    """
                    SELECT time, value FROM metrics
                    WHERE metric_name = ? AND time >= now() - make_interval(mins => ?)
                    ORDER BY time ASC
                    """,
                    (rs, rowNum) -> new MetricPoint(rs.getTimestamp("time").toInstant(), rs.getDouble("value")),
                    metricName, minutes);
        }
        return jdbcTemplate.query(
                """
                SELECT time_bucket(make_interval(secs => ?), time) AS bucket, avg(value) AS value
                FROM metrics
                WHERE metric_name = ? AND time >= now() - make_interval(mins => ?)
                GROUP BY bucket
                ORDER BY bucket ASC
                """,
                (rs, rowNum) -> new MetricPoint(rs.getTimestamp("bucket").toInstant(), rs.getDouble("value")),
                bucketSeconds, metricName, minutes);
    }

    public List<String> findMetricNames() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT metric_name FROM metrics ORDER BY metric_name", String.class);
    }
}
