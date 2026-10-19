package com.mentalcare.domain;

import java.time.Instant;

public record ApiUsage(
        long id,
        String requestType,
        String model,
        long inputTokens,
        long outputTokens,
        Long sessionId,
        Instant createdAt
) {}
