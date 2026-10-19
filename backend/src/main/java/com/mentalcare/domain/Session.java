package com.mentalcare.domain;

import com.mentalcare.domain.Enums.SessionKind;
import com.mentalcare.domain.Enums.SessionStatus;

import java.time.Instant;

public record Session(
        long id,
        SessionStatus status,
        SessionKind kind,
        String title,
        Instant startedAt,
        Instant endedAt,
        Long promptVersionId,
        boolean crisisFlag,
        Instant createdAt,
        Instant updatedAt
) {
    /** Convenience for ordinary counseling sessions (tests, callers that predate {@code kind}). */
    public Session(long id, SessionStatus status, String title, Instant startedAt, Instant endedAt,
                   Long promptVersionId, boolean crisisFlag, Instant createdAt, Instant updatedAt) {
        this(id, status, SessionKind.COUNSELING, title, startedAt, endedAt, promptVersionId, crisisFlag, createdAt, updatedAt);
    }

    public boolean isActive() {
        return status == SessionStatus.ACTIVE;
    }

    public boolean isImported() {
        return kind == SessionKind.IMPORTED;
    }
}
