package com.pulse.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import com.pulse.ingest.metric.MetricPoint;
import com.pulse.ingest.metric.MetricRepository;

/**
 * Proves the graceful degradation to plain PostgreSQL: the Flyway migrations
 * apply cleanly against a stock {@code postgres} image with no TimescaleDB
 * extension, and the downsampling query in {@link MetricRepository} buckets
 * correctly there. The TimescaleDB path is covered by
 * {@link RepositoryIntegrationTest}.
 *
 * Skipped automatically when no Docker daemon is available.
 */
class PlainPostgresMigrationTest {

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available; skipping plain-PostgreSQL migration test");
    }

    @Test
    void migrationsApplyAndBucketingWorksWithoutTimescale() {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")) {
            postgres.start();

            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .load()
                    .migrate();

            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

            // The extension really is absent — this is a genuine plain PostgreSQL.
            Integer timescaleAvailable = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_available_extensions WHERE name = 'timescaledb'",
                    Integer.class);
            assertThat(timescaleAvailable).isZero();

            // 30 readings, 2 s apart, all within the last minute.
            jdbcTemplate.update(
                    """
                    INSERT INTO metrics (time, metric_name, sensor_id, value)
                    SELECT now() - make_interval(secs => n * 2), 'bucket_metric', 'sensor-1', n
                    FROM generate_series(1, 30) AS n
                    """);

            MetricRepository metricRepository = new MetricRepository(jdbcTemplate);
            List<MetricPoint> raw = metricRepository.findRecent("bucket_metric", 10);
            List<MetricPoint> bucketed = metricRepository.findRecent("bucket_metric", 60);

            assertThat(raw).hasSize(30);
            // 60 s of data in 12 s buckets: at most 6 averaged points.
            assertThat(bucketed).hasSizeBetween(2, 6);
            assertThat(bucketed.size()).isLessThan(raw.size());
        }
    }
}
