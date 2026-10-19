package com.mentalcare.ai;

/** Token usage of one AI request. */
public record LlmUsage(String model, long inputTokens, long outputTokens) {
    public static LlmUsage none(String model) {
        return new LlmUsage(model, 0, 0);
    }
}
