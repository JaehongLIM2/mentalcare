package com.mentalcare.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalcare.ai.model.SessionSummaryOutput;
import com.mentalcare.domain.SessionSummary;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.mentalcare.repository.JdbcSupport.*;

@Repository
public class SummaryRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<SessionSummary> rowMapper;

    public SummaryRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rowMapper = (rs, i) -> new SessionSummary(
                rs.getLong("id"),
                rs.getLong("session_id"),
                parse(rs.getString("summary_json")),
                rs.getString("model"),
                longOrNull(rs, "prompt_version_id"),
                rs.getString("markdown_path"),
                instant(rs, "created_at"));
    }

    public SessionSummary insert(long sessionId, SessionSummaryOutput output, String model, Long promptVersionId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO session_summaries (session_id, summary_json, model, prompt_version_id, created_at)
                VALUES (:sid, :json, :model, :pv, :now)
                """)
                .param("sid", sessionId).param("json", toJson(output)).param("model", model)
                .param("pv", promptVersionId).param("now", now())
                .update(keys);
        return jdbc.sql("SELECT * FROM session_summaries WHERE id = :id").param("id", key(keys)).query(rowMapper).single();
    }

    public Optional<SessionSummary> findBySession(long sessionId) {
        return jdbc.sql("SELECT * FROM session_summaries WHERE session_id = :sid")
                .param("sid", sessionId).query(rowMapper).optional();
    }

    public Map<Long, SessionSummary> findBySessions(List<Long> sessionIds) {
        if (sessionIds.isEmpty()) return Map.of();
        return jdbc.sql("SELECT * FROM session_summaries WHERE session_id IN (:ids)")
                .param("ids", sessionIds).query(rowMapper).list().stream()
                .collect(Collectors.toMap(SessionSummary::sessionId, Function.identity()));
    }

    public void updateMarkdownPath(long sessionId, String path) {
        jdbc.sql("UPDATE session_summaries SET markdown_path = :path WHERE session_id = :sid")
                .param("path", path).param("sid", sessionId).update();
    }

    private String toJson(SessionSummaryOutput output) {
        try {
            return mapper.writeValueAsString(output);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize summary", e);
        }
    }

    private SessionSummaryOutput parse(String json) {
        try {
            return mapper.readValue(json, SessionSummaryOutput.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt summary_json", e);
        }
    }
}
