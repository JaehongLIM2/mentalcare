package com.mentalcare.domain;

import com.mentalcare.domain.Enums.SessionStatus;

import java.time.Instant;

public record Session(
        long id,
        SessionStatus status,
        String title,
        Instant startedAt,
        Instant endedAt,
        Long promptVersionId,
        boolean crisisFlag,
        Instant createdAt,
        Instant updatedAt
) {
    public boolean isActive() {
        return status == SessionStatus.ACTIVE;
    }
}
