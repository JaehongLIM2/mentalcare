package com.mentalcare.ai.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalcare.ai.LlmException;
import com.mentalcare.ai.LlmException.Kind;

/**
 * Maps OpenAI error responses (HTTP status + {@code error.code}) to {@link LlmException} kinds,
 * including the ChatGPT plan-usage codes documented for Sign in with ChatGPT.
 * Plan-usage failures are surfaced as application errors and are never rerouted to another billing path.
 */
final class OpenAiErrors {
    private OpenAiErrors() {}

    record ParsedError(String code, String message, String param) {}

    static ParsedError parse(ObjectMapper mapper, String body) {
        if (body == null || body.isBlank()) return new ParsedError(null, "", null);
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode error = node.path("error");
            if (error.isObject()) {
                return new ParsedError(text(error, "code"), text(error, "message"), text(error, "param"));
            }
            // Direct-admission failures may use {"detail": "..."}; treat as diagnostic text only.
            if (node.has("detail")) {
                return new ParsedError(null, node.path("detail").asText(""), null);
            }
        } catch (Exception ignored) {
            // not JSON
        }
        return new ParsedError(null, body.substring(0, Math.min(body.length(), 200)), null);
    }

    static Kind classify(int status, String code) {
        if (code != null) {
            switch (code) {
                case "subscription_sharing_usage_limit_exceeded" -> { return Kind.PLAN_LIMIT; }
                case "subscription_sharing_user_not_eligible", "subscription_sharing_v2_user_not_eligible",
                     "subscription_sharing_v2_client_not_enabled" -> { return Kind.PLAN_NOT_ELIGIBLE; }
                case "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable",
                     "subscription_sharing_v2_user_unavailable" -> { return Kind.PLAN_UNAVAILABLE; }
                case "subscription_sharing_unsupported_capability" -> { return Kind.UNSUPPORTED_CAPABILITY; }
                // no_matching_rule: the plan-usage gateway has no admission rule for this route (seen on
                // /v1/moderations with a ChatGPT token). The token itself is fine: not an auth failure.
                case "subscription_sharing_route_not_supported", "subscription_sharing_v2_route_not_supported",
                     "no_matching_rule" -> {
                    return Kind.ROUTE_NOT_SUPPORTED;
                }
                case "subscription_sharing_invalid_user", "subscription_sharing_v2_invalid_user",
                     "invalid_token", "invalid_api_key", "chatpass_v2_scope_not_authorized",
                     "chatpass_v2_invalid_authorization_context" -> { return Kind.AUTH_REQUIRED; }
                case "model_not_found" -> { return Kind.BAD_REQUEST; }
                default -> { /* fall through to status mapping */ }
            }
        }
        return switch (status) {
            case 401 -> Kind.AUTH_REQUIRED;
            case 403 -> Kind.PLAN_NOT_ELIGIBLE;
            case 429 -> Kind.RATE_LIMIT;
            case 400, 404, 422 -> Kind.BAD_REQUEST;
            case 408 -> Kind.TIMEOUT;
            default -> status >= 500 ? Kind.SERVER : Kind.UNKNOWN;
        };
    }

    static LlmException exception(int status, ParsedError error) {
        Kind kind = classify(status, error.code());
        String msg = "OpenAI HTTP " + status + (error.code() == null ? "" : " code=" + error.code())
                + (error.param() == null ? "" : " param=" + error.param())
                + (error.message() == null || error.message().isBlank() ? "" : ": " + error.message());
        return new LlmException(kind, msg, error.code(), null);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : null;
    }
}
