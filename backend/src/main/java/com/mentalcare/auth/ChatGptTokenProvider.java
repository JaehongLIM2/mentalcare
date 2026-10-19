package com.mentalcare.auth;

import com.mentalcare.ai.BearerTokenProvider;
import com.mentalcare.ai.LlmException;
import com.mentalcare.ai.LlmException.Kind;
import com.mentalcare.config.OpenAiProperties.AuthMode;

/** Bearer tokens from the Sign in with ChatGPT session. Inference is blocked until plan usage is granted. */
public class ChatGptTokenProvider implements BearerTokenProvider {

    private final ChatGptAuthService auth;

    public ChatGptTokenProvider(ChatGptAuthService auth) {
        this.auth = auth;
    }

    @Override
    public AuthMode mode() {
        return AuthMode.CHATGPT;
    }

    @Override
    public boolean available() {
        ChatGptAuthService.Status s = auth.status();
        return s.connected() && s.planUsage() && !s.reauthRequired();
    }

    @Override
    public String unavailableReason() {
        ChatGptAuthService.Status s = auth.status();
        if (s.reauthRequired()) return new LlmException(Kind.AUTH_REQUIRED, "").userMessage();
        if (!s.connected()) return new LlmException(Kind.NOT_CONNECTED, "").userMessage();
        if (!s.planUsage()) return new LlmException(Kind.NO_PLAN_USAGE, "").userMessage();
        return null;
    }

    @Override
    public String bearer() {
        return auth.accessToken();
    }

    @Override
    public void onUnauthorized() {
        auth.onUnauthorized();
    }
}
