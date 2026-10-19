package com.mentalcare.memory;

import com.mentalcare.ai.model.MemoryCandidateOutput;
import com.mentalcare.common.NotFoundException;
import com.mentalcare.config.AppProperties;
import com.mentalcare.domain.Enums.MemoryKind;
import com.mentalcare.domain.Enums.MemoryStatus;
import com.mentalcare.domain.Memory;
import com.mentalcare.domain.MemoryObservation;
import com.mentalcare.repository.MemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Long-term memory lifecycle: CANDIDATE → ACTIVE → ARCHIVED.
 * New observations never become ACTIVE immediately; they are promoted after repeated observation
 * (configurable thresholds) or by the user on the Memory screen.
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    public record IngestResult(int created, int merged, int activated) {}

    private final MemoryRepository repository;
    private final AppProperties app;

    /** Categories the UI offers and the summary prompt uses. */
    public static final java.util.Set<String> CATEGORIES =
            java.util.Set.of("concern", "pattern", "relationship", "goal", "event", "change", "other");

    private final com.mentalcare.counseling.SessionLocks locks;

    public MemoryService(MemoryRepository repository, AppProperties app, com.mentalcare.counseling.SessionLocks locks) {
        this.repository = repository;
        this.app = app;
        this.locks = locks;
    }

    /**
     * Adds a memory written by the user. It is what the user said about themselves, so it is USER_STATED and
     * ACTIVE immediately (included in the next turn's context). The same text twice is refused.
     */
    public Memory addManual(String content, String category) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("기억 내용을 입력하세요.");
        if (text.length() > 2000) throw new IllegalArgumentException("기억은 2000자 이내로 적어 주세요.");
        String cat = category == null || category.isBlank() ? "other" : category.trim();
        if (!CATEGORIES.contains(cat)) throw new IllegalArgumentException("알 수 없는 분류입니다: " + cat);
        if (!locks.tryLockGlobalRead()) {
            throw new com.mentalcare.common.ConflictException("데이터 삭제·복원 또는 종료가 진행 중입니다. 잠시 후 다시 시도하세요.", null);
        }
        try {
            String key = normalizeForDuplicate(text);
            for (Memory m : repository.findByStatuses(List.of(MemoryStatus.CANDIDATE, MemoryStatus.ACTIVE))) {
                if (normalizeForDuplicate(m.content()).equals(key)) {
                    throw new com.mentalcare.common.ConflictException("같은 내용의 기억이 이미 있습니다.", m.id());
                }
            }
            return repository.insertManual(text, cat);
        } finally {
            locks.unlockGlobalRead();
        }
    }

    static String normalizeForDuplicate(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", "");
    }

    public List<Memory> activeMemories() {
        return repository.findByStatuses(List.of(MemoryStatus.ACTIVE));
    }

    /** Memories shown to the summary model so it can link duplicates instead of creating new ones. */
    public List<Memory> memoriesForDeduplication() {
        return repository.findByStatuses(List.of(MemoryStatus.ACTIVE, MemoryStatus.CANDIDATE));
    }

    public List<Memory> list(Set<MemoryStatus> statuses) {
        return statuses.isEmpty() ? repository.findAll() : repository.findByStatuses(List.copyOf(statuses));
    }

    public Memory get(long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Memory " + id + " not found"));
    }

    public List<MemoryObservation> observations(List<Long> memoryIds) {
        return repository.observationsOf(memoryIds);
    }

    public long count(MemoryStatus status) {
        return repository.countByStatus(status);
    }

    @Transactional
    public Memory update(long id, String content, String category, MemoryStatus status) {
        Memory existing = get(id);
        String newContent = content == null || content.isBlank() ? existing.content() : content.trim();
        String newCategory = category == null || category.isBlank() ? existing.category() : category.trim();
        MemoryStatus newStatus = status == null ? existing.status() : status;
        repository.update(id, newContent, newCategory, newStatus);
        return get(id);
    }

    /**
     * Merges summary candidates into the memory store for {@code sessionId}.
     * A candidate that matches an existing memory (by model-provided id or by text similarity)
     * counts as a new observation of that memory; otherwise a new CANDIDATE is created.
     */
    @Transactional
    public IngestResult ingest(long sessionId, List<MemoryCandidateOutput> candidates) {
        int created = 0, merged = 0, activated = 0;
        List<Memory> existing = memoriesForDeduplication();
        Set<Long> touched = new HashSet<>();

        for (MemoryCandidateOutput c : candidates) {
            if (c == null || c.content() == null || c.content().isBlank()) continue;
            String content = c.content().trim();
            MemoryKind kind = parseKind(c.kind());
            String category = c.category() == null || c.category().isBlank() ? "other" : c.category().trim();

            Optional<Memory> match = findById(existing, c.matchesExistingMemoryId())
                    .or(() -> findSimilar(existing, content));

            if (match.isPresent() && !touched.contains(match.get().id())) {
                Memory m = match.get();
                touched.add(m.id());
                boolean fresh = repository.addObservation(m.id(), sessionId, c.reason());
                if (fresh) {
                    merged++;
                    if (shouldActivate(repository.findById(m.id()).orElse(m))) {
                        repository.updateStatus(m.id(), MemoryStatus.ACTIVE);
                        activated++;
                    }
                }
            } else if (match.isEmpty()) {
                Memory m = repository.insert(content, kind, category, MemoryStatus.CANDIDATE, sessionId, c.reason());
                existing.add(m);
                touched.add(m.id());
                created++;
            }
        }
        log.info("memory ingest session={} created={} merged={} activated={}", sessionId, created, merged, activated);
        return new IngestResult(created, merged, activated);
    }

    private boolean shouldActivate(Memory m) {
        if (m.status() != MemoryStatus.CANDIDATE) return false;
        int threshold = m.kind() == MemoryKind.USER_STATED
                ? app.memory().autoActivateUserStated()
                : app.memory().autoActivateAiObserved();
        return m.observationCount() >= threshold;
    }

    private static MemoryKind parseKind(String kind) {
        try {
            return kind == null ? MemoryKind.AI_OBSERVED : MemoryKind.valueOf(kind.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MemoryKind.AI_OBSERVED;
        }
    }

    private static Optional<Memory> findById(List<Memory> memories, Long id) {
        if (id == null) return Optional.empty();
        return memories.stream().filter(m -> m.id() == id).findFirst();
    }

    static Optional<Memory> findSimilar(List<Memory> memories, String content) {
        String target = normalize(content);
        if (target.isEmpty()) return Optional.empty();
        Memory best = null;
        double bestScore = 0;
        for (Memory m : memories) {
            String other = normalize(m.content());
            if (other.equals(target)) return Optional.of(m);
            double score = bigramJaccard(target, other);
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        return bestScore >= 0.72 ? Optional.ofNullable(best) : Optional.empty();
    }

    static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[\\s\\p{Punct}]+", "");
    }

    static double bigramJaccard(String a, String b) {
        Set<String> sa = bigrams(a), sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        return (double) inter.size() / union.size();
    }

    private static Set<String> bigrams(String s) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) out.add(s.substring(i, i + 2));
        return out;
    }
}
