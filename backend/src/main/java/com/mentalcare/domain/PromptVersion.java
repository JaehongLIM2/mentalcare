package com.mentalcare.domain;

import java.time.Instant;

public record PromptVersion(long id, String name, String version, String contentHash, Instant loadedAt) {}
