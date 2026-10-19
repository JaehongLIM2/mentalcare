package com.mentalcare.domain;

import com.mentalcare.ai.model.SessionSummaryOutput;

import java.time.Instant;

/** Stored structured summary of a completed session. {@code output} is the parsed summary_json. */
public record SessionSummary(
        long id,
        long sessionId,
        SessionSummaryOutput output,
        String model,
        Long promptVersionId,
        String markdownPath,
        Instant createdAt
) {}
