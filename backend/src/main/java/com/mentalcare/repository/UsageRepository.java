package com.mentalcare.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

import static com.mentalcare.repository.JdbcSupport.now;

@Repository
public class UsageRepository {

    /** Aggregated usage per model and request type. */
    public record UsageRow(String model, String requestType, long requests, long inputTokens, long outputTokens) {}

    private final JdbcClient jdbc;

    public UsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String requestType, String model, long inputTokens, long outputTokens, Long sessionId) {
        jdbc.sql("""
                INSERT INTO api_usage (request_type, model, input_tokens, output_tokens, session_id, created_at)
                VALUES (:type, :model, :in, :out, :sid, :now)
                """)
                .param("type", requestType).param("model", model).param("in", inputTokens)
                .param("out", outputTokens).param("sid", sessionId).param("now", now())
                .update();
    }

    public List<UsageRow> summarizeSince(Instant since) {
        return jdbc.sql("""
                SELECT model, request_type, COUNT(*) AS requests,
                       SUM(input_tokens) AS input_tokens, SUM(output_tokens) AS output_tokens
                FROM api_usage WHERE created_at >= :since
                GROUP BY model, request_type ORDER BY model, request_type
                """)
                .param("since", since.toString())
                .query((rs, i) -> new UsageRow(rs.getString("model"), rs.getString("request_type"),
                        rs.getLong("requests"), rs.getLong("input_tokens"), rs.getLong("output_tokens")))
                .list();
    }
}
