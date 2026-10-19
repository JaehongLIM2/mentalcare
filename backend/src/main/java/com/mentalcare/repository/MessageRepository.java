package com.mentalcare.repository;

import com.mentalcare.domain.Enums.MessageRole;
import com.mentalcare.domain.Enums.MessageStatus;
import com.mentalcare.domain.Enums.RiskLevel;
import com.mentalcare.domain.Message;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.mentalcare.repository.JdbcSupport.*;

@Repository
public class MessageRepository {

    private static final RowMapper<Message> MAPPER = (rs, i) -> new Message(
            rs.getLong("id"),
            rs.getLong("session_id"),
            MessageRole.valueOf(rs.getString("role")),
            rs.getString("content"),
            MessageStatus.valueOf(rs.getString("status")),
            rs.getString("risk_level") == null ? null : RiskLevel.valueOf(rs.getString("risk_level")),
            instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public MessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Message insert(long sessionId, MessageRole role, String content, MessageStatus status, RiskLevel risk) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO messages (session_id, role, content, status, risk_level, created_at)
                VALUES (:sid, :role, :content, :status, :risk, :now)
                """)
                .param("sid", sessionId).param("role", role.name()).param("content", content)
                .param("status", status.name()).param("risk", risk == null ? null : risk.name())
                .param("now", now())
                .update(keys);
        return findById(key(keys)).orElseThrow();
    }

    public Optional<Message> findById(long id) {
        return jdbc.sql("SELECT * FROM messages WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public List<Message> findBySession(long sessionId) {
        return jdbc.sql("SELECT * FROM messages WHERE session_id = :sid ORDER BY id")
                .param("sid", sessionId).query(MAPPER).list();
    }

    public long countBySession(long sessionId) {
        return jdbc.sql("SELECT COUNT(*) FROM messages WHERE session_id = :sid")
                .param("sid", sessionId).query(Long.class).single();
    }

    public void updateRisk(long id, RiskLevel risk) {
        jdbc.sql("UPDATE messages SET risk_level = :risk WHERE id = :id")
                .param("risk", risk.name()).param("id", id).update();
    }
}
