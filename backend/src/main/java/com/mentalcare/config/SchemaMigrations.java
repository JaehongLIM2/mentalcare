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
        addColumnIfMissing("imported_archives", "content_hash", "TEXT");
        backfillArchiveHashes();
        jdbc.sql("CREATE UNIQUE INDEX IF NOT EXISTS idx_imported_archives_hash ON imported_archives(content_hash)").update();
    }

    /** Archives imported before content hashing existed get their hash from the stored Markdown. */
    void backfillArchiveHashes() {
        var rows = jdbc.sql("SELECT session_id, raw_markdown FROM imported_archives WHERE content_hash IS NULL")
                .query((rs, i) -> new Object[]{rs.getLong("session_id"), rs.getString("raw_markdown")}).list();
        for (Object[] row : rows) {
            String hash = com.mentalcare.importing.ArchiveImportService.sha256(
                    ((String) row[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // a pre-existing duplicate keeps NULL on the later row so the unique index can still be created
            boolean taken = jdbc.sql("SELECT COUNT(*) FROM imported_archives WHERE content_hash = :h").param("h", hash)
                    .query(Long.class).single() > 0;
            if (!taken) {
                jdbc.sql("UPDATE imported_archives SET content_hash = :h WHERE session_id = :id")
                        .param("h", hash).param("id", row[0]).update();
            }
            log.info("schema migration: hashed imported archive session={} duplicate={}", row[0], taken);
        }
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
