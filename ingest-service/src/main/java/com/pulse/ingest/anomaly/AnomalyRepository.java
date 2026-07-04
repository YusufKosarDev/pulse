package com.pulse.ingest.anomaly;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AnomalyRepository {

    private static final RowMapper<Anomaly> ROW_MAPPER = (rs, rowNum) -> new Anomaly(
            rs.getTimestamp("time").toInstant(),
            rs.getString("metric_name"),
            rs.getString("sensor_id"),
            rs.getDouble("value"),
            rs.getDouble("z_score"),
            rs.getString("severity"));

    private final JdbcTemplate jdbcTemplate;

    // While detectors run side by side, the dashboard shows only one of them.
    private final String displayDetector;

    public AnomalyRepository(JdbcTemplate jdbcTemplate,
                             @Value("${pulse.anomalies.display-detector}") String displayDetector) {
        this.jdbcTemplate = jdbcTemplate;
        this.displayDetector = displayDetector;
    }

    public List<Anomaly> findRecent(String metricName, int minutes) {
        return jdbcTemplate.query(
                """
                SELECT time, metric_name, sensor_id, value, z_score, severity FROM anomalies
                WHERE metric_name = ? AND detector = ? AND time >= now() - make_interval(mins => ?)
                ORDER BY time ASC
                """,
                ROW_MAPPER, metricName, displayDetector, minutes);
    }

    public List<Anomaly> findLatest(int limit) {
        return jdbcTemplate.query(
                """
                SELECT time, metric_name, sensor_id, value, z_score, severity FROM anomalies
                WHERE detector = ?
                ORDER BY time DESC
                LIMIT ?
                """,
                ROW_MAPPER, displayDetector, limit);
    }
}
