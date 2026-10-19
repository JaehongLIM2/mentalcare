package com.mentalcare.counseling;

import com.mentalcare.common.ConflictException;
import com.mentalcare.common.NotFoundException;
import com.mentalcare.domain.Message;
import com.mentalcare.domain.PromptVersion;
import com.mentalcare.domain.Session;
import com.mentalcare.domain.SessionSummary;
import com.mentalcare.domain.WeeklyCheckIn;
import com.mentalcare.prompt.PromptLoader;
import com.mentalcare.repository.CheckInRepository;
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
                                String importedMarkdown) {}

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

    public SessionService(SessionRepository sessions, MessageRepository messages, CheckInRepository checkIns,
                          SummaryRepository summaries, TagRepository tags, PromptVersionRepository promptVersions,
                          PromptLoader prompts, com.mentalcare.repository.ImportedArchiveRepository archives,
                          SessionLocks locks, TransactionTemplate tx) {
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
                session.isImported() ? archives.rawMarkdown(id).orElse(null) : null);
    }

    public List<SessionListItem> list(int limit, int offset) {
        List<Session> page = sessions.findAll(limit, offset);
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
