package com.mentalcare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI connection and model configuration (prefix {@code openai}).
 * Model names are never hard-coded elsewhere; change them here or via environment variables.
 *
 * <p>{@code authMode} selects how requests are authorized:
 * <ul>
 *   <li>{@code chatgpt} (default): Sign in with ChatGPT; inference uses the user's ChatGPT plan.</li>
 *   <li>{@code api-key}: developer mode with {@code OPENAI_API_KEY}. Never used as an automatic fallback.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
        String authMode,
        String apiKey,
        String baseUrl,
        String counselingModel,
        String utilityModel,
        String moderationModel,
        String counselingReasoningEffort,
        String utilityReasoningEffort,
        int connectTimeoutSeconds,
        int requestTimeoutSeconds,
        int counselingMaxOutputTokens,
        int utilityMaxOutputTokens,
        Siwc siwc
) {
    public enum AuthMode { CHATGPT, API_KEY }

    /** Sign in with ChatGPT settings. Endpoints are discovered from the issuer, never hard-coded. */
    public record Siwc(String issuer, String resource, String appName, String scopes,
                       String callbackPath, String publicBaseUrl) {
        public String issuerOrDefault() { return orDefault(issuer, "https://auth.openai.com"); }
        public String resourceOrDefault() { return orDefault(resource, "https://api.openai.com/v1"); }
        public String appNameOrDefault() { return orDefault(appName, "mentalcare"); }
        public String scopesOrDefault() {
            return orDefault(scopes, "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct");
        }
        public String callbackPathOrDefault() { return orDefault(callbackPath, "/api/auth/chatgpt/callback"); }
        public String publicBaseUrlOrDefault() { return orDefault(publicBaseUrl, "http://127.0.0.1:8080"); }

        private static String orDefault(String v, String d) { return v == null || v.isBlank() ? d : v; }
    }

    public AuthMode mode() {
        return authMode != null && authMode.trim().equalsIgnoreCase("api-key") ? AuthMode.API_KEY : AuthMode.CHATGPT;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Siwc siwcOrDefault() {
        return siwc == null ? new Siwc(null, null, null, null, null, null) : siwc;
    }
}
