package com.pulse.ingest.forecast;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ForecastOutcomeRepository {

    private static final RowMapper<ForecastOutcome> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp predicted = rs.getTimestamp("predicted_crossing_at");
        Timestamp actual = rs.getTimestamp("actual_crossing_at");
        return new ForecastOutcome(
                rs.getLong("id"),
                rs.getString("metric_name"),
                rs.getString("sensor_id"),
                rs.getDouble("threshold"),
                rs.getString("outcome"),
                predicted == null ? null : predicted.toInstant(),
                actual == null ? null : actual.toInstant(),
                rs.getObject("error_minutes", Double.class),
                rs.getObject("lead_minutes", Double.class),
                rs.getTimestamp("closed_at").toInstant());
    };

    private final JdbcTemplate jdbcTemplate;

    public ForecastOutcomeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ForecastOutcome> findLatest(int limit) {
        return jdbcTemplate.query(
                """
                SELECT id, metric_name, sensor_id, threshold, outcome,
                       predicted_crossing_at, actual_crossing_at,
                       error_minutes, lead_minutes, closed_at
                FROM forecast_outcomes
                ORDER BY closed_at DESC
                LIMIT ?
                """,
                ROW_MAPPER, limit);
    }

    public ForecastOutcomeStats stats(int hours) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count(*) FILTER (WHERE outcome = 'hit')      AS hits,
                       count(*) FILTER (WHERE outcome = 'miss')     AS misses,
                       count(*) FILTER (WHERE outcome = 'unwarned') AS unwarned,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY abs(error_minutes))
                           FILTER (WHERE outcome = 'hit') AS median_abs_error,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY lead_minutes)
                           FILTER (WHERE outcome = 'hit') AS median_lead,
                       avg(abs(error_minutes)) FILTER (WHERE outcome = 'hit') AS avg_abs_error,
                       avg(lead_minutes)       FILTER (WHERE outcome = 'hit') AS avg_lead
                FROM forecast_outcomes
                WHERE closed_at > now() - make_interval(hours => ?)
                """,
                (rs, rowNum) -> {
                    int hits = rs.getInt("hits");
                    int misses = rs.getInt("misses");
                    Double hitRate = hits + misses == 0
                            ? null : (double) hits / (hits + misses);
                    return new ForecastOutcomeStats(
                            hits, misses, rs.getInt("unwarned"), hitRate,
                            rs.getObject("median_abs_error", Double.class),
                            rs.getObject("median_lead", Double.class),
                            rs.getObject("avg_abs_error", Double.class),
                            rs.getObject("avg_lead", Double.class));
                },
                hours);
    }
}
