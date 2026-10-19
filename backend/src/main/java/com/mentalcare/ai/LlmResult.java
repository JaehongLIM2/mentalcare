package com.mentalcare.ai;

/** A parsed structured result together with the usage of the request that produced it. */
public record LlmResult<T>(T value, LlmUsage usage) {}
