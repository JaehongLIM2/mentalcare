package com.mentalcare.counseling;

import com.mentalcare.ai.BearerTokenProvider;
import com.mentalcare.common.Dates;
import com.mentalcare.config.AppProperties;
import com.mentalcare.config.SettingsService;
import com.mentalcare.domain.Enums.MemoryStatus;
import com.mentalcare.domain.Session;
import com.mentalcare.domain.SessionSummary;
import com.mentalcare.memory.MemoryService;
import com.mentalcare.repository.SessionRepository;
import com.mentalcare.repository.SummaryRepository;
import com.mentalcare.trend.TrendService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** Data for the Home screen. */
@Service
public class HomeService {

    public record HomeView(
            Long activeSessionId,
            Long lastSessionId,
            String lastSessionDate,
            String lastSessionTitle,
            Long daysSinceLast,
            String recommendedNextDate,
            boolean dueNow,
            List<String> lastNextSessionTopics,
            List<String> lastActionItems,
            List<TrendService.TrendPoint> recentCheckIns,
            String goals,
            long totalSessions,
            long activeMemories,
            long candidateMemories,
            String authMode,
            boolean aiReady,
            String aiUnavailableReason,
            boolean usesChatGptPlan
    ) {}

    private final SessionRepository sessions;
    private final SummaryRepository summaries;
    private final TrendService trends;
    private final MemoryService memories;
    private final SettingsService settings;
    private final AppProperties app;
    private final BearerTokenProvider tokens;

    public HomeService(SessionRepository sessions, SummaryRepository summaries, TrendService trends,
                       MemoryService memories, SettingsService settings, AppProperties app, BearerTokenProvider tokens) {
        this.sessions = sessions;
        this.summaries = summaries;
        this.trends = trends;
        this.memories = memories;
        this.settings = settings;
        this.app = app;
        this.tokens = tokens;
    }

    public HomeView view() {
        Optional<Session> active = sessions.findActive();
        Optional<Session> last = sessions.findRecentCompleted(1).stream().findFirst();
        Optional<SessionSummary> lastSummary = last.flatMap(s -> summaries.findBySession(s.id()));

        LocalDate today = LocalDate.now(Dates.zone());
        Long daysSince = null;
        String recommended = null;
        boolean due = last.isEmpty();
        if (last.isPresent()) {
            LocalDate lastDate = Dates.localDate(last.get().startedAt());
            daysSince = ChronoUnit.DAYS.between(lastDate, today);
            LocalDate next = lastDate.plusDays(app.recommendedIntervalDays());
            recommended = next.toString();
            due = !today.isBefore(next);
        }

        boolean ready = tokens.available();
        return new HomeView(
                active.map(Session::id).orElse(null),
                last.map(Session::id).orElse(null),
                last.map(s -> Dates.isoDate(s.startedAt())).orElse(null),
                last.map(Session::title).orElse(null),
                daysSince,
                recommended,
                due,
                lastSummary.map(s -> s.output().nextSessionTopics()).orElse(List.of()),
                lastSummary.map(s -> s.output().actionItems()).orElse(List.of()),
                trends.recent(6),
                settings.goals(),
                sessions.countCompleted(),
                memories.count(MemoryStatus.ACTIVE),
                memories.count(MemoryStatus.CANDIDATE),
                tokens.mode().name(),
                ready,
                ready ? null : tokens.unavailableReason(),
                tokens.usesChatGptPlan());
    }
}
