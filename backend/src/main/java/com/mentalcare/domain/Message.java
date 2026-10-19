package com.mentalcare.domain;

import com.mentalcare.domain.Enums.MessageRole;
import com.mentalcare.domain.Enums.MessageStatus;
import com.mentalcare.domain.Enums.RiskLevel;

import java.time.Instant;

public record Message(
        long id,
        long sessionId,
        MessageRole role,
        String content,
        MessageStatus status,
        RiskLevel riskLevel,
        Instant createdAt,
        /** true when the safety assessment for this (user) message could not be done */
        boolean safetyUnavailable
) {
    public Message(long id, long sessionId, MessageRole role, String content, MessageStatus status,
                   RiskLevel riskLevel, Instant createdAt) {
        this(id, sessionId, role, content, status, riskLevel, createdAt, false);
    }
}
