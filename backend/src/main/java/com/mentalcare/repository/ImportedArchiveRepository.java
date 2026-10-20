package com.mentalcare.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static com.mentalcare.repository.JdbcSupport.now;

/** Raw Markdown of archives imported from another assistant, keyed by the session that represents them. */
@Repository
public class ImportedArchiveRepository {

    private final JdbcClient jdbc;

    public ImportedArchiveRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores the archive unless one with the same content hash is already stored (the unique index on
     * content_hash decides, so two concurrent imports of one file cannot both get in).
     *
     * @return false when the same file was already imported; nothing is written then
     */
    public boolean insertIfNew(long sessionId, String sourceName, String rawMarkdown, String contentHash) {
        return jdbc.sql("""
                        INSERT INTO imported_archives (session_id, source_name, raw_markdown, content_hash, imported_at)
                        VALUES (:sid, :name, :md, :hash, :now)
                        ON CONFLICT (content_hash) DO NOTHING""")
                .param("sid", sessionId).param("name", sourceName).param("md", rawMarkdown).param("hash", contentHash).param("now", now())
                .update() == 1;
    }

    /** Session that already holds an archive with this content hash, if any. */
    public Optional<Long> findSessionByHash(String contentHash) {
        return jdbc.sql("SELECT session_id FROM imported_archives WHERE content_hash = :hash LIMIT 1")
                .param("hash", contentHash).query(Long.class).optional();
    }

    public Optional<String> rawMarkdown(long sessionId) {
        return jdbc.sql("SELECT raw_markdown FROM imported_archives WHERE session_id = :sid")
                .param("sid", sessionId).query(String.class).optional();
    }

    public Optional<String> sourceName(long sessionId) {
        return jdbc.sql("SELECT source_name FROM imported_archives WHERE session_id = :sid")
                .param("sid", sessionId).query(String.class).optional();
    }
}
