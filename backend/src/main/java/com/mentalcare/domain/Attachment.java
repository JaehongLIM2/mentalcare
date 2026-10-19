package com.mentalcare.domain;

import java.time.Instant;

/** A text file (.txt / .md) the user attached to one of their messages. Counseling data: never logged. */
public record Attachment(long id, long messageId, long sessionId, String fileName, String content, int charCount,
                         Instant createdAt) {}
