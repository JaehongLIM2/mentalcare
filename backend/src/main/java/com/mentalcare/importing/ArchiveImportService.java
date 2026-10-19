package com.mentalcare.importing;

import com.mentalcare.ai.model.MemoryCandidateOutput;
import com.mentalcare.ai.model.SessionSummaryOutput;
import com.mentalcare.common.Dates;
import com.mentalcare.domain.Session;
import com.mentalcare.domain.SessionSummary;
import com.mentalcare.importing.ArchiveMarkdownParser.MemoryCandidate;
import com.mentalcare.importing.ArchiveMarkdownParser.Parsed;
import com.mentalcare.memory.MemoryService;
import com.mentalcare.repository.ImportedArchiveRepository;
import com.mentalcare.repository.SessionRepository;
import com.mentalcare.repository.SummaryRepository;
import com.mentalcare.repository.TagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a "ChatGPT 상담 이관 기록" Markdown archive into an IMPORTED session: a structured summary
 * (facts kept apart from the archive's AI observations), CANDIDATE memories, and the archive's
 * core context that the counselor sees inside a data block. No model call is made.
 */
@Service
public class ArchiveImportService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveImportService.class);
    public static final long MAX_BYTES = 2L * 1024 * 1024;

    public record Preview(String title, String counseledAt, String mainTopics, int factCount, int eventCount,
                          int relationshipCount, int concernCount, int aiPatternCount, int memoryCandidateCount,
                          int coreContextChars, List<String> warnings) {}

    public record Result(long sessionId, String title, String date, int memoriesCreated, int memoriesMerged,
                         List<String> warnings) {}

    private final SessionRepository sessions;
    private final SummaryRepository summaries;
    private final TagRepository tags;
    private final ImportedArchiveRepository archives;
    private final MemoryService memoryService;
    private final TransactionTemplate tx;

    public ArchiveImportService(SessionRepository sessions, SummaryRepository summaries, TagRepository tags,
                                ImportedArchiveRepository archives, MemoryService memoryService, TransactionTemplate tx) {
        this.sessions = sessions;
        this.summaries = summaries;
        this.tags = tags;
        this.archives = archives;
        this.memoryService = memoryService;
        this.tx = tx;
    }

    public Preview preview(byte[] file) {
        Parsed p = parse(file);
        Map<String, List<String>> s = p.sections();
        return new Preview(p.title(), p.counseledAt() == null ? p.counseledAtRaw() : p.counseledAt().toString(),
                p.mainTopics(),
                s.get(ArchiveMarkdownParser.FACTS).size(), s.get(ArchiveMarkdownParser.EVENTS).size(),
                s.get(ArchiveMarkdownParser.RELATIONSHIPS).size(), s.get(ArchiveMarkdownParser.CONCERNS).size(),
                s.get(ArchiveMarkdownParser.AI_PATTERNS).size(), p.memoryCandidates().size(),
                p.coreContext().length(), p.warnings());
    }

    public Result importArchive(String sourceName, byte[] file) {
        Parsed p = parse(file);
        String markdown = new String(file, StandardCharsets.UTF_8);
        LocalDate date = p.counseledAt() != null ? p.counseledAt() : LocalDate.now(Dates.zone());
        Instant counseledAt = date.atTime(12, 0).atZone(Dates.zone()).toInstant();
        String title = (p.mainTopics() == null || p.mainTopics().isBlank())
                ? "ChatGPT 이관 기록" : "이관: " + limit(p.mainTopics(), 60);
        SessionSummaryOutput output = toSummary(p);
        List<MemoryCandidateOutput> candidates = p.memoryCandidates().stream().map(ArchiveImportService::toCandidate).toList();
        String safeName = sourceName == null ? "archive.md" : sourceName.replaceAll("[^\\w.\\-가-힣 ]", "_");

        record Persisted(Session session, MemoryService.IngestResult ingest) {}
        Persisted persisted = tx.execute(status -> {
            Session session = sessions.createImported(title, counseledAt);
            summaries.insert(session.id(), output, "imported", null);
            tags.replaceSessionTags(session.id(), List.of("imported"));
            archives.insert(session.id(), limit(safeName, 200), markdown);
            MemoryService.IngestResult ingest = memoryService.ingest(session.id(), candidates);
            return new Persisted(session, ingest);
        });
        log.info("archive imported session={} candidates={} created={} merged={}", persisted.session().id(),
                candidates.size(), persisted.ingest().created(), persisted.ingest().merged());
        return new Result(persisted.session().id(), title, date.toString(),
                persisted.ingest().created(), persisted.ingest().merged(), p.warnings());
    }

    /** Core context of every imported archive, oldest first, for the context builder. */
    public List<ImportedContext> importedContexts() {
        List<ImportedContext> out = new ArrayList<>();
        for (Session s : sessions.findImported()) {
            summaries.findBySession(s.id()).map(SessionSummary::output)
                    .filter(o -> o.importedContext() != null && !o.importedContext().isBlank())
                    .ifPresent(o -> out.add(new ImportedContext(s.id(), Dates.isoDate(s.startedAt()), s.title(), o.importedContext())));
        }
        return out;
    }

    public record ImportedContext(long sessionId, String date, String title, String text) {}

    // ------------------------------------------------------------------ mapping

    private static Parsed parse(byte[] file) {
        if (file == null || file.length == 0) throw new IllegalArgumentException("파일이 비어 있습니다.");
        if (file.length > MAX_BYTES) throw new IllegalArgumentException("파일이 너무 큽니다 (최대 2MB).");
        return ArchiveMarkdownParser.parse(new String(file, StandardCharsets.UTF_8));
    }

    static SessionSummaryOutput toSummary(Parsed p) {
        Map<String, List<String>> s = p.sections();
        List<String> topics = new ArrayList<>();
        if (p.mainTopics() != null && !p.mainTopics().isBlank()) {
            for (String t : p.mainTopics().split("[,/·]")) if (!t.isBlank()) topics.add(t.trim());
        }
        List<String> keyEvents = new ArrayList<>(s.get(ArchiveMarkdownParser.FACTS));
        keyEvents.addAll(prefix("사건: ", s.get(ArchiveMarkdownParser.EVENTS)));
        keyEvents.addAll(prefix("관계: ", s.get(ArchiveMarkdownParser.RELATIONSHIPS)));
        keyEvents.addAll(prefix("상황: ", s.get(ArchiveMarkdownParser.SITUATION)));
        List<String> userThoughts = new ArrayList<>(s.get(ArchiveMarkdownParser.THOUGHTS));
        userThoughts.addAll(prefix("가치관: ", s.get(ArchiveMarkdownParser.VALUES)));
        userThoughts.addAll(prefix("고민: ", s.get(ArchiveMarkdownParser.CONCERNS)));
        List<String> discoveries = new ArrayList<>(prefix("생각이 바뀐 부분: ", s.get(ArchiveMarkdownParser.CHANGED)));
        List<String> next = new ArrayList<>(s.get(ArchiveMarkdownParser.FOLLOW_UP));
        next.addAll(prefix("미해결: ", s.get(ArchiveMarkdownParser.UNRESOLVED)));
        next.addAll(prefix("갱신 필요: ", s.get(ArchiveMarkdownParser.OUTDATED)));
        return new SessionSummaryOutput(
                p.mainTopics() == null ? "ChatGPT 이관 기록" : p.mainTopics(),
                topics, keyEvents, s.get(ArchiveMarkdownParser.EMOTIONS), userThoughts,
                s.get(ArchiveMarkdownParser.AI_PATTERNS), discoveries,
                s.get(ArchiveMarkdownParser.DECISIONS), next, List.of(), List.of("imported"),
                p.coreContext().isBlank() ? null : p.coreContext());
    }

    static MemoryCandidateOutput toCandidate(MemoryCandidate c) {
        String category = switch (c.type()) {
            case "RELATIONSHIP" -> "relationship";
            case "EVENT" -> "event";
            case "CONCERN" -> "concern";
            case "GOAL" -> "goal";
            case "PATTERN" -> "pattern";
            case "VALUE" -> "change";
            default -> "other";
        };
        StringBuilder reason = new StringBuilder();
        if (!c.whyItMatters().isBlank()) reason.append(c.whyItMatters());
        if (!c.timeContext().isBlank()) reason.append(reason.isEmpty() ? "" : " · ").append("시점: ").append(c.timeContext());
        if (c.mayBecomeOutdated()) reason.append(reason.isEmpty() ? "" : " · ").append("시간이 지나 달라졌을 수 있음");
        String kind = "USER_STATED".equals(c.evidence()) ? "USER_STATED" : "AI_OBSERVED";
        return new MemoryCandidateOutput(c.content(), kind, category, reason.toString(), null);
    }

    private static List<String> prefix(String prefix, List<String> items) {
        return items.stream().map(i -> prefix + i).toList();
    }

    private static String limit(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    static String slug(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9가-힣]+", "-");
    }
}
