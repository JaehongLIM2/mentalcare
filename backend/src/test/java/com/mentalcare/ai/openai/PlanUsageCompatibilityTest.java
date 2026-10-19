package com.mentalcare.ai.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalcare.ai.LlmException;
import com.mentalcare.config.OpenAiProperties;
import com.mentalcare.context.ContextMessage;
import com.mentalcare.context.CounselingContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-checks every Responses API body the client builds against OpenAI's
 * "Sign in with ChatGPT → Preview limitations" page (developers.openai.com/siwc/token-sharing-open-source/preview-limitations,
 * read 2026-10-19):
 * <ul>
 *   <li>Set {@code store: false} and {@code stream: true}.</li>
 *   <li>Send {@code input} as an array containing the context needed for each request.</li>
 *   <li>Omit background, conversation, max_output_tokens, max_tool_calls, metadata, moderation, multi_agent, prompt,
 *       prompt_cache_retention, safety_identifier, temperature, top_logprobs, top_p, truncation, user.</li>
 *   <li>Omit previous_response_id over HTTP.</li>
 *   <li>Use {@code instructions} or developer messages; explicit {@code role: "system"} items are rejected.</li>
 * </ul>
 */
class PlanUsageCompatibilityTest {

    /** Verbatim from the preview-limitations page. */
    static final Set<String> OMITTED_FIELDS = Set.of(
            "background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation", "multi_agent",
            "prompt", "prompt_cache_retention", "safety_identifier", "temperature", "top_logprobs", "top_p", "truncation",
            "user", "previous_response_id");

    static final Set<String> ALLOWED_ROLES = Set.of("user", "assistant", "developer");

    private final ObjectMapper mapper = new ObjectMapper();

    static class CapturingTransport implements OpenAiTransport {
        final List<JsonNode> bodies = new ArrayList<>();
        final ObjectMapper mapper = new ObjectMapper();
        boolean plan = true;

        @Override public String post(String path, String jsonBody) { throw new LlmException(LlmException.Kind.UNKNOWN, "no non-stream calls expected"); }
        @Override public String get(String path) { return "{\"models\":[]}"; }
        @Override public boolean usesChatGptPlan() { return plan; }

        @Override
        public void postStream(String path, String jsonBody, Consumer<String> lines) {
            try {
                bodies.add(mapper.readTree(jsonBody));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            assertThat(path).isEqualTo("/responses");
            lines.accept("data: {\"type\":\"response.output_text.delta\",\"delta\":\"{\\\"riskLevel\\\":\\\"NONE\\\",\\\"reason\\\":\\\"r\\\",\\\"recommendedAction\\\":\\\"a\\\"}\"}");
            lines.accept("");
            lines.accept("data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}");
            lines.accept("");
        }
    }

    private OpenAiLlmClient client(CapturingTransport t) {
        OpenAiProperties props = new OpenAiProperties("chatgpt", "", "https://api.openai.com/v1",
                "gpt-6.1-sol", "gpt-6-luna", "omni-moderation-latest", "medium", "low", 5, 30, 2000, 4000, null);
        return new OpenAiLlmClient(props, t, mapper);
    }

    @Test
    void everyRequestBodyRespectsPreviewLimitations() {
        CapturingTransport t = new CapturingTransport();
        OpenAiLlmClient client = client(t);

        // counseling turn: history + developer cue (first turn)
        client.streamCounselingResponse(new CounselingContext("gpt-6.1-sol", "SYSTEM PROMPT",
                List.of(ContextMessage.assistant("안녕하세요"), ContextMessage.user("힘들어요"), ContextMessage.developer("cue")), "medium"), d -> {});
        // structured requests (summary shape differs but request shape is what matters here)
        client.assessCrisis("gpt-6.1-sol", "ASSESS", "최근 대화");

        assertThat(t.bodies).hasSize(2);
        for (JsonNode body : t.bodies) {
            // required
            assertThat(body.get("store").asBoolean()).as("store").isFalse();
            assertThat(body.get("stream").asBoolean()).as("stream").isTrue();
            assertThat(body.get("input").isArray()).as("input must be an array").isTrue();
            assertThat(body.get("input").size()).isPositive();
            assertThat(body.get("instructions").isTextual()).as("instructions carries the system prompt").isTrue();
            assertThat(body.get("model").asText()).isEqualTo("gpt-6.1-sol");
            // omitted fields
            Iterator<String> names = body.fieldNames();
            while (names.hasNext()) {
                String field = names.next();
                assertThat(OMITTED_FIELDS).as("field '%s' is rejected under ChatGPT plan usage", field).doesNotContain(field);
            }
            // input items: role/content only, never system
            for (JsonNode item : body.get("input")) {
                assertThat(ALLOWED_ROLES).as("role").contains(item.get("role").asText());
                assertThat(item.get("content").isTextual()).isTrue();
                assertThat(item.has("type")).as("no explicit message type items").isFalse();
            }
            // nothing else sneaks in
            assertThat(body.has("tools")).isFalse();
            assertThat(body.has("tool_choice")).isFalse();
        }
        // the developer cue is sent as a developer message, not as a system item
        assertThat(t.bodies.get(0).get("input").get(2).get("role").asText()).isEqualTo("developer");
        // fields the page does not mention but we do send (documented as manual verification items)
        assertThat(t.bodies.get(0).get("reasoning").get("effort").asText()).isEqualTo("medium");
        assertThat(t.bodies.get(1).get("text").get("format").get("type").asText()).isEqualTo("json_schema");
    }

    @Test
    void apiKeyModeMayUseMaxOutputTokensButStillStreamsWithStoreFalse() {
        CapturingTransport t = new CapturingTransport();
        t.plan = false;
        OpenAiLlmClient client = client(t);
        client.streamCounselingResponse(new CounselingContext("gpt-6.1-sol", "S", List.of(ContextMessage.user("hi")), "low"), d -> {});
        JsonNode body = t.bodies.get(0);
        assertThat(body.get("max_output_tokens").asInt()).isEqualTo(2000);
        assertThat(body.get("store").asBoolean()).isFalse();
        assertThat(body.get("stream").asBoolean()).isTrue();
    }
}
