package com.mentalcare.repository;

import com.mentalcare.domain.Enums.RiskLevel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import static com.mentalcare.repository.JdbcSupport.now;

@Repository
public class SafetyEventRepository {

    private final JdbcClient jdbc;

    public SafetyEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stored value for a message whose assessment could not be done (distinct from every RiskLevel). */
    public static final String UNAVAILABLE = "UNAVAILABLE";

    public void insert(long sessionId, Long messageId, boolean moderationFlagged, String scoresJson,
                       boolean keywordSignal, RiskLevel riskLevel, String reason) {
        insert(sessionId, messageId, moderationFlagged, scoresJson, keywordSignal, riskLevel.name(), reason);
    }

    public void insert(long sessionId, Long messageId, boolean moderationFlagged, String scoresJson,
                       boolean keywordSignal, String riskLevel, String reason) {
        jdbc.sql("""
                INSERT INTO safety_events (session_id, message_id, moderation_flagged, moderation_scores_json,
                                           keyword_signal, risk_level, assessment_reason, created_at)
                VALUES (:sid, :mid, :flagged, :scores, :kw, :risk, :reason, :now)
                """)
                .param("sid", sessionId).param("mid", messageId).param("flagged", moderationFlagged ? 1 : 0)
                .param("scores", scoresJson).param("kw", keywordSignal ? 1 : 0).param("risk", riskLevel)
                .param("reason", reason).param("now", now())
                .update();
    }
}
