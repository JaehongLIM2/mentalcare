package com.mentalcare.ai.openai;

import com.mentalcare.ai.LlmException;

import java.util.function.Consumer;

/**
 * Thin HTTP boundary so {@link OpenAiLlmClient} can be tested without the network.
 * Implementations must not log request or response bodies (they contain counseling text).
 */
public interface OpenAiTransport {

    /** POSTs JSON and returns the response body. Non-2xx responses become {@link LlmException}. */
    String post(String path, String jsonBody) throws LlmException;

    /** GETs JSON and returns the response body. Non-2xx responses become {@link LlmException}. */
    String get(String path) throws LlmException;

    /** POSTs JSON and feeds every raw response line (SSE) to {@code lineConsumer} as it arrives. */
    void postStream(String path, String jsonBody, Consumer<String> lineConsumer) throws LlmException;

    /** True when requests are authorized with the user's ChatGPT plan (affects allowed request fields). */
    boolean usesChatGptPlan();
}
