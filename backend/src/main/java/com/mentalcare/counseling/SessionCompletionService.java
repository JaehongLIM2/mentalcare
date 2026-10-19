package com.mentalcare.counseling;

import com.mentalcare.ai.LlmClient;
import com.mentalcare.ai.LlmResult;
import com.mentalcare.ai.model.SessionSummaryOutput;
import com.mentalcare.common.ConflictException;
import com.mentalcare.common.Dates;
import com.mentalcare.domain.Enums.MemoryKind;
import com.mentalcare.domain.Enums.MessageRole;
import com.mentalcare.domain.Enums.MessageStatus;
import com.mentalcare.domain.Memory;
import com.mentalcare.domain.Message;
import com.mentalcare.domain.Session;
import com.mentalcare.domain.SessionSummary;
import com.mentalcare.domain.WeeklyCheckIn;
import com.mentalcare.export.MarkdownExporter;
import com.mentalcare.memory.MemoryService;
import com.mentalcare.prompt.PromptLoader;
import com.mentalcare.repository.CheckInRepository;
import com.mentalcare.repository.MessageRepository;
import com.mentalcare.repository.SessionRepository;
import com.mentalcare.repository.SummaryRepository;
import com.mentalcare.repository.TagRepository;
import com.mentalcare.usage.UsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ends a session: structured summary (utility model) → summary row + tags + memory candidates
 * → Markdown archive → COMPLETED. If the summary request fails the session stays ACTIVE so the user
 * can retry; a Markdown failure does not undo the completion.
 */
@Service
public class SessionCompletionService {

    private static final Logger log = LoggerFactory.getLogger(SessionCompletionService.class);
    private static final int MAX_TAGS = 5;

    public record CompletionResult(Session session, SessionSummary summary, List<String> tags,
                                   MemoryService.IngestResult memory, String warning) {}

    private final SessionService sessionService;
    private final SessionRepository sessions;
    private final MessageRepository messages;
    private final CheckInRepository checkIns;
    private final SummaryRepository summaries;
    private final TagRepository tags;
    private final MemoryService memoryService;
    private final MarkdownExporter markdown;
    private final PromptLoader prompts;
    private final LlmClient llm;
    private final UsageRecorder usage;
    private final TransactionTemplate tx;
    private final com.mentalcare.ai.ModelCatalogService models;

    public SessionCompletionService(SessionService sessionService, SessionRepository sessions,
                                    MessageRepository messages, CheckInRepository checkIns,
                                    SummaryRepository summaries, TagRepository tags, MemoryService memoryService,
                                    MarkdownExporter markdown, PromptLoader prompts, LlmClient llm,
                                    UsageRecorder usage, TransactionTemplate tx,
                                    com.mentalcare.ai.ModelCatalogService models) {
        this.models = models;
        this.sessionService = sessionService;
        this.sessions = sessions;
        this.messages = messages;
        this.checkIns = checkIns;
        this.summaries = summaries;
        this.tags = tags;
        this.memoryService = memoryService;
        this.markdown = markdown;
        this.prompts = prompts;
        this.llm = llm;
        this.usage = usage;
        this.tx = tx;
    }

    public CompletionResult complete(long sessionId) {
        Session session = sessionService.get(sessionId);
        if (!session.isActive()) {
            throw new ConflictException("이미 종료된 상담입니다.", sessionId);
        }
        List<Message> transcript = messages.findBySession(sessionId);
        WeeklyCheckIn checkIn = checkIns.findBySession(sessionId).orElse(null);
        boolean hasUserMessages = transcript.stream().anyMatch(m -> m.role() == MessageRole.USER);

        if (!hasUserMessages) {
            // Nothing to summarize: close quietly without calling the model.
            sessions.markCompleted(sessionId, "(대화 없음)", Instant.now());
            return new CompletionResult(sessionService.get(sessionId), null, List.of(),
                    new MemoryService.IngestResult(0, 0, 0), null);
        }

        // 1. Structured summary (may throw LlmException → session stays ACTIVE).
        PromptLoader.LoadedPrompt prompt = prompts.load(PromptLoader.SESSION_SUMMARY);
        List<Memory> existingMemories = memoryService.memoriesForDeduplication();
        LlmResult<SessionSummaryOutput> result = llm.generateSessionSummary(
                models.utilityModel(), prompt.content(), buildTranscript(session, checkIn, transcript, existingMemories));
        usage.record(UsageRecorder.SUMMARY, result.usage(), sessionId);
        SessionSummaryOutput output = result.value();
        List<String> normalizedTags = normalizeTags(output.tags());
        String title = output.title() == null || output.title().isBlank() ? Dates.isoDate(session.startedAt()) : output.title().trim();

        // 2. Persist summary, tags, memories and completion atomically.
        record Persisted(SessionSummary summary, MemoryService.IngestResult memory) {}
        Persisted persisted = tx.execute(status -> {
            SessionSummary summary = summaries.insert(sessionId, output, result.usage().model(), prompt.versionId());
            tags.replaceSessionTags(sessionId, normalizedTags);
            MemoryService.IngestResult ingest = memoryService.ingest(sessionId, output.memoryCandidates());
            sessions.markCompleted(sessionId, title, Instant.now());
            return new Persisted(summary, ingest);
        });

        // 3. Markdown archive (best effort).
        String warning = null;
        SessionSummary summary = persisted.summary();
        try {
            Path path = markdown.export(sessionService.get(sessionId), checkIn, output, normalizedTags);
            summaries.updateMarkdownPath(sessionId, path.toString());
            summary = summaries.findBySession(sessionId).orElse(summary);
        } catch (IOException | RuntimeException e) {
            log.warn("Markdown export failed for session {}: {}", sessionId, e.getClass().getSimpleName());
            warning = "상담은 저장되었지만 Markdown 파일 생성에 실패했습니다 (" + e.getClass().getSimpleName() + ").";
        }

        return new CompletionResult(sessionService.get(sessionId), summary, normalizedTags, persisted.memory(), warning);
    }

    /** Transcript sent to the summary model: date, check-in, existing memories (for dedup), conversation. */
    String buildTranscript(Session session, WeeklyCheckIn checkIn, List<Message> transcript, List<Memory> existing) {
        StringBuilder sb = new StringBuilder();
        sb.append("상담 날짜: ").append(Dates.isoDate(session.startedAt())).append("\n");
        if (checkIn != null && !checkIn.isEmpty()) {
            sb.append("체크인: ");
            if (checkIn.mood() != null) sb.append("기분 ").append(checkIn.mood()).append("/10 ");
            if (checkIn.stress() != null) sb.append("스트레스 ").append(checkIn.stress()).append("/10 ");
            if (checkIn.anxiety() != null) sb.append("불안 ").append(checkIn.anxiety()).append("/10 ");
            if (checkIn.energy() != null) sb.append("에너지 ").append(checkIn.energy()).append("/10 ");
            if (checkIn.sleep() != null) sb.append("수면 ").append(checkIn.sleep()).append("/10 ");
            sb.append("\n");
            if (checkIn.note() != null && !checkIn.note().isBlank()) sb.append("체크인 메모: ").append(checkIn.note()).append("\n");
        }
        sb.append("\n## 기존 기억 목록 (중복 후보는 matchesExistingMemoryId로 연결)\n");
        if (existing.isEmpty()) {
            sb.append("(없음)\n");
        } else {
            for (Memory m : existing) {
                sb.append("- id=").append(m.id()).append(" [").append(m.status()).append(", ")
                        .append(m.kind() == MemoryKind.USER_STATED ? "사용자가 말함" : "AI 관찰").append("] ")
                        .append(m.content()).append("\n");
            }
        }
        sb.append("\n## 상담 대화 전체\n\n");
        for (Message m : transcript) {
            if (m.status() == MessageStatus.FAILED || m.content().isBlank()) continue;
            sb.append(m.role() == MessageRole.USER ? "[사용자] " : "[상담자] ").append(m.content().trim()).append("\n\n");
        }
        return sb.toString();
    }

    static List<String> normalizeTags(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return List.of();
        for (String t : raw) {
            if (t == null) continue;
            String slug = t.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-").replaceAll("[^a-z0-9-]", "");
            if (!slug.isEmpty()) out.add(slug);
            if (out.size() >= MAX_TAGS) break;
        }
        return List.copyOf(out);
    }
}
