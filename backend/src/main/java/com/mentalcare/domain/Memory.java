package com.mentalcare.domain;

import com.mentalcare.domain.Enums.MemoryKind;
import com.mentalcare.domain.Enums.MemoryStatus;

import java.time.Instant;

public record Memory(
        long id,
        String content,
        MemoryKind kind,
        String category,
        MemoryStatus status,
        Long firstObservedSessionId,
        Long lastObservedSessionId,
        int observationCount,
        Instant createdAt,
        Instant updatedAt,
        String source
) {
    public static final String SOURCE_SUMMARY = "SUMMARY";
    public static final String SOURCE_MANUAL = "MANUAL";

    public Memory(long id, String content, MemoryKind kind, String category, MemoryStatus status,
                  Long firstObservedSessionId, Long lastObservedSessionId, int observationCount,
                  Instant createdAt, Instant updatedAt) {
        this(id, content, kind, category, status, firstObservedSessionId, lastObservedSessionId, observationCount,
                createdAt, updatedAt, SOURCE_SUMMARY);
    }

    /** Written by the user on the memory screen (not extracted from a session). */
    public boolean manual() {
        return SOURCE_MANUAL.equals(source);
    }
}
