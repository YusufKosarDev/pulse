package com.pulse.ingest.alert;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AlertRepository {

    private static final RowMapper<Alert> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp acknowledgedAt = rs.getTimestamp("acknowledged_at");
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return new Alert(
                rs.getLong("id"),
                rs.getString("metric_name"),
                rs.getString("sensor_id"),
                rs.getString("severity"),
                rs.getString("status"),
                rs.getInt("anomaly_count"),
                rs.getTimestamp("first_seen").toInstant(),
                rs.getTimestamp("last_seen").toInstant(),
                rs.getDouble("last_value"),
                rs.getDouble("max_z_score"),
                acknowledgedAt == null ? null : acknowledgedAt.toInstant(),
                resolvedAt == null ? null : resolvedAt.toInstant());
    };

    private final JdbcTemplate jdbcTemplate;

    public AlertRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Alert> findLatest(int limit) {
        // Live alerts first, then recently resolved ones.
        return jdbcTemplate.query(
                """
                SELECT id, metric_name, sensor_id, severity, status, anomaly_count,
                       first_seen, last_seen, last_value, max_z_score,
                       acknowledged_at, resolved_at
                FROM alerts
                ORDER BY status = 'resolved', last_seen DESC
                LIMIT ?
                """,
                ROW_MAPPER, limit);
    }

    public Optional<Alert> findById(long id) {
        return jdbcTemplate.query(
                """
                SELECT id, metric_name, sensor_id, severity, status, anomaly_count,
                       first_seen, last_seen, last_value, max_z_score,
                       acknowledged_at, resolved_at
                FROM alerts
                WHERE id = ?
                """,
                ROW_MAPPER, id).stream().findFirst();
    }

    /** @return true if the alert existed in a state the transition applies to */
    public boolean acknowledge(long id) {
        return jdbcTemplate.update(
                """
                UPDATE alerts SET status = 'acknowledged', acknowledged_at = now()
                WHERE id = ? AND status = 'open'
                """, id) > 0;
    }

    /** @return true if the alert existed in a state the transition applies to */
    public boolean resolve(long id) {
        return jdbcTemplate.update(
                """
                UPDATE alerts SET status = 'resolved', resolved_at = now()
                WHERE id = ? AND status IN ('open', 'acknowledged')
                """, id) > 0;
    }
}
