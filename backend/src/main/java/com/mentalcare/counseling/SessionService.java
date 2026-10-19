package com.mentalcare.counseling;

import com.mentalcare.common.ConflictException;
import com.mentalcare.common.NotFoundException;
import com.mentalcare.domain.Draft;
import com.mentalcare.domain.Message;
import com.mentalcare.domain.PromptVersion;
import com.mentalcare.domain.Session;
import com.mentalcare.domain.SessionSummary;
import com.mentalcare.domain.WeeklyCheckIn;
import com.mentalcare.prompt.PromptLoader;
import com.mentalcare.repository.CheckInRepository;
import com.mentalcare.repository.DraftRepository;
import com.mentalcare.repository.MessageRepository;
import com.mentalcare.repository.PromptVersionRepository;
import com.mentalcare.repository.SessionRepository;
import com.mentalcare.repository.SummaryRepository;
import com.mentalcare.repository.TagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Session lifecycle except the AI parts: create with check-in, resume, list, detail. */
@Service
public class SessionService {

    public record CheckInInput(Integer mood, Integer stress, Integer anxiety, Integer energy, Integer sleep, String note) {
        public boolean isEmpty() {
            return mood == null && stress == null && anxiety == null && energy == null && sleep == null
                    && (note == null || note.isBlank());
        }
    }

    public record SessionDetail(Session session, WeeklyCheckIn checkIn, List<Message> messages,
                                SessionSummary summary, List<String> tags, PromptVersion promptVersion,
                                String importedMarkdown, Draft draft) {}

    public record SessionListItem(Session session, SessionSummary summary, List<String> tags, WeeklyCheckIn checkIn) {}

    private final SessionRepository sessions;
    private final MessageRepository messages;
    private final CheckInRepository checkIns;
    private final SummaryRepository summaries;
    private final TagRepository tags;
    private final PromptVersionRepository promptVersions;
    private final PromptLoader prompts;
    private final com.mentalcare.repository.ImportedArchiveRepository archives;
    private final SessionLocks locks;
    private final TransactionTemplate tx;
    private final DraftRepository drafts;

    public SessionService(SessionRepository sessions, MessageRepository messages, CheckInRepository checkIns,
                          SummaryRepository summaries, TagRepository tags, PromptVersionRepository promptVersions,
                          PromptLoader prompts, com.mentalcare.repository.ImportedArchiveRepository archives,
                          SessionLocks locks, TransactionTemplate tx, DraftRepository drafts) {
        this.drafts = drafts;
        this.archives = archives;
        this.locks = locks;
        this.tx = tx;
        this.sessions = sessions;
        this.messages = messages;
        this.checkIns = checkIns;
        this.summaries = summaries;
        this.tags = tags;
        this.promptVersions = promptVersions;
        this.prompts = prompts;
    }

    /** Creates a session inside the global lock so a delete-all cannot run between create and commit. */
    public Session start(CheckInInput checkIn) {
        validate(checkIn);
        if (!locks.tryLockGlobalRead()) {
            throw new ConflictException("데이터 삭제가 진행 중입니다. 잠시 후 다시 시도하세요.", null);
        }
        try {
            return tx.execute(status -> {
                Optional<Session> active = sessions.findActive();
                if (active.isPresent()) {
                    throw new ConflictException("진행 중인 상담이 있습니다. 먼저 이어서 진행하거나 종료하세요.", active.get().id());
                }
                long promptVersionId = prompts.load(PromptLoader.COUNSELOR_SYSTEM).versionId();
                Session session = sessions.create(promptVersionId);
                if (checkIn != null && !checkIn.isEmpty()) {
                    checkIns.insert(session.id(), checkIn.mood(), checkIn.stress(), checkIn.anxiety(),
                            checkIn.energy(), checkIn.sleep(), blankToNull(checkIn.note()));
                }
                return session;
            });
        } finally {
            locks.unlockGlobalRead();
        }
    }

    public Optional<Session> active() {
        return sessions.findActive();
    }

    public Session get(long id) {
        return sessions.findById(id).orElseThrow(() -> new NotFoundException("Session " + id + " not found"));
    }

    public SessionDetail detail(long id) {
        Session session = get(id);
        PromptVersion pv = session.promptVersionId() == null ? null
                : promptVersions.findById(session.promptVersionId()).orElse(null);
        return new SessionDetail(session,
                checkIns.findBySession(id).orElse(null),
                messages.findBySession(id),
                summaries.findBySession(id).orElse(null),
                tags.findBySession(id),
                pv,
                session.isImported() ? archives.rawMarkdown(id).orElse(null) : null,
                session.isActive() ? drafts.find(id).orElse(null) : null);
    }

    /** Stores the composer text of an active session; blank text removes the draft. */
    public void saveDraft(long id, String content) {
        saveDraft(id, content, null);
    }

    /**
     * Stores the composer text of an active session at revision {@code rev} (server clock when null).
     * Blank text hides the draft. A request older than the stored revision is ignored, not an error.
     *
     * @return true when written, false when it was stale
     */
    public boolean saveDraft(long id, String content, Long rev) {
        if (!locks.tryLockGlobalRead()) {
            throw new ConflictException("데이터 삭제·복원 또는 종료가 진행 중이라 초안을 저장하지 못했습니다.", id);
        }
        try {
            String text = content == null || content.isBlank() ? "" : content;
            if (drafts.saveIfActive(id, text, rev == null ? DraftRevisions.next() : rev) == 1) return true;
            Session session = get(id); // NotFound when it is gone
            if (!session.isActive()) {
                throw new ConflictException("종료된 상담에는 초안을 저장할 수 없습니다.", session.id());
            }
            return false; // a newer revision is already stored
        } finally {
            locks.unlockGlobalRead();
        }
    }

    public record SessionPage(List<SessionListItem> items, long total) {}

    /** History list with optional keyword / date / tag filter. */
    public SessionPage search(SessionRepository.Filter filter, int limit, int offset) {
        boolean plain = filter == null || filter.isEmpty();
        List<Session> page = plain ? sessions.findAll(limit, offset) : sessions.search(filter, limit, offset);
        long total = plain ? sessions.count() : sessions.countMatching(filter);
        return new SessionPage(items(page), total);
    }

    public List<SessionListItem> list(int limit, int offset) {
        return items(sessions.findAll(limit, offset));
    }

    private List<SessionListItem> items(List<Session> page) {
        List<Long> ids = page.stream().map(Session::id).toList();
        Map<Long, SessionSummary> summaryById = summaries.findBySessions(ids);
        Map<Long, List<String>> tagsById = tags.findBySessions(ids);
        return page.stream().map(s -> new SessionListItem(s, summaryById.get(s.id()),
                tagsById.getOrDefault(s.id(), List.of()), checkIns.findBySession(s.id()).orElse(null))).toList();
    }

    public long count() {
        return sessions.count();
    }

    private static void validate(CheckInInput c) {
        if (c == null) return;
        for (Integer v : new Integer[]{c.mood(), c.stress(), c.anxiety(), c.energy(), c.sleep()}) {
            if (v != null && (v < 1 || v > 10)) {
                throw new IllegalArgumentException("체크인 점수는 1~10 사이여야 합니다.");
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
