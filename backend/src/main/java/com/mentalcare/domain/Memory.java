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
        Instant updatedAt
) {}
