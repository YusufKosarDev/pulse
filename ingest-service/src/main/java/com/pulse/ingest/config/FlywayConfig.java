package com.pulse.ingest.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayConfig {

    /**
     * Repair before migrating.
     *
     * <p>V1 was changed after release to make the TimescaleDB extension optional
     * (the schema now degrades gracefully to plain PostgreSQL). That edit changes
     * V1's checksum, which would otherwise fail validation on any database that
     * already applied the original V1 — including a running deployment. {@code
     * repair()} realigns the stored checksum with the current file without
     * re-running the migration, so existing databases keep working untouched and
     * fresh databases are unaffected.
     */
    @Bean
    public FlywayMigrationStrategy repairBeforeMigrate() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}
