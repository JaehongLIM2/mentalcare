package com.mentalcare.counseling;

import com.mentalcare.ai.LlmClient;
import com.mentalcare.ai.LlmException;
import com.mentalcare.ai.LlmUsage;
import com.mentalcare.context.ContextOutline;
import com.mentalcare.context.CounselingContext;
import com.mentalcare.context.CounselingContextBuilder;
import com.mentalcare.context.SafetyMode;
import com.mentalcare.domain.Enums.MessageRole;
import com.mentalcare.domain.Enums.MessageStatus;
import com.mentalcare.domain.Enums.RiskLevel;
import com.mentalcare.domain.Message;
import com.mentalcare.domain.Session;
import com.mentalcare.repository.DraftRepository;
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
    private final DraftRepository drafts;
    private final SafetyService safety;
    private final CounselingContextBuilder contextBuilder;
    private final LlmClient llm;
    private final UsageRecorder usage;
    private final SessionLocks locks;
    private final com.mentalcare.repository.AttachmentRepository attachmentRepository;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public CounselingService(SessionService sessionService, SessionRepository sessions, MessageRepository messages,
                             DraftRepository drafts, SafetyService safety, CounselingContextBuilder contextBuilder,
                             LlmClient llm, UsageRecorder usage, SessionLocks locks,
                             com.mentalcare.repository.AttachmentRepository attachmentRepository,
                             org.springframework.transaction.support.TransactionTemplate tx) {
        this.attachmentRepository = attachmentRepository;
        this.tx = tx;
        this.sessionService = sessionService;
        this.sessions = sessions;
        this.messages = messages;
        this.drafts = drafts;
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
        reply(sessionId, userText, null, listener);
    }

    /**
     * @param draftRev the browser's draft revision at the moment of sending; autosaves issued before it
     *                 (still in flight) can no longer bring the sent text back as a draft
     */
    public void reply(long sessionId, String userText, Long draftRev, TurnListener listener) {
        reply(sessionId, userText, draftRev, List.of(), listener);
    }

    /**
     * @param files text files attached to this message (validated here). Their full text is given to the model
     *              only on this turn (and a retry of it); later turns see the file names only.
     */
    public void reply(long sessionId, String userText, Long draftRev, List<Attachments.Input> files, TurnListener listener) {
        List<Attachments.Input> attached;
        try {
            attached = Attachments.validate(files);
        } catch (IllegalArgumentException e) {
            listener.onError(e.getMessage(), "BAD_REQUEST");
            return;
        }
        if ((userText == null || userText.isBlank()) && attached.isEmpty()) {
            listener.onError("메시지가 비어 있습니다.", "BAD_REQUEST");
            return;
        }
        String text = userText == null ? "" : userText.trim();
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

            // 1. Persist first so nothing is lost if the AI call fails. The sent text is no longer a draft.
            Message userMessage = tx.execute(status -> {
                Message saved = messages.insert(sessionId, MessageRole.USER, text, MessageStatus.COMPLETE, null);
                for (Attachments.Input f : attached) attachmentRepository.insert(saved.id(), sessionId, f.name(), f.content());
                drafts.clearUpTo(sessionId, draftRev == null ? DraftRevisions.next() : draftRev);
                return saved;
            });
            if (!attached.isEmpty()) log.info("message saved session={} attachments={}", sessionId, attached.size());
            sessions.touch(sessionId);
            listener.onUserSaved(userMessage);

            // 2. Safety layer.
            List<Message> all = messages.findBySession(sessionId);
            SafetyDecision decision = safety.evaluate(session, userMessage, all);
            if (decision.riskLevel() != RiskLevel.NONE) {
                messages.updateRisk(userMessage.id(), decision.riskLevel());
                listener.onSafety(decision.riskLevel());
            }
            if (decision.unavailable()) {
                messages.markSafetyUnavailable(userMessage.id());
                listener.onSafetyUnavailable();
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

    /**
     * Requests the reply again after a failed or interrupted generation, <b>without</b> saving the user's
     * message a second time. The last saved user message (and its stored safety level) is reused; a partial
     * reply left behind by the interruption is marked FAILED so it neither reaches the model nor the screen.
     * If the reply did arrive in the meantime (the browser only lost the connection), nothing is generated.
     */
    public void retry(long sessionId, TurnListener listener) {
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
            List<Message> all = messages.findBySession(sessionId);
            Message last = all.isEmpty() ? null : all.get(all.size() - 1);
            if (last != null && last.role() == MessageRole.ASSISTANT && last.status() == MessageStatus.INTERRUPTED) {
                messages.updateStatus(last.id(), MessageStatus.FAILED);
                all = messages.findBySession(sessionId);
            }
            Message lastUsable = all.stream().filter(m -> m.status() != MessageStatus.FAILED).reduce((a, b) -> b).orElse(null);
            if (lastUsable != null && lastUsable.role() == MessageRole.ASSISTANT) {
                listener.onError("답변이 이미 저장되어 있습니다. 화면을 새로고침하면 보입니다.", "ALREADY_ANSWERED");
                return;
            }
            boolean opening = lastUsable == null; // the greeting itself failed
            RiskLevel risk = lastUsable == null || lastUsable.riskLevel() == null ? RiskLevel.NONE : lastUsable.riskLevel();
            SafetyMode mode = switch (risk) {
                case IMMINENT -> SafetyMode.CRISIS;
                case ELEVATED -> SafetyMode.CAUTION;
                case LOW, NONE -> session.crisisFlag() ? SafetyMode.CAUTION : SafetyMode.NORMAL;
            };
            if (risk != RiskLevel.NONE) listener.onSafety(risk);
            log.info("retrying reply session={} opening={}", sessionId, opening);
            generate(session, all, opening, mode, listener);
        } finally {
            locks.unlock(sessionId);
        }
    }

    private void generate(Session session, List<Message> sessionMessages, boolean opening, SafetyMode mode,
                          TurnListener listener) {
        ContextOutline.Budget budget = contextBuilder.budgetOf(sessionMessages);
        listener.onContext(budget.included(), budget.omitted());
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
