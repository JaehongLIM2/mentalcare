package com.mentalcare.domain;

import java.time.Instant;

/** One session in which a memory was observed (the "related sessions" of a memory). */
public record MemoryObservation(long memoryId, long sessionId, String note, Instant createdAt) {}
