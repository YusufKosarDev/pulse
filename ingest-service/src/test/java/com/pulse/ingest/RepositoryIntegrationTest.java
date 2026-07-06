package com.pulse.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.pulse.ingest.alert.Alert;
import com.pulse.ingest.alert.AlertRepository;
import com.pulse.ingest.forecast.ForecastOutcomeRepository;
import com.pulse.ingest.forecast.ForecastOutcomeStats;
import com.pulse.ingest.forecast.ForecastRepository;
import com.pulse.ingest.forecast.ForecastSeries;
import com.pulse.ingest.metric.MetricPoint;
import com.pulse.ingest.metric.MetricRepository;

/**
 * Exercises the repositories' SQL against a real TimescaleDB with the Flyway
 * migrations applied — the schema contracts (partial indexes, freshness
 * filters, time_bucket) are exactly what unit tests cannot cover.
 *
 * Runs against the docker-compose TimescaleDB (host port 5435) in a separate
 * {@code pulse_test} database, so dev data is never touched; each test rolls
 * back. Skipped automatically when the compose stack is not running.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AlertRepository.class, ForecastRepository.class, MetricRepository.class,
        ForecastOutcomeRepository.class})
@EnabledIf(value = "databaseAvailable",
        disabledReason = "compose TimescaleDB (localhost:5435) is not running")
class RepositoryIntegrationTest {

    private static final String HOST = env("PULSE_TEST_DB_HOST", "localhost");
    private static final int PORT = Integer.parseInt(env("PULSE_TEST_DB_PORT", "5435"));
    private static final String USER = env("POSTGRES_USER", "pulse");
    private static final String PASSWORD = env("POSTGRES_PASSWORD", "pulse_local_dev");
    private static final String TEST_DB = "pulse_test";

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static boolean databaseAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        createTestDatabaseIfAbsent();
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://" + HOST + ":" + PORT + "/" + TEST_DB);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
    }

    private static void createTestDatabaseIfAbsent() {
        String url = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + env("POSTGRES_DB", "pulse");
        try (Connection connection = DriverManager.getConnection(url, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + TEST_DB);
        } catch (SQLException e) {
            if (!"42P04".equals(e.getSQLState())) { // duplicate_database: fine on reruns
                throw new IllegalStateException("Could not create " + TEST_DB, e);
            }
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AlertRepository alertRepository;
    @Autowired
    private ForecastRepository forecastRepository;
    @Autowired
    private MetricRepository metricRepository;
    @Autowired
    private ForecastOutcomeRepository outcomeRepository;

    private long insertAlert(String metric, String status, String lastSeenOffset) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO alerts (metric_name, sensor_id, severity, status, anomaly_count,
                                    first_seen, last_seen, last_value, max_z_score)
                VALUES (?, 'sensor-1', 'critical', ?, 3,
                        now() - interval '5 minutes', now() - ?::interval, 42.0, 8.5)
                RETURNING id
                """,
                Long.class, metric, status, lastSeenOffset);
    }

    @Test
    void alertTransitionsOnlyApplyFromValidStates() {
        long id = insertAlert("energy_kwh", "open", "1 minute");

        assertThat(alertRepository.acknowledge(id)).isTrue();
        assertThat(alertRepository.acknowledge(id)).isFalse(); // already acknowledged
        assertThat(alertRepository.resolve(id)).isTrue();
        assertThat(alertRepository.resolve(id)).isFalse();     // already resolved
        assertThat(alertRepository.acknowledge(id)).isFalse(); // resolved is terminal

        Alert alert = alertRepository.findById(id).orElseThrow();
        assertThat(alert.status()).isEqualTo("resolved");
        assertThat(alert.acknowledgedAt()).isNotNull();
        assertThat(alert.resolvedAt()).isNotNull();
    }

    @Test
    void latestAlertsListLiveOnesBeforeResolved() {
        insertAlert("temperature_c", "resolved", "1 minute");
        insertAlert("energy_kwh", "open", "3 minutes");

        List<Alert> alerts = alertRepository.findLatest(10);

        assertThat(alerts).hasSize(2);
        // The open alert comes first although the resolved one is more recent.
        assertThat(alerts.get(0).status()).isEqualTo("open");
        assertThat(alerts.get(1).status()).isEqualTo("resolved");
    }

    @Test
    void onlyOneLiveAlertPerSeriesIsAllowed() {
        // Resolved history rows for a series may pile up freely...
        insertAlert("energy_kwh", "resolved", "1 minute");
        insertAlert("energy_kwh", "open", "1 minute");

        // ...but the partial unique index rejects a second live alert. This
        // aborts the test transaction, so it must be the last statement here.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> insertAlert("energy_kwh", "acknowledged", "1 minute"))
                .hasMessageContaining("alerts_one_active_per_series");
    }

    @Test
    void staleForecastsAreNotServed() {
        jdbcTemplate.update(
                """
                INSERT INTO forecasts (metric_name, sensor_id, generated_at, target_time, value, threshold)
                VALUES ('fresh_metric', 'sensor-1', now(), now() + interval '1 minute', 20.0, 26.0),
                       ('stale_metric', 'sensor-1', now() - interval '1 day',
                        now() - interval '1 day' + interval '1 minute', 20.0, 26.0)
                """);

        ForecastSeries fresh = forecastRepository.findByMetric("fresh_metric");
        ForecastSeries stale = forecastRepository.findByMetric("stale_metric");

        assertThat(fresh.points()).hasSize(1);
        assertThat(fresh.threshold()).isEqualTo(26.0);
        // Leftover rows from before a downtime must come back as an empty series.
        assertThat(stale.points()).isEmpty();
        assertThat(stale.generatedAt()).isNull();
    }

    @Test
    void outcomeStatsAggregateOnlyGradedEpisodesInsideTheWindow() {
        jdbcTemplate.update(
                """
                INSERT INTO forecast_outcomes
                    (metric_name, sensor_id, threshold, outcome, error_minutes, lead_minutes, closed_at)
                VALUES ('temperature_c', 's1', 26.0, 'hit',      2.0, 4.0, now()),
                       ('temperature_c', 's1', 26.0, 'hit',     -1.0, 6.0, now()),
                       ('temperature_c', 's1', 26.0, 'miss',    NULL, NULL, now()),
                       ('energy_kwh',    's1', 65.0, 'unwarned', NULL, NULL, now()),
                       ('temperature_c', 's1', 26.0, 'miss',    NULL, NULL, now() - interval '2 days')
                """);

        ForecastOutcomeStats stats = outcomeRepository.stats(24);

        assertThat(stats.hits()).isEqualTo(2);
        assertThat(stats.misses()).isEqualTo(1); // the two-day-old miss is outside the window
        assertThat(stats.unwarned()).isEqualTo(1);
        assertThat(stats.hitRate()).isEqualTo(2.0 / 3.0);
        assertThat(stats.avgAbsErrorMinutes()).isEqualTo(1.5); // |2.0| and |-1.0|
        assertThat(stats.avgLeadMinutes()).isEqualTo(5.0);
    }

    @Test
    void longRangesAreBucketedShortRangesStayRaw() {
        // 30 readings, 2 s apart, all within the last minute.
        jdbcTemplate.update(
                """
                INSERT INTO metrics (time, metric_name, sensor_id, value)
                SELECT now() - make_interval(secs => n * 2), 'bucket_metric', 'sensor-1', n
                FROM generate_series(1, 30) AS n
                """);

        List<MetricPoint> raw = metricRepository.findRecent("bucket_metric", 10);
        List<MetricPoint> bucketed = metricRepository.findRecent("bucket_metric", 60);

        assertThat(raw).hasSize(30);
        // 60 s of data in 12 s buckets: at most 6 averaged points.
        assertThat(bucketed).hasSizeBetween(2, 6);
        assertThat(bucketed.size()).isLessThan(raw.size());
    }
}
