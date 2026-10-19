package com.mentalcare.counseling;

import com.mentalcare.ai.model.MemoryCandidateOutput;
import com.mentalcare.ai.model.SessionSummaryOutput;
import com.mentalcare.config.SettingsService;
import com.mentalcare.counseling.SessionService.CheckInInput;
import com.mentalcare.domain.Enums.MemoryStatus;
import com.mentalcare.domain.Enums.RiskLevel;
import com.mentalcare.domain.Message;
import com.mentalcare.domain.Session;
import com.mentalcare.memory.MemoryService;
import com.mentalcare.repository.MessageRepository;
import com.mentalcare.testsupport.FakeLlm;
import com.mentalcare.testsupport.FakeLlmConfig;
import com.mentalcare.testsupport.TestDirs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(FakeLlmConfig.class)
class DataDeletionIntegrationTest {

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        TestDirs.register(registry, "deletion");
    }

    static class Listener implements TurnListener {
        @Override public void onUserSaved(Message m) {}
        @Override public void onSafety(RiskLevel r) {}
        @Override public void onDelta(String t) {}
        @Override public void onDone(Message m) {}
        @Override public void onError(String m, String k) {}
    }

    @Autowired FakeLlm llm;
    @Autowired SessionService sessions;
    @Autowired CounselingService counseling;
    @Autowired SessionCompletionService completion;
    @Autowired DataDeletionService deletion;
    @Autowired MemoryService memories;
    @Autowired MessageRepository messageRepository;
    @Autowired SettingsService settings;
    @Autowired JdbcClient jdbc;

    private Session completedSession(String memoryText) {
        llm.reset();
        Session s = sessions.start(new CheckInInput(5, 5, 5, 5, 5, "note"));
        counseling.opening(s.id(), new Listener());
        counseling.reply(s.id(), "테스트 대화", new Listener());
        llm.summary = new SessionSummaryOutput("t", List.of("work"), List.of("e"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                List.of(new MemoryCandidateOutput(memoryText, "USER_STATED", "concern", "r", null)), List.of("work"));
        completion.complete(s.id());
        return sessions.get(s.id());
    }

    private long count(String table, long sessionId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE session_id = :id").param("id", sessionId).query(Long.class).single();
    }

    @Test
    void deletingOneSessionRemovesItsRowsFileAndSoleMemoriesOnly() throws Exception {
        Session a = completedSession("상사와의 갈등이 반복된다");
        Session b = completedSession("주말에 운동을 다시 시작하고 싶다");
        Path mdA = Path.of(sessions.detail(a.id()).summary().markdownPath());
        assertThat(mdA).exists();

        DataDeletionService.Result r = deletion.deleteSession(a.id());

        assertThat(r.sessionsDeleted()).isEqualTo(1);
        assertThat(r.filesDeleted()).isEqualTo(1);
        assertThat(mdA).doesNotExist();
        for (String t : List.of("messages", "weekly_checkins", "session_summaries", "session_tags", "safety_events", "memory_observations")) {
            assertThat(count(t, a.id())).as(t).isZero();
        }
        assertThatThrownBy(() -> sessions.get(a.id())).hasMessageContaining("not found");
        // B is intact
        assertThat(sessions.get(b.id())).isNotNull();
        assertThat(messageRepository.findBySession(b.id())).isNotEmpty();
        assertThat(memories.list(Set.of(MemoryStatus.CANDIDATE))).extracting(m -> m.content())
                .contains("주말에 운동을 다시 시작하고 싶다").doesNotContain("상사와의 갈등이 반복된다");
        // deleting again is a 404, not a crash
        assertThatThrownBy(() -> deletion.deleteSession(a.id())).hasMessageContaining("not found");
    }

    @Test
    void deletingAMiddleSessionRecountsSharedMemories() {
        // the same memory observed in three sessions (the model links it by id each time)
        Session a = completedSession("새 취미로 수영을 배우기 시작했다");
        long memoryId = memories.list(Set.of(MemoryStatus.CANDIDATE, MemoryStatus.ACTIVE)).stream()
                .filter(m -> m.content().equals("새 취미로 수영을 배우기 시작했다")).findFirst().orElseThrow().id();
        Session b = observeAgain(memoryId);
        Session c = observeAgain(memoryId);
        assertThat(memories.get(memoryId).observationCount()).isEqualTo(3);

        deletion.deleteSession(b.id()); // neither first nor last observation

        var m = memories.get(memoryId);
        assertThat(m.observationCount()).isEqualTo(2);
        assertThat(m.firstObservedSessionId()).isEqualTo(a.id());
        assertThat(m.lastObservedSessionId()).isEqualTo(c.id());
        assertThat(memories.observations(List.of(memoryId))).hasSize(2);
    }

    private Session observeAgain(long memoryId) {
        llm.reset();
        Session s = sessions.start(null);
        counseling.opening(s.id(), new Listener());
        counseling.reply(s.id(), "또 그 이야기", new Listener());
        llm.summary = new SessionSummaryOutput("t", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(),
                List.of(new MemoryCandidateOutput("부모님께 전화하는 약속이 다시 언급됨", "USER_STATED", "goal", "r", memoryId)), List.of());
        completion.complete(s.id());
        return sessions.get(s.id());
    }

    @Test
    void deleteAllRequiresConfirmationAndKeepsSettings() throws Exception {
        completedSession("x");
        completedSession("y");
        settings.updateGoals("목표 유지");
        Path markdownDir = Path.of(sessions.detail(sessions.list(10, 0).get(0).session().id()).summary().markdownPath()).getParent();

        assertThatThrownBy(() -> deletion.deleteAll("yes")).isInstanceOf(IllegalArgumentException.class);
        assertThat(sessions.count()).isGreaterThanOrEqualTo(2);

        DataDeletionService.Result r = deletion.deleteAll("DELETE");

        assertThat(r.sessionsDeleted()).isGreaterThanOrEqualTo(2);
        assertThat(sessions.count()).isZero();
        for (String t : List.of("messages", "weekly_checkins", "session_summaries", "session_tags", "tags", "safety_events",
                "memory_observations", "memories", "api_usage")) {
            assertThat(jdbc.sql("SELECT COUNT(*) FROM " + t).query(Long.class).single()).as(t).isZero();
        }
        try (var files = Files.list(markdownDir)) {
            assertThat(files.filter(p -> p.toString().endsWith(".md")).toList()).isEmpty();
        }
        assertThat(settings.goals()).isEqualTo("목표 유지");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM prompt_versions").query(Long.class).single()).isPositive();
        // app keeps working afterwards
        assertThat(sessions.start(null).id()).isPositive();
    }
}
