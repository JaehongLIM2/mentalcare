package com.mentalcare.memory;

import com.mentalcare.ai.model.SessionSummaryOutput;
import com.mentalcare.common.ConflictException;
import com.mentalcare.common.Dates;
import com.mentalcare.counseling.SessionLocks;
import com.mentalcare.domain.Enums.SessionStatus;
import com.mentalcare.domain.Memory;
import com.mentalcare.domain.Session;
import com.mentalcare.domain.SessionSummary;
import com.mentalcare.repository.MemoryRepository;
import com.mentalcare.repository.SessionRepository;
import com.mentalcare.repository.SummaryRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Suggestions for the "add a memory yourself" form, taken from what the user said in recent sessions
 * (summary fields keyEvents and userThoughts; AI interpretations are not used). No model call: the
 * sentences come straight from the stored summaries on this PC. Sentences that an existing memory
 * already covers, or that the user dismissed, are left out.
 */
@Service
public class MemorySuggestionService {

    static final int SESSIONS_SCANNED = 6;
    /** Share of a suggestion's character pairs already present in one memory above which it counts as known. */
    static final double KNOWN_THRESHOLD = 0.6;

    public record Suggestion(String key, String text, String category, String source, long sessionId, String sessionDate,
                             String sessionTitle) {}

    private final SessionRepository sessions;
    private final SummaryRepository summaries;
    private final MemoryRepository memories;
    private final JdbcClient jdbc;
    private final SessionLocks locks;

    public MemorySuggestionService(SessionRepository sessions, SummaryRepository summaries, MemoryRepository memories,
                                   JdbcClient jdbc, SessionLocks locks) {
        this.sessions = sessions;
        this.summaries = summaries;
        this.memories = memories;
        this.jdbc = jdbc;
        this.locks = locks;
    }

    public List<Suggestion> suggestions(int limit) {
        List<Session> recent = sessions.findAll(SESSIONS_SCANNED * 3, 0).stream()
                .filter(s -> s.status() == SessionStatus.COMPLETED).limit(SESSIONS_SCANNED).toList();
        Map<Long, SessionSummary> byId = summaries.findBySessions(recent.stream().map(Session::id).toList());
        List<String> known = memories.findAll().stream().map(Memory::content).map(MemoryService::normalize).toList();
        Set<String> dismissed = new HashSet<>(jdbc.sql("SELECT key FROM memory_suggestion_dismissals").query(String.class).list());

        Map<String, Suggestion> out = new LinkedHashMap<>(); // newest session first, no duplicates
        for (Session s : recent) {
            SessionSummary summary = byId.get(s.id());
            if (summary == null) continue;
            SessionSummaryOutput o = summary.output();
            add(out, o.keyEvents(), "event", "이야기한 사건", s, known, dismissed);
            add(out, o.userThoughts(), "other", "직접 말한 생각", s, known, dismissed);
            if (out.size() >= limit) break;
        }
        return out.values().stream().limit(limit).toList();
    }

    private void add(Map<String, Suggestion> out, List<String> items, String category, String source, Session s,
                     List<String> known, Set<String> dismissed) {
        if (items == null) return;
        for (String raw : items) {
            if (raw == null) continue;
            String text = raw.trim();
            if (text.length() < 8 || text.length() > 2000) continue;
            String key = key(text);
            if (out.containsKey(key) || dismissed.contains(key) || covered(MemoryService.normalize(text), known)) continue;
            out.put(key, new Suggestion(key, text, category, source, s.id(), Dates.isoDate(s.startedAt()), s.title()));
        }
    }

    /** Hides a suggestion for good. Only a hash of the sentence is stored. */
    public void dismiss(String key) {
        if (key == null || !key.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("잘못된 추천 키입니다.");
        if (!locks.tryLockGlobalRead()) {
            throw new ConflictException("데이터 삭제·복원 또는 종료가 진행 중입니다. 잠시 후 다시 시도하세요.", null);
        }
        try {
            jdbc.sql("INSERT OR IGNORE INTO memory_suggestion_dismissals (key, created_at) VALUES (:k, :now)")
                    .param("k", key).param("now", java.time.Instant.now().toString()).update();
        } finally {
            locks.unlockGlobalRead();
        }
    }

    static boolean covered(String text, List<String> known) {
        if (text.isEmpty()) return true;
        for (String k : known) {
            if (k.equals(text) || k.contains(text)) return true;
            if (containment(text, k) >= KNOWN_THRESHOLD) return true;
        }
        return false;
    }

    /** Share of {@code a}'s character bigrams that also occur in {@code b}. */
    static double containment(String a, String b) {
        Set<String> sa = bigrams(a), sb = bigrams(b);
        if (sa.isEmpty()) return 0;
        int hit = 0;
        for (String g : sa) if (sb.contains(g)) hit++;
        return (double) hit / sa.size();
    }

    private static Set<String> bigrams(String s) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) out.add(s.substring(i, i + 2));
        return out;
    }

    static String key(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(MemoryService.normalize(text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Used by tests and delete-all bookkeeping. */
    List<String> dismissedKeys() {
        return new ArrayList<>(jdbc.sql("SELECT key FROM memory_suggestion_dismissals").query(String.class).list());
    }
}
