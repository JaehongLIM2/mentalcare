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
        Instant createdAt
) {}
