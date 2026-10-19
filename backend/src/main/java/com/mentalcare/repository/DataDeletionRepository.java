package com.mentalcare.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.mentalcare.repository.JdbcSupport.now;

/**
 * Hard deletes of counseling data. Called only inside a transaction by DataDeletionService.
 * Settings, prompt versions and OAuth credentials are never touched here.
 */
@Repository
public class DataDeletionRepository {

    private final JdbcClient jdbc;

    public DataDeletionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Markdown paths recorded for the given sessions (null entries filtered out). */
    public List<String> markdownPathsOf(List<Long> sessionIds) {
        if (sessionIds.isEmpty()) return List.of();
        return jdbc.sql("SELECT markdown_path FROM session_summaries WHERE session_id IN (:ids) AND markdown_path IS NOT NULL")
                .param("ids", sessionIds).query(String.class).list();
    }

    public List<String> allMarkdownPaths() {
        return jdbc.sql("SELECT markdown_path FROM session_summaries WHERE markdown_path IS NOT NULL").query(String.class).list();
    }

    /** Deletes one session and everything that hangs off it; memories lose this session's observation. */
    public void deleteSession(long sessionId) {
        jdbc.sql("DELETE FROM session_tags WHERE session_id = :id").param("id", sessionId).update();
        jdbc.sql("DELETE FROM drafts WHERE session_id = :id").param("id", sessionId).update();
        jdbc.sql("DELETE FROM imported_archives WHERE session_id = :id").param("id", sessionId).update();
        jdbc.sql("DELETE FROM safety_events WHERE session_id = :id").param("id", sessionId).update();
        jdbc.sql("DELETE FROM session_summaries WHERE session_id = :id").param("id", sessionId).update();
        jdbc.sql("DELETE FROM weekly_checkins WHERE session_id = :id").param("id", sessionId).update();
        jdbc.sql("DELETE FROM messages WHERE session_id = :id").param("id", sessionId).update();
        List<Long> affected = jdbc.sql("SELECT DISTINCT memory_id FROM memory_observations WHERE session_id = :id")
                .param("id", sessionId).query(Long.class).list();
        jdbc.sql("DELETE FROM memory_observations WHERE session_id = :id").param("id", sessionId).update();
        // memories that were only ever observed in this session disappear with it
        jdbc.sql("DELETE FROM memories WHERE id NOT IN (SELECT DISTINCT memory_id FROM memory_observations)").update();
        // every memory the session touched: recount and re-point first/last observed session
        if (!affected.isEmpty()) {
            jdbc.sql("""
                    UPDATE memories SET
                      observation_count = (SELECT COUNT(*) FROM memory_observations o WHERE o.memory_id = memories.id),
                      first_observed_session_id = (SELECT MIN(session_id) FROM memory_observations o WHERE o.memory_id = memories.id),
                      last_observed_session_id = (SELECT MAX(session_id) FROM memory_observations o WHERE o.memory_id = memories.id),
                      updated_at = :now
                    WHERE id IN (:ids)
                    """).param("now", now()).param("ids", affected).update();
        }
        jdbc.sql("UPDATE api_usage SET session_id = NULL WHERE session_id = :id").param("id", sessionId).update();
        jdbc.sql("DELETE FROM tags WHERE id NOT IN (SELECT DISTINCT tag_id FROM session_tags)").update();
        jdbc.sql("DELETE FROM sessions WHERE id = :id").param("id", sessionId).update();
    }

    /** Deletes every counseling record. Keeps app_settings, prompt_versions. */
    public void deleteAllCounselingData() {
        for (String table : List.of("session_tags", "drafts", "imported_archives", "safety_events", "session_summaries", "weekly_checkins", "messages",
                "memory_observations", "memories", "tags", "api_usage", "sessions")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    public long countSessions() {
        return jdbc.sql("SELECT COUNT(*) FROM sessions").query(Long.class).single();
    }
}
