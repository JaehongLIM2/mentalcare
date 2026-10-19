package com.mentalcare.ai.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mentalcare.ai.LlmClient;
import com.mentalcare.ai.LlmException;
import com.mentalcare.ai.LlmException.Kind;
import com.mentalcare.ai.LlmResult;
import com.mentalcare.ai.LlmUsage;
import com.mentalcare.ai.ModelInfo;
import com.mentalcare.ai.ModerationSignal;
import com.mentalcare.ai.StreamHandler;
import com.mentalcare.ai.model.CrisisAssessmentOutput;
import com.mentalcare.ai.model.SessionSummaryOutput;
import com.mentalcare.config.OpenAiProperties;
import com.mentalcare.context.ContextMessage;
import com.mentalcare.context.CounselingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link LlmClient} backed by the OpenAI Responses API and Moderation API.
 * Every Responses request sets {@code store: false} and {@code stream: true} (both required under
 * ChatGPT plan usage): OpenAI never keeps the conversation and the local database is the only source
 * of truth. Fields that plan usage rejects ({@code max_output_tokens}) are omitted in that mode.
 */
@Component
public class OpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);

    private static final String RESPONSES = "/responses";
    private static final String MODERATIONS = "/moderations";
    private static final String MODELS = "/models";
    private static final int MAX_STREAM_CHARS = 4_000_000;

    private final OpenAiProperties props;
    private final OpenAiTransport transport;
    private final ObjectMapper mapper;
    private final JsonNode sessionSummarySchema;
    private final JsonNode crisisAssessmentSchema;

    public OpenAiLlmClient(OpenAiProperties props, OpenAiTransport transport, ObjectMapper mapper) {
        this.props = props;
        this.transport = transport;
        this.mapper = mapper;
        this.sessionSummarySchema = loadSchema("schemas/session-summary.schema.json");
        this.crisisAssessmentSchema = loadSchema("schemas/crisis-assessment.schema.json");
    }

    // ---------------------------------------------------------------- counseling (streaming)

    @Override
    public LlmUsage streamCounselingResponse(CounselingContext context, StreamHandler handler) {
        ObjectNode body = baseRequest(context.model(), context.instructions(), context.messages());
        body.putObject("reasoning").put("effort", context.reasoningEffort());
        if (!transport.usesChatGptPlan()) {
            body.put("max_output_tokens", props.counselingMaxOutputTokens());
        }
        StreamResult result = stream(body, handler, context.model());
        return result.usage;
    }

    // ---------------------------------------------------------------- structured outputs (also streamed)

    @Override
    public LlmResult<SessionSummaryOutput> generateSessionSummary(String model, String instructions, String transcript) {
        return structured(model, props.utilityReasoningEffort(), props.utilityMaxOutputTokens(),
                instructions, transcript, "session_summary", sessionSummarySchema, SessionSummaryOutput.class);
    }

    @Override
    public LlmResult<CrisisAssessmentOutput> assessCrisis(String model, String instructions, String recentConversation) {
        return structured(model, "low", 800,
                instructions, recentConversation, "crisis_assessment", crisisAssessmentSchema, CrisisAssessmentOutput.class);
    }

    private <T> LlmResult<T> structured(String model, String reasoningEffort, int maxOutputTokens,
                                        String instructions, String userInput,
                                        String schemaName, JsonNode schema, Class<T> type) {
        ObjectNode body = baseRequest(model, instructions, List.of(ContextMessage.user(userInput)));
        body.putObject("reasoning").put("effort", reasoningEffort);
        if (!transport.usesChatGptPlan()) {
            body.put("max_output_tokens", maxOutputTokens);
        }
        ObjectNode format = body.putObject("text").putObject("format");
        format.put("type", "json_schema");
        format.put("name", schemaName);
        format.put("strict", true);
        format.set("schema", schema);

        StringBuilder text = new StringBuilder();
        StreamResult result = stream(body, text::append, model);
        try {
            T value = mapper.readValue(text.toString(), type);
            return new LlmResult<>(value, result.usage);
        } catch (JsonProcessingException e) {
            throw new LlmException(Kind.PARSE_FAILED, "Structured output did not match " + type.getSimpleName(), e);
        }
    }

    // ---------------------------------------------------------------- shared streaming core

    private static final class StreamResult {
        LlmUsage usage;
        boolean completed;
        LlmException failure;
        final StringBuilder refusal = new StringBuilder();
        int chars;
    }

    private StreamResult stream(ObjectNode body, StreamHandler handler, String model) {
        body.put("stream", true);
        StreamResult state = new StreamResult();
        state.usage = LlmUsage.none(model);
        SseEventReader reader = new SseEventReader((event, data) -> handleStreamEvent(event, data, handler, state, model));
        transport.postStream(RESPONSES, toJson(body), reader::line);
        reader.finish();

        if (state.failure != null) {
            throw state.failure;
        }
        if (!state.refusal.isEmpty()) {
            throw new LlmException(Kind.REFUSAL, "Model refused the request");
        }
        if (!state.completed) {
            throw new LlmException(Kind.STREAM_INTERRUPTED, "Stream ended without a completion event");
        }
        return state;
    }

    private void handleStreamEvent(String event, String data, StreamHandler handler, StreamResult state, String model) {
        if ("[DONE]".equals(data) || state.failure != null) {
            return;
        }
        JsonNode node;
        try {
            node = mapper.readTree(data);
        } catch (JsonProcessingException e) {
            return; // ignore malformed keep-alives
        }
        String type = node.path("type").asText(event);
        switch (type) {
            case "response.output_text.delta" -> {
                String delta = node.path("delta").asText("");
                if (delta.isEmpty()) return;
                state.chars += delta.length();
                if (state.chars > MAX_STREAM_CHARS) {
                    state.failure = new LlmException(Kind.INCOMPLETE, "Response too large");
                    return;
                }
                handler.onDelta(delta);
            }
            case "response.refusal.delta" -> state.refusal.append(node.path("delta").asText(""));
            case "response.completed" -> {
                state.usage = usageOf(node.path("response"), model);
                state.completed = true;
            }
            case "response.incomplete" -> {
                state.usage = usageOf(node.path("response"), model);
                String reason = node.path("response").path("incomplete_details").path("reason").asText("");
                log.warn("response incomplete reason={}", reason);
                state.failure = new LlmException(Kind.INCOMPLETE, "Response incomplete: " + reason);
            }
            case "response.failed" -> {
                JsonNode err = node.path("response").path("error");
                state.failure = failure(err.path("code").asText(null), err.path("message").asText(""), err.path("param").asText(null));
            }
            case "error" -> {
                JsonNode err = node.has("error") ? node.path("error") : node;
                state.failure = failure(err.path("code").asText(null), err.path("message").asText(""), err.path("param").asText(null));
            }
            default -> { /* lifecycle events we do not need */ }
        }
    }

    private LlmException failure(String code, String message, String param) {
        Kind kind = code == null ? Kind.SERVER : OpenAiErrors.classify(0, code);
        if (kind == Kind.UNKNOWN) kind = Kind.SERVER;
        log.warn("stream failed kind={} code={} param={}", kind, code, param);
        return new LlmException(kind, "Response failed: code=" + code + (param == null ? "" : " param=" + param) + " " + message, code, null);
    }

    // ---------------------------------------------------------------- moderation / models

    @Override
    public ModerationSignal moderate(String text) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.moderationModel());
        body.put("input", text);
        JsonNode response = parse(transport.post(MODERATIONS, toJson(body)));
        JsonNode result = response.path("results").path(0);
        if (result.isMissingNode()) {
            throw new LlmException(Kind.PARSE_FAILED, "Moderation response has no results");
        }
        Map<String, Double> scores = new HashMap<>();
        JsonNode scoreNode = result.path("category_scores");
        for (String category : List.of(ModerationSignal.SELF_HARM, ModerationSignal.SELF_HARM_INTENT,
                ModerationSignal.SELF_HARM_INSTRUCTIONS, ModerationSignal.VIOLENCE)) {
            scores.put(category, scoreNode.path(category).asDouble(0.0));
        }
        return new ModerationSignal(result.path("flagged").asBoolean(false), scores);
    }

    /**
     * Supports both catalog shapes: ChatGPT plan usage returns {@code models[]} with
     * {@code slug/display_name/visibility}; the platform API returns {@code data[]} with {@code id}.
     */
    @Override
    public List<ModelInfo> listModels() {
        JsonNode response = parse(transport.get(MODELS));
        List<ModelInfo> out = new ArrayList<>();
        if (response.path("models").isArray()) {
            for (JsonNode m : response.path("models")) {
                if (!m.isObject()) continue;
                if (!"list".equals(m.path("visibility").asText(""))) continue;
                String slug = m.path("slug").asText("");
                String name = m.path("display_name").asText(slug);
                if (slug.isBlank() || slug.length() > 200) continue;
                out.add(new ModelInfo(slug, name.isBlank() ? slug : name));
            }
            return out;
        }
        if (response.path("data").isArray()) {
            for (JsonNode m : response.path("data")) {
                String id = m.path("id").asText("");
                if (!id.isBlank()) out.add(new ModelInfo(id, id));
            }
            out.sort((a, b) -> a.slug().compareTo(b.slug()));
            return out;
        }
        throw new LlmException(Kind.PARSE_FAILED, "Unexpected model catalog shape");
    }

    // ---------------------------------------------------------------- helpers

    private ObjectNode baseRequest(String model, String instructions, List<ContextMessage> messages) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("instructions", instructions);
        ArrayNode input = body.putArray("input");
        for (ContextMessage m : messages) {
            ObjectNode item = input.addObject();
            item.put("role", m.role());
            item.put("content", m.content());
        }
        body.put("store", false); // never let OpenAI retain counseling conversations
        return body;
    }

    private static LlmUsage usageOf(JsonNode response, String fallbackModel) {
        JsonNode usage = response.path("usage");
        String model = response.path("model").asText(fallbackModel);
        return new LlmUsage(model, usage.path("input_tokens").asLong(0), usage.path("output_tokens").asLong(0));
    }

    private String toJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new LlmException(Kind.PARSE_FAILED, "Response is not valid JSON", e);
        }
    }

    private JsonNode loadSchema(String path) {
        try {
            return mapper.readTree(new ClassPathResource(path).getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load schema " + path, e);
        }
    }
}
