package com.mentalcare.domain;

import java.time.Instant;

/** Unsent composer text of an active session. Counseling data: never logged. */
public record Draft(long sessionId, String content, Instant updatedAt) {}
