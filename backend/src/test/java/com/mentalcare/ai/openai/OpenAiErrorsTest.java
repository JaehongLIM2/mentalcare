package com.mentalcare.ai.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalcare.ai.LlmException;
import com.mentalcare.ai.LlmException.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiErrorsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void planUsageCodesMapToDedicatedKinds() {
        assertThat(OpenAiErrors.classify(429, "subscription_sharing_usage_limit_exceeded")).isEqualTo(Kind.PLAN_LIMIT);
        assertThat(OpenAiErrors.classify(403, "subscription_sharing_user_not_eligible")).isEqualTo(Kind.PLAN_NOT_ELIGIBLE);
        assertThat(OpenAiErrors.classify(503, "subscription_sharing_usage_unavailable")).isEqualTo(Kind.PLAN_UNAVAILABLE);
        assertThat(OpenAiErrors.classify(400, "subscription_sharing_unsupported_capability")).isEqualTo(Kind.UNSUPPORTED_CAPABILITY);
        assertThat(OpenAiErrors.classify(403, "subscription_sharing_route_not_supported")).isEqualTo(Kind.ROUTE_NOT_SUPPORTED);
        assertThat(OpenAiErrors.classify(401, "subscription_sharing_invalid_user")).isEqualTo(Kind.AUTH_REQUIRED);
        assertThat(OpenAiErrors.classify(403, "chatpass_v2_scope_not_authorized")).isEqualTo(Kind.AUTH_REQUIRED);
    }

    @Test
    void statusFallbacks() {
        assertThat(OpenAiErrors.classify(401, null)).isEqualTo(Kind.AUTH_REQUIRED);
        assertThat(OpenAiErrors.classify(401, "invalid_token")).isEqualTo(Kind.AUTH_REQUIRED);
        // the plan-usage gateway's answer for a route it does not admit (observed on /v1/moderations)
        assertThat(OpenAiErrors.classify(401, "no_matching_rule")).isEqualTo(Kind.ROUTE_NOT_SUPPORTED);
        assertThat(OpenAiErrors.classify(429, null)).isEqualTo(Kind.RATE_LIMIT);
        assertThat(OpenAiErrors.classify(500, null)).isEqualTo(Kind.SERVER);
        assertThat(OpenAiErrors.classify(400, "invalid_request_error")).isEqualTo(Kind.BAD_REQUEST);
    }

    @Test
    void parsesStandardAndDetailBodies() {
        OpenAiErrors.ParsedError e = OpenAiErrors.parse(mapper,
                "{\"error\":{\"code\":\"subscription_sharing_unsupported_capability\",\"message\":\"m\",\"param\":\"max_output_tokens\"}}");
        assertThat(e.code()).isEqualTo("subscription_sharing_unsupported_capability");
        assertThat(e.param()).isEqualTo("max_output_tokens");
        LlmException ex = OpenAiErrors.exception(400, e);
        assertThat(ex.kind()).isEqualTo(Kind.UNSUPPORTED_CAPABILITY);
        assertThat(ex.providerCode()).isEqualTo("subscription_sharing_unsupported_capability");

        OpenAiErrors.ParsedError detail = OpenAiErrors.parse(mapper, "{\"detail\":\"not admitted\"}");
        assertThat(detail.code()).isNull();
        assertThat(OpenAiErrors.exception(401, detail).kind()).isEqualTo(Kind.AUTH_REQUIRED);
    }
}
