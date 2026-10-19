package com.mentalcare.ai.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalcare.ai.LlmException;
import com.mentalcare.ai.LlmResult;
import com.mentalcare.ai.LlmUsage;
import com.mentalcare.ai.ModelInfo;
import com.mentalcare.ai.ModerationSignal;
import com.mentalcare.ai.model.CrisisAssessmentOutput;
import com.mentalcare.ai.model.SessionSummaryOutput;
import com.mentalcare.config.OpenAiProperties;
import com.mentalcare.context.ContextMessage;
import com.mentalcare.context.CounselingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises request shaping and response parsing against a fake transport (no network). */
class OpenAiLlmClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiProperties props = new OpenAiProperties("chatgpt", "", "https://example.invalid/v1",
            "counsel-model", "util-model", "mod-model", "medium", "low", 5, 30, 1000, 2000, null);

    private FakeTransport transport;
    private OpenAiLlmClient client;

    static class FakeTransport implements OpenAiTransport {
        String lastPath;
        JsonNode lastBody;
        String response = "{}";
        List<String> streamLines = List.of();
        LlmException failWith;
        boolean chatGptPlan = true;
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public String post(String path, String jsonBody) {
            remember(path, jsonBody);
            if (failWith != null) throw failWith;
            return response;
        }

        @Override
        public String get(String path) {
            lastPath = path;
            if (failWith != null) throw failWith;
            return response;
        }

        @Override
        public void postStream(String path, String jsonBody, Consumer<String> lineConsumer) {
            remember(path, jsonBody);
            if (failWith != null) throw failWith;
            streamLines.forEach(lineConsumer);
        }

        @Override
        public boolean usesChatGptPlan() {
            return chatGptPlan;
        }

        private void remember(String path, String body) {
            lastPath = path;
            try {
                lastBody = mapper.readTree(body);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static List<String> sse(String... events) {
        List<String> lines = new ArrayList<>();
        for (String e : events) {
            lines.add("event: x");
            lines.add("data: " + e);
            lines.add("");
        }
        return lines;
    }

    private static String delta(String text) {
        return "{\"type\":\"response.output_text.delta\",\"delta\":" + quote(text) + "}";
    }

    private static String completed(String model, int in, int out) {
        return "{\"type\":\"response.completed\",\"response\":{\"model\":\"" + model + "\",\"usage\":{\"input_tokens\":" + in + ",\"output_tokens\":" + out + "}}}";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        client = new OpenAiLlmClient(props, transport, mapper);
    }

    @Test
    void streamingRequestSetsStoreFalseAndCollectsDeltas() {
        transport.streamLines = sse("{\"type\":\"response.created\"}", delta("안녕"), delta("하세요"), completed("counsel-model-2026", 120, 8));
        List<String> deltas = new ArrayList<>();
        CounselingContext ctx = new CounselingContext("counsel-model", "SYS", List.of(ContextMessage.user("hi")), "medium");

        LlmUsage usage = client.streamCounselingResponse(ctx, deltas::add);

        assertThat(deltas).containsExactly("안녕", "하세요");
        assertThat(usage.inputTokens()).isEqualTo(120);
        assertThat(usage.outputTokens()).isEqualTo(8);
        assertThat(usage.model()).isEqualTo("counsel-model-2026");
        assertThat(transport.lastPath).isEqualTo("/responses");
        assertThat(transport.lastBody.get("store").asBoolean()).isFalse();
        assertThat(transport.lastBody.get("stream").asBoolean()).isTrue();
        assertThat(transport.lastBody.get("model").asText()).isEqualTo("counsel-model");
        assertThat(transport.lastBody.get("instructions").asText()).isEqualTo("SYS");
        assertThat(transport.lastBody.get("reasoning").get("effort").asText()).isEqualTo("medium");
        assertThat(transport.lastBody.get("input").get(0).get("role").asText()).isEqualTo("user");
        // fields rejected by ChatGPT plan usage are omitted in that mode
        assertThat(transport.lastBody.has("max_output_tokens")).isFalse();
        assertThat(transport.lastBody.has("previous_response_id")).isFalse();
        assertThat(transport.lastBody.has("metadata")).isFalse();
        assertThat(transport.lastBody.has("user")).isFalse();
    }

    @Test
    void apiKeyModeMayLimitOutputTokens() {
        transport.chatGptPlan = false;
        transport.streamLines = sse(delta("x"), completed("m", 1, 1));
        client.streamCounselingResponse(new CounselingContext("counsel-model", "SYS", List.of(ContextMessage.user("hi")), "low"), d -> {});
        assertThat(transport.lastBody.get("max_output_tokens").asInt()).isEqualTo(1000);
    }

    @Test
    void streamFailureEventBecomesTypedException() {
        transport.streamLines = sse(delta("부분"),
                "{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"limit\"}}}");
        CounselingContext ctx = new CounselingContext("counsel-model", "SYS", List.of(ContextMessage.user("hi")), "medium");

        assertThatThrownBy(() -> client.streamCounselingResponse(ctx, d -> {}))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.kind()).isEqualTo(LlmException.Kind.PLAN_LIMIT);
                    assertThat(e.providerCode()).isEqualTo("subscription_sharing_usage_limit_exceeded");
                });
    }

    @Test
    void streamWithoutCompletionIsInterrupted() {
        transport.streamLines = sse(delta("x"));
        CounselingContext ctx = new CounselingContext("counsel-model", "SYS", List.of(ContextMessage.user("hi")), "medium");

        assertThatThrownBy(() -> client.streamCounselingResponse(ctx, d -> {}))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.STREAM_INTERRUPTED));
    }

    @Test
    void structuredSummaryIsStreamedWithStrictSchema() {
        String json = "{\"title\":\"회사 스트레스\",\"topics\":[\"work\"],\"keyEvents\":[\"상사와 갈등\"],\"emotions\":[\"불안\"],\"userThoughts\":[],"
                + "\"observedPatterns\":[\"평가 걱정 가능성\"],\"discoveries\":[],\"actionItems\":[],\"nextSessionTopics\":[\"상사 면담 결과\"],"
                + "\"memoryCandidates\":[{\"content\":\"상사와의 관계가 반복적인 스트레스 원인\",\"kind\":\"USER_STATED\",\"category\":\"relationship\",\"reason\":\"반복\",\"matchesExistingMemoryId\":null}],"
                + "\"tags\":[\"work\",\"stress\"]}";
        transport.streamLines = sse(delta(json.substring(0, 40)), delta(json.substring(40)), completed("util-model", 50, 40));

        LlmResult<SessionSummaryOutput> result = client.generateSessionSummary("util-model", "PROMPT", "TRANSCRIPT");

        assertThat(result.value().title()).isEqualTo("회사 스트레스");
        assertThat(result.value().memoryCandidates()).hasSize(1);
        assertThat(result.value().memoryCandidates().get(0).matchesExistingMemoryId()).isNull();
        assertThat(result.usage().inputTokens()).isEqualTo(50);
        JsonNode format = transport.lastBody.get("text").get("format");
        assertThat(format.get("type").asText()).isEqualTo("json_schema");
        assertThat(format.get("strict").asBoolean()).isTrue();
        assertThat(format.get("schema").get("required")).isNotEmpty();
        assertThat(transport.lastBody.get("store").asBoolean()).isFalse();
        assertThat(transport.lastBody.get("stream").asBoolean()).isTrue();
        assertThat(transport.lastBody.get("model").asText()).isEqualTo("util-model");
    }

    @Test
    void refusalIncompleteAndParseFailuresAreTyped() {
        transport.streamLines = sse("{\"type\":\"response.refusal.delta\",\"delta\":\"no\"}", completed("m", 1, 1));
        assertThatThrownBy(() -> client.generateSessionSummary("m", "P", "T"))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.REFUSAL));

        transport.streamLines = sse(delta("{\"title\""), "{\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}");
        assertThatThrownBy(() -> client.generateSessionSummary("m", "P", "T"))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.INCOMPLETE));

        transport.streamLines = sse(delta("not json"), completed("m", 1, 1));
        assertThatThrownBy(() -> client.generateSessionSummary("m", "P", "T"))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.PARSE_FAILED));

        transport.failWith = new LlmException(LlmException.Kind.RATE_LIMIT, "429");
        assertThatThrownBy(() -> client.generateSessionSummary("m", "P", "T"))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.RATE_LIMIT));
    }

    @Test
    void crisisAssessmentUsesGivenModel() {
        transport.streamLines = sse(delta("{\"riskLevel\":\"ELEVATED\",\"reason\":\"r\",\"recommendedAction\":\"a\"}"), completed("counsel-model", 1, 1));

        LlmResult<CrisisAssessmentOutput> result = client.assessCrisis("counsel-model", "P", "C");

        assertThat(result.value().riskLevel()).isEqualTo("ELEVATED");
        assertThat(transport.lastBody.get("model").asText()).isEqualTo("counsel-model");
        assertThat(transport.lastBody.get("store").asBoolean()).isFalse();
    }

    @Test
    void moderationExtractsRelevantScores() {
        transport.response = """
                {"results":[{"flagged":true,"categories":{"self-harm":true},
                  "category_scores":{"self-harm":0.91,"self-harm/intent":0.4,"self-harm/instructions":0.01,"violence":0.02,"harassment":0.5}}]}""";

        ModerationSignal signal = client.moderate("text");

        assertThat(signal.flagged()).isTrue();
        assertThat(signal.score(ModerationSignal.SELF_HARM)).isEqualTo(0.91);
        assertThat(signal.selfHarmSignal(0.4)).isTrue();
        assertThat(signal.violenceSignal(0.4)).isFalse();
        assertThat(transport.lastPath).isEqualTo("/moderations");
        assertThat(transport.lastBody.get("model").asText()).isEqualTo("mod-model");
    }

    @Test
    void listModelsSupportsPlanUsageAndPlatformShapes() {
        transport.response = """
                {"models":[{"slug":"gpt-6.1-sol","display_name":"GPT-6.1 Sol","visibility":"list"},
                           {"slug":"hidden-model","display_name":"Hidden","visibility":"hidden"},
                           {"slug":"","display_name":"broken","visibility":"list"}]}""";
        List<ModelInfo> plan = client.listModels();
        assertThat(plan).containsExactly(new ModelInfo("gpt-6.1-sol", "GPT-6.1 Sol"));
        assertThat(transport.lastPath).isEqualTo("/models");

        transport.response = """
                {"object":"list","data":[{"id":"gpt-6-luna","object":"model"},{"id":"gpt-6.1-sol","object":"model"}]}""";
        List<ModelInfo> platform = client.listModels();
        assertThat(platform).extracting(ModelInfo::slug).containsExactly("gpt-6-luna", "gpt-6.1-sol");

        transport.response = "{\"weird\":true}";
        assertThatThrownBy(() -> client.listModels())
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.PARSE_FAILED));
    }
}
