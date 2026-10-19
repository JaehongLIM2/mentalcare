package com.mentalcare.repository;

import com.mentalcare.domain.Attachment;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mentalcare.repository.JdbcSupport.instant;
import static com.mentalcare.repository.JdbcSupport.now;

@Repository
public class AttachmentRepository {

    private static final RowMapper<Attachment> MAPPER = (rs, i) -> new Attachment(
            rs.getLong("id"), rs.getLong("message_id"), rs.getLong("session_id"), rs.getString("file_name"),
            rs.getString("content"), rs.getInt("char_count"), instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public AttachmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long messageId, long sessionId, String fileName, String content) {
        jdbc.sql("""
                INSERT INTO message_attachments (message_id, session_id, file_name, content, char_count, created_at)
                VALUES (:mid, :sid, :name, :content, :chars, :now)
                """)
                .param("mid", messageId).param("sid", sessionId).param("name", fileName).param("content", content)
                .param("chars", content.length()).param("now", now()).update();
    }

    /** Attachments of a session grouped by message id, in upload order. */
    public Map<Long, List<Attachment>> bySession(long sessionId) {
        Map<Long, List<Attachment>> out = new LinkedHashMap<>();
        jdbc.sql("SELECT * FROM message_attachments WHERE session_id = :sid ORDER BY id")
                .param("sid", sessionId).query(MAPPER).list()
                .forEach(a -> out.computeIfAbsent(a.messageId(), k -> new java.util.ArrayList<>()).add(a));
        return out;
    }
}
