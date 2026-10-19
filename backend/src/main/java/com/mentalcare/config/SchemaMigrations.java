package com.mentalcare.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Tiny forward-only migrations for columns added after the first release. schema.sql only creates
 * tables that do not exist, so new columns on existing tables are added here (guarded by PRAGMA).
 */
@Component
@DependsOnDatabaseInitialization
public class SchemaMigrations {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrations.class);

    private final JdbcClient jdbc;

    public SchemaMigrations(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void migrate() {
        addColumnIfMissing("sessions", "kind", "TEXT NOT NULL DEFAULT 'COUNSELING'");
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        boolean exists = jdbc.sql("PRAGMA table_info(" + table + ")")
                .query((rs, i) -> rs.getString("name")).list().stream().anyMatch(column::equalsIgnoreCase);
        if (!exists) {
            jdbc.sql("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition).update();
            log.info("schema migration: added {}.{}", table, column);
        }
    }
}
