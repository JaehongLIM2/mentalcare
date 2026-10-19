package com.mentalcare.domain;

import com.mentalcare.domain.Enums.RiskLevel;

import java.time.Instant;

/** Safety decision record. Holds scores and verdicts only, never the message text. */
public record SafetyEvent(
        long id,
        long sessionId,
        Long messageId,
        boolean moderationFlagged,
        String moderationScoresJson,
        boolean keywordSignal,
        RiskLevel riskLevel,
        String assessmentReason,
        Instant createdAt
) {}
