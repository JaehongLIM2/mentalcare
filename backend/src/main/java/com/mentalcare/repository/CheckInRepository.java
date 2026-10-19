package com.mentalcare.repository;

import com.mentalcare.domain.WeeklyCheckIn;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.mentalcare.repository.JdbcSupport.*;

@Repository
public class CheckInRepository {

    /** A check-in joined with the date of its session, used for trends. */
    public record DatedCheckIn(Instant sessionStartedAt, WeeklyCheckIn checkIn) {}

    private static final RowMapper<WeeklyCheckIn> MAPPER = (rs, i) -> new WeeklyCheckIn(
            rs.getLong("id"),
            rs.getLong("session_id"),
            intOrNull(rs, "mood"),
            intOrNull(rs, "stress"),
            intOrNull(rs, "anxiety"),
            intOrNull(rs, "energy"),
            intOrNull(rs, "sleep"),
            rs.getString("note"),
            instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public CheckInRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public WeeklyCheckIn insert(long sessionId, Integer mood, Integer stress, Integer anxiety,
                                Integer energy, Integer sleep, String note) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO weekly_checkins (session_id, mood, stress, anxiety, energy, sleep, note, created_at)
                VALUES (:sid, :mood, :stress, :anxiety, :energy, :sleep, :note, :now)
                """)
                .param("sid", sessionId).param("mood", mood).param("stress", stress).param("anxiety", anxiety)
                .param("energy", energy).param("sleep", sleep).param("note", note).param("now", now())
                .update(keys);
        return jdbc.sql("SELECT * FROM weekly_checkins WHERE id = :id").param("id", key(keys)).query(MAPPER).single();
    }

    public Optional<WeeklyCheckIn> findBySession(long sessionId) {
        return jdbc.sql("SELECT * FROM weekly_checkins WHERE session_id = :sid")
                .param("sid", sessionId).query(MAPPER).optional();
    }

    /** Most recent check-ins first. */
    public List<DatedCheckIn> findRecent(int limit) {
        return jdbc.sql("""
                SELECT c.*, s.started_at AS session_started_at
                FROM weekly_checkins c JOIN sessions s ON s.id = c.session_id
                ORDER BY s.started_at DESC LIMIT :limit
                """)
                .param("limit", limit)
                .query((rs, i) -> new DatedCheckIn(instant(rs, "session_started_at"), MAPPER.mapRow(rs, i)))
                .list();
    }
}
