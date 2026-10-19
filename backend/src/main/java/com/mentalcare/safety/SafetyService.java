package com.mentalcare.safety;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalcare.ai.LlmClient;
import com.mentalcare.ai.LlmException;
import com.mentalcare.ai.LlmResult;
import com.mentalcare.ai.ModelCatalogService;
import com.mentalcare.ai.ModerationSignal;
import com.mentalcare.ai.model.CrisisAssessmentOutput;
import com.mentalcare.config.AppProperties;
import com.mentalcare.context.SafetyMode;
import com.mentalcare.domain.Enums.MessageRole;
import com.mentalcare.domain.Enums.RiskLevel;
import com.mentalcare.domain.Message;
import com.mentalcare.domain.Session;
import com.mentalcare.prompt.PromptLoader;
import com.mentalcare.repository.SafetyEventRepository;
import com.mentalcare.usage.UsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Safety layer. Moderation API and a keyword list are <em>signals</em>; when either fires, a
 * structured crisis assessment with conversational context makes the call. Failures of the
 * external calls degrade conservatively (keyword hit → ELEVATED) and never block counseling.
 *
 * <p>When moderation is not available (the route is not admitted for a ChatGPT plan token, or it
 * failed for this message), the keyword list alone would miss risk phrased without any keyword.
 * In that case every user message is <b>screened</b> with the same structured assessment. A failed
 * screen without any signal keeps the turn as is (NONE) but is recorded as a safety event so the
 * gap is auditable; a failed assessment after a signal stays conservative (ELEVATED).
 *
 * <p>This is a self-reflection tool, not a validated clinical screening instrument.
 */
@Service
public class SafetyService {

    private static final Logger log = LoggerFactory.getLogger(SafetyService.class);
    /** An unsupported route does not come back by itself; retry rarely (it may be enabled on the account later). */
    static final Duration MODERATION_BACKOFF = Duration.ofHours(6);

    private final LlmClient llm;
    private final CrisisKeywordDetector keywords;
    private final PromptLoader prompts;
    private final SafetyEventRepository events;
    private final UsageRecorder usage;
    private final AppProperties app;
    private final ObjectMapper mapper;
    private final ModelCatalogService models;

    /** When the moderation route is not available for this credential, skip it for a while instead of failing every turn. */
    private volatile Instant moderationDisabledUntil = Instant.EPOCH;

    public SafetyService(LlmClient llm, CrisisKeywordDetector keywords, PromptLoader prompts,
                         SafetyEventRepository events, UsageRecorder usage, AppProperties app, ObjectMapper mapper,
                         ModelCatalogService models) {
        this.llm = llm;
        this.keywords = keywords;
        this.prompts = prompts;
        this.events = events;
        this.usage = usage;
        this.app = app;
        this.mapper = mapper;
        this.models = models;
    }

    /**
     * @param session        the session (its crisis flag keeps later turns in CAUTION mode)
     * @param userMessage    the message just saved
     * @param recentMessages the last few messages of the session, oldest first, including userMessage
     */
    public SafetyDecision evaluate(Session session, Message userMessage, List<Message> recentMessages) {
        double threshold = app.safety().moderationThreshold();

        ModerationSignal moderation = moderate(userMessage.content());
        boolean keywordHit = keywords.matches(userMessage.content());
        boolean moderationHit = moderation != null && moderation.anySignal(threshold);
        boolean screening = moderation == null && !keywordHit; // no moderation: assess every message

        if (!keywordHit && !moderationHit && !screening) {
            return SafetyDecision.none(session.crisisFlag());
        }

        RiskLevel level;
        String reason;
        try {
            LlmResult<CrisisAssessmentOutput> result = llm.assessCrisis(
                    models.counselingModel(),
                    prompts.load(PromptLoader.CRISIS_ASSESSMENT).content(),
                    formatConversation(recentMessages));
            if (result == null || result.value() == null) {
                throw new LlmException(LlmException.Kind.PARSE_FAILED, "empty crisis assessment");
            }
            usage.record(UsageRecorder.CRISIS_ASSESSMENT, result.usage(), session.id());
            level = parseLevel(result.value().riskLevel());
            reason = result.value().reason();
        } catch (LlmException e) {
            if (screening) {
                // nothing pointed at risk and we could not look closer: keep counseling going, leave a trace
                log.warn("Safety screen unavailable ({}) session={} message={}; no signal, keeping NONE",
                        e.kind(), session.id(), userMessage.id());
                recordEvent(session.id(), userMessage.id(), null, false, RiskLevel.NONE, "screening unavailable: " + e.kind());
                return SafetyDecision.none(session.crisisFlag());
            }
            log.warn("Crisis assessment unavailable ({}); falling back to conservative ELEVATED", e.kind());
            level = RiskLevel.ELEVATED;
            reason = "assessment unavailable: " + e.kind();
        }

        if (screening && level == RiskLevel.NONE) {
            return SafetyDecision.none(session.crisisFlag()); // routine screen: no event row per message
        }

        // A moderation-only hit (no keyword) that the assessment downgrades to NONE stays NONE: the
        // assessment saw the context, moderation did not.
        recordEvent(session.id(), userMessage.id(), moderation, keywordHit, level, reason);
        log.info("safety session={} message={} moderationHit={} keywordHit={} screening={} risk={}",
                session.id(), userMessage.id(), moderationHit, keywordHit, screening, level);

        SafetyMode mode = switch (level) {
            case IMMINENT -> SafetyMode.CRISIS;
            case ELEVATED -> SafetyMode.CAUTION;
            case LOW, NONE -> session.crisisFlag() ? SafetyMode.CAUTION : SafetyMode.NORMAL;
        };
        return new SafetyDecision(level, mode, reason);
    }

    /** True while moderation is switched off because its route is not available for this credential. */
    public boolean moderationSuspended() {
        return moderationDisabledUntil.isAfter(Instant.now());
    }

    private ModerationSignal moderate(String text) {
        if (moderationDisabledUntil.isAfter(Instant.now())) {
            return null;
        }
        try {
            return llm.moderate(text);
        } catch (LlmException e) {
            if (e.kind() == LlmException.Kind.ROUTE_NOT_SUPPORTED || e.kind() == LlmException.Kind.PLAN_NOT_ELIGIBLE
                    || e.kind() == LlmException.Kind.UNSUPPORTED_CAPABILITY) {
                moderationDisabledUntil = Instant.now().plus(MODERATION_BACKOFF);
                log.warn("Moderation route not available for this credential ({}); screening every message for {} h",
                        e.kind(), MODERATION_BACKOFF.toHours());
            } else {
                // transient (network, server) or a real auth problem: try moderation again next turn
                log.warn("Moderation unavailable ({}); screening this message", e.kind());
            }
            return null;
        }
    }

    private String formatConversation(List<Message> messages) {
        int max = app.safety().assessmentContextMessages();
        List<Message> window = messages.size() > max ? messages.subList(messages.size() - max, messages.size()) : messages;
        StringBuilder sb = new StringBuilder("최근 대화 (오래된 것부터):\n\n");
        for (Message m : window) {
            sb.append(m.role() == MessageRole.USER ? "[사용자] " : "[상담자] ").append(m.content().trim()).append("\n\n");
        }
        sb.append("마지막 [사용자] 메시지를 중심으로 현재 위험 수준을 판단하세요.");
        return sb.toString();
    }

    private void recordEvent(long sessionId, long messageId, ModerationSignal moderation, boolean keywordHit,
                             RiskLevel level, String reason) {
        try {
            String scores = moderation == null ? null : mapper.writeValueAsString(moderation.scores());
            events.insert(sessionId, messageId, moderation != null && moderation.flagged(), scores, keywordHit, level, reason);
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Could not record safety event: {}", e.getClass().getSimpleName());
        }
    }

    static RiskLevel parseLevel(String value) {
        try {
            return value == null ? RiskLevel.ELEVATED : RiskLevel.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return RiskLevel.ELEVATED;
        }
    }
}
