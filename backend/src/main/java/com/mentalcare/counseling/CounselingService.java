package com.mentalcare.counseling;

import com.mentalcare.ai.LlmClient;
import com.mentalcare.ai.LlmException;
import com.mentalcare.ai.LlmUsage;
import com.mentalcare.context.CounselingContext;
import com.mentalcare.context.CounselingContextBuilder;
import com.mentalcare.context.SafetyMode;
import com.mentalcare.domain.Enums.MessageRole;
import com.mentalcare.domain.Enums.MessageStatus;
import com.mentalcare.domain.Enums.RiskLevel;
import com.mentalcare.domain.Message;
import com.mentalcare.domain.Session;
import com.mentalcare.repository.MessageRepository;
import com.mentalcare.repository.SessionRepository;
import com.mentalcare.safety.SafetyDecision;
import com.mentalcare.safety.SafetyService;
import com.mentalcare.usage.UsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * One counseling turn: persist the user's message first, run the safety layer, build the context,
 * stream the reply, persist the reply. Runs synchronously on the caller's thread; the controller
 * puts it on an executor and relays the listener callbacks as SSE events.
 * Turns on the same session are serialized through {@link SessionLocks}; a concurrent turn is refused.
 */
@Service
public class CounselingService {

    private static final Logger log = LoggerFactory.getLogger(CounselingService.class);
    static final String BUSY_MESSAGE = "이 상담에서 다른 작업(응답 생성 또는 종료)이 진행 중입니다. 잠시 후 다시 시도하세요.";

    private final SessionService sessionService;
    private final SessionRepository sessions;
    private final MessageRepository messages;
    private final SafetyService safety;
    private final CounselingContextBuilder contextBuilder;
    private final LlmClient llm;
    private final UsageRecorder usage;
    private final SessionLocks locks;

    public CounselingService(SessionService sessionService, SessionRepository sessions, MessageRepository messages,
                             SafetyService safety, CounselingContextBuilder contextBuilder, LlmClient llm,
                             UsageRecorder usage, SessionLocks locks) {
        this.sessionService = sessionService;
        this.sessions = sessions;
        this.messages = messages;
        this.safety = safety;
        this.contextBuilder = contextBuilder;
        this.llm = llm;
        this.usage = usage;
        this.locks = locks;
    }

    /** The counselor's first words after check-in. Idempotent: if the session already has messages, nothing is generated. */
    public void opening(long sessionId, TurnListener listener) {
        if (!locks.tryLock(sessionId)) {
            listener.onError(BUSY_MESSAGE, "BUSY");
            return;
        }
        try {
            Session session = sessionService.get(sessionId);
            if (!session.isActive()) {
                listener.onError("이미 종료된 상담입니다.", "CONFLICT");
                return;
            }
            List<Message> existing = messages.findBySession(sessionId);
            if (!existing.isEmpty()) {
                existing.stream().filter(m -> m.role() == MessageRole.ASSISTANT).reduce((a, b) -> b)
                        .ifPresentOrElse(listener::onDone, () -> listener.onError("이미 시작된 상담입니다.", "CONFLICT"));
                return;
            }
            SafetyMode mode = session.crisisFlag() ? SafetyMode.CAUTION : SafetyMode.NORMAL;
            generate(session, existing, true, mode, listener);
        } finally {
            locks.unlock(sessionId);
        }
    }

    /** Saves the user's message, evaluates safety, and streams the reply. */
    public void reply(long sessionId, String userText, TurnListener listener) {
        if (userText == null || userText.isBlank()) {
            listener.onError("메시지가 비어 있습니다.", "BAD_REQUEST");
            return;
        }
        if (!locks.tryLock(sessionId)) {
            listener.onError(BUSY_MESSAGE, "BUSY");
            return;
        }
        try {
            Session session = sessionService.get(sessionId);
            if (!session.isActive()) {
                listener.onError("이미 종료된 상담입니다. 새 상담을 시작하세요.", "CONFLICT");
                return;
            }

            // 1. Persist first so nothing is lost if the AI call fails.
            Message userMessage = messages.insert(sessionId, MessageRole.USER, userText.trim(), MessageStatus.COMPLETE, null);
            sessions.touch(sessionId);
            listener.onUserSaved(userMessage);

            // 2. Safety layer.
            List<Message> all = messages.findBySession(sessionId);
            SafetyDecision decision = safety.evaluate(session, userMessage, all);
            if (decision.riskLevel() != RiskLevel.NONE) {
                messages.updateRisk(userMessage.id(), decision.riskLevel());
                listener.onSafety(decision.riskLevel());
            }
            if (decision.riskLevel() == RiskLevel.IMMINENT && !session.crisisFlag()) {
                sessions.setCrisisFlag(sessionId, true);
                session = sessionService.get(sessionId);
            }

            // 3-5. Context, stream, persist.
            generate(session, all, false, decision.mode(), listener);
        } finally {
            locks.unlock(sessionId);
        }
    }

    private void generate(Session session, List<Message> sessionMessages, boolean opening, SafetyMode mode,
                          TurnListener listener) {
        CounselingContext context = contextBuilder.build(session, sessionMessages, opening, mode);
        StringBuilder buffer = new StringBuilder();
        try {
            LlmUsage used = llm.streamCounselingResponse(context, delta -> {
                buffer.append(delta);
                listener.onDelta(delta);
            });
            usage.record(UsageRecorder.COUNSELING, used, session.id());
            Message saved = messages.insert(session.id(), MessageRole.ASSISTANT, buffer.toString(),
                    MessageStatus.COMPLETE, null);
            sessions.touch(session.id());
            listener.onDone(saved);
        } catch (LlmException e) {
            log.warn("Counseling stream failed session={} kind={} code={}", session.id(), e.kind(), e.providerCode());
            if (!buffer.isEmpty()) {
                Message partial = messages.insert(session.id(), MessageRole.ASSISTANT, buffer.toString(),
                        MessageStatus.INTERRUPTED, null);
                listener.onDone(partial);
            }
            listener.onError(e.userMessage(), e.kind().name());
        } catch (RuntimeException e) {
            log.error("Unexpected error during counseling turn session={}: {}", session.id(), e.getClass().getName());
            if (!buffer.isEmpty()) {
                Message partial = messages.insert(session.id(), MessageRole.ASSISTANT, buffer.toString(),
                        MessageStatus.INTERRUPTED, null);
                listener.onDone(partial);
            }
            listener.onError("응답 생성 중 오류가 발생했습니다.", "INTERNAL");
        }
    }
}
