package com.mentalcare.repository;

import com.mentalcare.domain.Enums.SessionStatus;
import com.mentalcare.domain.Session;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.mentalcare.repository.JdbcSupport.*;

@Repository
public class SessionRepository {

    private static final RowMapper<Session> MAPPER = (rs, i) -> new Session(
            rs.getLong("id"),
            SessionStatus.valueOf(rs.getString("status")),
            rs.getString("title"),
            instant(rs, "started_at"),
            instant(rs, "ended_at"),
            longOrNull(rs, "prompt_version_id"),
            rs.getInt("crisis_flag") != 0,
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private final JdbcClient jdbc;

    public SessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Session create(Long promptVersionId) {
        String now = now();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO sessions (status, started_at, prompt_version_id, crisis_flag, created_at, updated_at)
                VALUES ('ACTIVE', :now, :pv, 0, :now, :now)
                """)
                .param("now", now)
                .param("pv", promptVersionId)
                .update(keys);
        return findById(key(keys)).orElseThrow();
    }

    public Optional<Session> findById(long id) {
        return jdbc.sql("SELECT * FROM sessions WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Optional<Session> findActive() {
        return jdbc.sql("SELECT * FROM sessions WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
                .query(MAPPER).optional();
    }

    public List<Session> findAll(int limit, int offset) {
        return jdbc.sql("SELECT * FROM sessions ORDER BY started_at DESC LIMIT :limit OFFSET :offset")
                .param("limit", limit).param("offset", offset).query(MAPPER).list();
    }

    public List<Session> findRecentCompleted(int limit) {
        return jdbc.sql("SELECT * FROM sessions WHERE status = 'COMPLETED' ORDER BY ended_at DESC LIMIT :limit")
                .param("limit", limit).query(MAPPER).list();
    }

    public List<Session> findByIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT * FROM sessions WHERE id IN (:ids)").param("ids", ids).query(MAPPER).list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM sessions").query(Long.class).single();
    }

    public long countCompleted() {
        return jdbc.sql("SELECT COUNT(*) FROM sessions WHERE status = 'COMPLETED'").query(Long.class).single();
    }

    public void markCompleted(long id, String title, Instant endedAt) {
        jdbc.sql("""
                UPDATE sessions SET status = 'COMPLETED', title = :title, ended_at = :ended, updated_at = :now
                WHERE id = :id
                """)
                .param("title", title).param("ended", text(endedAt)).param("now", now()).param("id", id)
                .update();
    }

    public void setCrisisFlag(long id, boolean flag) {
        jdbc.sql("UPDATE sessions SET crisis_flag = :flag, updated_at = :now WHERE id = :id")
                .param("flag", flag ? 1 : 0).param("now", now()).param("id", id).update();
    }

    public void touch(long id) {
        jdbc.sql("UPDATE sessions SET updated_at = :now WHERE id = :id").param("now", now()).param("id", id).update();
    }
}
