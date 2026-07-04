package com.pulse.ingest.anomaly;

import java.util.List;

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

    public AnomalyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Anomaly> findRecent(String metricName, int minutes) {
        return jdbcTemplate.query(
                """
                SELECT time, metric_name, sensor_id, value, z_score, severity FROM anomalies
                WHERE metric_name = ? AND time >= now() - make_interval(mins => ?)
                ORDER BY time ASC
                """,
                ROW_MAPPER, metricName, minutes);
    }

    public List<Anomaly> findLatest(int limit) {
        return jdbcTemplate.query(
                """
                SELECT time, metric_name, sensor_id, value, z_score, severity FROM anomalies
                ORDER BY time DESC
                LIMIT ?
                """,
                ROW_MAPPER, limit);
    }
}
