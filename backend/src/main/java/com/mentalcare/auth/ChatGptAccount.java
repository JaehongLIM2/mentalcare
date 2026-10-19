package com.mentalcare.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * The single stored ChatGPT account registration + credentials (encrypted at rest by the store).
 * {@code clientId} is the OpenAI-issued id (never {@code dynamic_agent_client}); it survives sign-out.
 *
 * @param status CONNECTED | DISCONNECTED (signed out, registration kept) | REAUTH_REQUIRED (refresh failed terminally)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatGptAccount(
        int version,
        String clientId,
        String subject,
        String email,
        String name,
        List<String> scopes,
        String status,
        String accessToken,
        Instant expiresAt,
        String refreshToken,
        String idToken,
        Instant savedAt
) {
    public static final String CONNECTED = "CONNECTED";
    public static final String DISCONNECTED = "DISCONNECTED";
    public static final String REAUTH_REQUIRED = "REAUTH_REQUIRED";
    public static final String PLAN_SCOPE = "chatgpt.tokens.use.direct";

    public boolean connected() {
        return CONNECTED.equals(status) && accessToken != null && refreshToken != null;
    }

    public boolean planUsageGranted() {
        return scopes != null && scopes.contains(PLAN_SCOPE);
    }

    public ChatGptAccount withTokens(String access, Instant expires, String refresh, String id, List<String> newScopes) {
        return new ChatGptAccount(version, clientId, subject, email, name,
                newScopes == null ? scopes : newScopes, CONNECTED, access, expires,
                refresh == null ? refreshToken : refresh, id == null ? idToken : id, Instant.now());
    }

    /** Credentials removed, registration (client id, identity label) kept. */
    public ChatGptAccount withoutTokens(String newStatus) {
        return new ChatGptAccount(version, clientId, subject, email, name, scopes, newStatus,
                null, null, null, null, Instant.now());
    }
}
