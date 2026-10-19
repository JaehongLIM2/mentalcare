package com.mentalcare.ai;

/** A model the current credential can use. {@code slug} is what goes into the request's {@code model} field. */
public record ModelInfo(String slug, String displayName) {}
