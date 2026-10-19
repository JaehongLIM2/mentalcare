package com.mentalcare.domain;

import java.time.Instant;

/** Weekly check-in. Every score is optional (1..10 or null when skipped). */
public record WeeklyCheckIn(
        long id,
        long sessionId,
        Integer mood,
        Integer stress,
        Integer anxiety,
        Integer energy,
        Integer sleep,
        String note,
        Instant createdAt
) {
    public boolean isEmpty() {
        return mood == null && stress == null && anxiety == null && energy == null && sleep == null
                && (note == null || note.isBlank());
    }
}
