package com.mentalcare.repository;

import com.mentalcare.domain.Draft;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static com.mentalcare.repository.JdbcSupport.instant;
import static com.mentalcare.repository.JdbcSupport.now;

/** One draft per session (the text in the composer that has not been sent yet). */
@Repository
public class DraftRepository {

    private final JdbcClient jdbc;

    public DraftRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Draft> find(long sessionId) {
        return jdbc.sql("SELECT session_id, content, updated_at FROM drafts WHERE session_id = :sid")
                .param("sid", sessionId)
                .query((rs, i) -> new Draft(rs.getLong("session_id"), rs.getString("content"), instant(rs, "updated_at")))
                .optional();
    }

    /**
     * Saves only while the session is ACTIVE, decided in the same statement as the write: a completion or
     * deletion committed just before cannot be followed by a stray draft. Returns 1 when saved.
     */
    public int saveIfActive(long sessionId, String content) {
        return jdbc.sql("""
                INSERT INTO drafts (session_id, content, updated_at)
                SELECT :sid, :content, :now WHERE EXISTS (SELECT 1 FROM sessions WHERE id = :sid AND status = 'ACTIVE')
                ON CONFLICT(session_id) DO UPDATE SET content = excluded.content, updated_at = excluded.updated_at
                """)
                .param("sid", sessionId).param("content", content).param("now", now()).update();
    }

    public void save(long sessionId, String content) {
        jdbc.sql("""
                INSERT INTO drafts (session_id, content, updated_at) VALUES (:sid, :content, :now)
                ON CONFLICT(session_id) DO UPDATE SET content = excluded.content, updated_at = excluded.updated_at
                """)
                .param("sid", sessionId).param("content", content).param("now", now()).update();
    }

    public void delete(long sessionId) {
        jdbc.sql("DELETE FROM drafts WHERE session_id = :sid").param("sid", sessionId).update();
    }
}
