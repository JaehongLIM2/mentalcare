package com.mentalcare.ai;

import com.mentalcare.config.OpenAiProperties.AuthMode;

/**
 * Supplies the bearer credential for OpenAI requests. There is exactly one active provider,
 * chosen by {@code openai.auth-mode}; providers never fall back to one another.
 */
public interface BearerTokenProvider {

    AuthMode mode();

    /** True when inference requests can be made right now (connected, authorized, not expired beyond refresh). */
    boolean available();

    /** Short reason when {@link #available()} is false (user-facing, Korean), otherwise null. */
    String unavailableReason();

    /** Returns the bearer token, refreshing it if necessary. Never log the result. */
    String bearer() throws LlmException;

    /** Called when the API rejected the credential (HTTP 401) so the provider can mark itself invalid. */
    default void onUnauthorized() {}

    /** True when requests are billed to the user's ChatGPT plan rather than an API key. */
    default boolean usesChatGptPlan() {
        return mode() == AuthMode.CHATGPT;
    }
}
