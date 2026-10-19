package com.mentalcare.repository;

import com.mentalcare.domain.Enums.MemoryKind;
import com.mentalcare.domain.Enums.MemoryStatus;
import com.mentalcare.domain.Memory;
import com.mentalcare.domain.MemoryObservation;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.mentalcare.repository.JdbcSupport.*;

@Repository
public class MemoryRepository {

    private static final RowMapper<Memory> MAPPER = (rs, i) -> new Memory(
            rs.getLong("id"),
            rs.getString("content"),
            MemoryKind.valueOf(rs.getString("kind")),
            rs.getString("category"),
            MemoryStatus.valueOf(rs.getString("status")),
            longOrNull(rs, "first_observed_session_id"),
            longOrNull(rs, "last_observed_session_id"),
            rs.getInt("observation_count"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private static final RowMapper<MemoryObservation> OBS_MAPPER = (rs, i) -> new MemoryObservation(
            rs.getLong("memory_id"), rs.getLong("session_id"), rs.getString("note"), instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public MemoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates a memory first observed in {@code sessionId} (observation_count = 1, observation row included). */
    public Memory insert(String content, MemoryKind kind, String category, MemoryStatus status, long sessionId, String note) {
        String now = now();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO memories (content, kind, category, status, first_observed_session_id,
                                      last_observed_session_id, observation_count, created_at, updated_at)
                VALUES (:content, :kind, :category, :status, :sid, :sid, 1, :now, :now)
                """)
                .param("content", content).param("kind", kind.name()).param("category", category)
                .param("status", status.name()).param("sid", sessionId).param("now", now)
                .update(keys);
        long id = key(keys);
        jdbc.sql("INSERT OR IGNORE INTO memory_observations (memory_id, session_id, note, created_at) VALUES (:mid, :sid, :note, :now)")
                .param("mid", id).param("sid", sessionId).param("note", note).param("now", now).update();
        return findById(id).orElseThrow();
    }

    public Optional<Memory> findById(long id) {
        return jdbc.sql("SELECT * FROM memories WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public List<Memory> findByStatuses(List<MemoryStatus> statuses) {
        if (statuses.isEmpty()) return List.of();
        return jdbc.sql("SELECT * FROM memories WHERE status IN (:statuses) ORDER BY updated_at DESC")
                .param("statuses", statuses.stream().map(Enum::name).toList())
                .query(MAPPER).list();
    }

    public List<Memory> findAll() {
        return jdbc.sql("SELECT * FROM memories ORDER BY updated_at DESC").query(MAPPER).list();
    }

    public void update(long id, String content, String category, MemoryStatus status) {
        jdbc.sql("""
                UPDATE memories SET content = :content, category = :category, status = :status, updated_at = :now
                WHERE id = :id
                """)
                .param("content", content).param("category", category).param("status", status.name())
                .param("now", now()).param("id", id).update();
    }

    public void updateStatus(long id, MemoryStatus status) {
        jdbc.sql("UPDATE memories SET status = :status, updated_at = :now WHERE id = :id")
                .param("status", status.name()).param("now", now()).param("id", id).update();
    }

    /** Records that {@code sessionId} observed the memory. Returns true if it was a new observation. */
    public boolean addObservation(long memoryId, long sessionId, String note) {
        int inserted = jdbc.sql("""
                INSERT OR IGNORE INTO memory_observations (memory_id, session_id, note, created_at)
                VALUES (:mid, :sid, :note, :now)
                """)
                .param("mid", memoryId).param("sid", sessionId).param("note", note).param("now", now())
                .update();
        if (inserted > 0) {
            jdbc.sql("""
                    UPDATE memories SET observation_count = observation_count + 1,
                        last_observed_session_id = :sid, updated_at = :now
                    WHERE id = :mid
                    """)
                    .param("sid", sessionId).param("now", now()).param("mid", memoryId).update();
        }
        return inserted > 0;
    }

    public List<MemoryObservation> observationsOf(long memoryId) {
        return jdbc.sql("SELECT * FROM memory_observations WHERE memory_id = :mid ORDER BY created_at")
                .param("mid", memoryId).query(OBS_MAPPER).list();
    }

    public List<MemoryObservation> observationsOf(List<Long> memoryIds) {
        if (memoryIds.isEmpty()) return List.of();
        return jdbc.sql("SELECT * FROM memory_observations WHERE memory_id IN (:ids) ORDER BY created_at")
                .param("ids", memoryIds).query(OBS_MAPPER).list();
    }

    public long countByStatus(MemoryStatus status) {
        return jdbc.sql("SELECT COUNT(*) FROM memories WHERE status = :s")
                .param("s", status.name()).query(Long.class).single();
    }
}
