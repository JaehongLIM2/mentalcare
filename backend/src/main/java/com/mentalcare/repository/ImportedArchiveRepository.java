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

    public void insert(long sessionId, String sourceName, String rawMarkdown) {
        jdbc.sql("INSERT INTO imported_archives (session_id, source_name, raw_markdown, imported_at) VALUES (:sid, :name, :md, :now)")
                .param("sid", sessionId).param("name", sourceName).param("md", rawMarkdown).param("now", now())
                .update();
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
