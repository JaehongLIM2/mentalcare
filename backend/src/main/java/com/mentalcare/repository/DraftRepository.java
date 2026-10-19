package com.mentalcare.repository;

import com.mentalcare.domain.Draft;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static com.mentalcare.repository.JdbcSupport.instant;
import static com.mentalcare.repository.JdbcSupport.now;

/**
 * One draft per session (the text in the composer that has not been sent yet).
 *
 * <p>Every write carries a revision ({@code rev}, microseconds since the epoch on this PC, strictly increasing
 * per browser tab). A write only replaces the stored draft when its revision is newer, so autosave requests
 * that arrive out of order (a slow request overtaken by a later one, or by the keepalive sent when the tab
 * closes) never bring back older text. An empty content is a tombstone: it hides the draft but keeps its
 * revision, so a stale request cannot resurrect text that was already sent.
 */
@Repository
public class DraftRepository {

    private final JdbcClient jdbc;

    public DraftRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The visible draft (tombstones excluded). */
    public Optional<Draft> find(long sessionId) {
        return jdbc.sql("SELECT session_id, content, updated_at FROM drafts WHERE session_id = :sid AND content <> ''")
                .param("sid", sessionId)
                .query((rs, i) -> new Draft(rs.getLong("session_id"), rs.getString("content"), instant(rs, "updated_at")))
                .optional();
    }

    public Optional<Long> rev(long sessionId) {
        return jdbc.sql("SELECT rev FROM drafts WHERE session_id = :sid").param("sid", sessionId).query(Long.class).optional();
    }

    /**
     * Saves only while the session is ACTIVE and only when {@code rev} is newer than the stored one, decided in
     * the same statement as the write. Returns 1 when the draft was written, 0 when the session is not active
     * or the request is stale.
     */
    public int saveIfActive(long sessionId, String content, long rev) {
        return jdbc.sql("""
                INSERT INTO drafts (session_id, content, rev, updated_at)
                SELECT :sid, :content, :rev, :now WHERE EXISTS (SELECT 1 FROM sessions WHERE id = :sid AND status = 'ACTIVE')
                ON CONFLICT(session_id) DO UPDATE SET content = excluded.content, rev = excluded.rev, updated_at = excluded.updated_at
                WHERE excluded.rev > drafts.rev
                """)
                .param("sid", sessionId).param("content", content).param("rev", rev).param("now", now()).update();
    }

    /** The draft was sent as a message: leave a tombstone at {@code rev} so older autosaves are ignored. */
    public void clearUpTo(long sessionId, long rev) {
        jdbc.sql("""
                INSERT INTO drafts (session_id, content, rev, updated_at) VALUES (:sid, '', :rev, :now)
                ON CONFLICT(session_id) DO UPDATE SET content = '', rev = MAX(drafts.rev, excluded.rev), updated_at = excluded.updated_at
                """)
                .param("sid", sessionId).param("rev", rev).param("now", now()).update();
    }

    /** Session completed or deleted: no more drafts can be written (saveIfActive requires ACTIVE). */
    public void delete(long sessionId) {
        jdbc.sql("DELETE FROM drafts WHERE session_id = :sid").param("sid", sessionId).update();
    }
}
