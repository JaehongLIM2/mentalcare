package com.mentalcare.ai.openai;

import com.mentalcare.ai.BearerTokenProvider;
import com.mentalcare.ai.LlmException;
import com.mentalcare.ai.LlmException.Kind;
import com.mentalcare.config.OpenAiProperties;
import com.mentalcare.config.OpenAiProperties.AuthMode;

/** Developer/testing credential: a static API key from the environment. Only active when {@code openai.auth-mode=api-key}. */
public class ApiKeyTokenProvider implements BearerTokenProvider {

    private final OpenAiProperties props;

    public ApiKeyTokenProvider(OpenAiProperties props) {
        this.props = props;
    }

    @Override
    public AuthMode mode() {
        return AuthMode.API_KEY;
    }

    @Override
    public boolean available() {
        return props.hasApiKey();
    }

    @Override
    public String unavailableReason() {
        return available() ? null : new LlmException(Kind.NOT_CONFIGURED, "").userMessage();
    }

    @Override
    public String bearer() {
        if (!props.hasApiKey()) {
            throw new LlmException(Kind.NOT_CONFIGURED, "OPENAI_API_KEY is not set");
        }
        return props.apiKey();
    }
}
