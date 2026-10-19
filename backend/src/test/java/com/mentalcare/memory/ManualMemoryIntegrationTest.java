package com.mentalcare.memory;

import com.mentalcare.ai.model.MemoryCandidateOutput;
import com.mentalcare.ai.model.SessionSummaryOutput;
import com.mentalcare.common.ConflictException;
import com.mentalcare.counseling.CounselingService;
import com.mentalcare.counseling.DataDeletionService;
import com.mentalcare.counseling.SessionCompletionService;
import com.mentalcare.counseling.SessionLocks;
import com.mentalcare.counseling.SessionService;
import com.mentalcare.counseling.TurnListener;
import com.mentalcare.domain.Enums.MemoryKind;
import com.mentalcare.domain.Enums.MemoryStatus;
import com.mentalcare.domain.Enums.RiskLevel;
import com.mentalcare.domain.Memory;
import com.mentalcare.domain.Message;
import com.mentalcare.domain.Session;
import com.mentalcare.prompt.PromptLoader;
import com.mentalcare.testsupport.FakeLlm;
import com.mentalcare.testsupport.FakeLlmConfig;
import com.mentalcare.testsupport.TestDirs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Memories the user writes by hand, and the summary prompt that should extract lasting facts. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeLlmConfig.class)
class ManualMemoryIntegrationTest {

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        TestDirs.register(registry, "manual-memory");
    }

    static class Listener implements TurnListener {
        @Override public void onUserSaved(Message m) {}
        @Override public void onSafety(RiskLevel r) {}
        @Override public void onDelta(String t) {}
        @Override public void onDone(Message m) {}
        @Override public void onError(String m, String k) {}
    }

    @Autowired MemoryService memories;
    @Autowired FakeLlm llm;
    @Autowired SessionService sessions;
    @Autowired CounselingService counseling;
    @Autowired SessionCompletionService completion;
    @Autowired DataDeletionService deletion;
    @Autowired SessionLocks locks;
    @Autowired PromptLoader prompts;
    @Autowired MockMvc mvc;

    @BeforeEach
    void reset() {
        llm.reset();
        sessions.active().ifPresent(s -> {
            llm.summary = FakeLlm.emptySummary();
            completion.complete(s.id());
            llm.reset();
        });
    }

    @Test
    void manualMemoryIsActiveUserStatedAndReachesTheNextTurn() {
        Memory m = memories.addManual("  고양이 보리(4살)가 지난달 치과 치료를 받았다.  ", "event");

        assertThat(m.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(m.kind()).isEqualTo(MemoryKind.USER_STATED);
        assertThat(m.manual()).isTrue();
        assertThat(m.observationCount()).isZero();
        assertThat(m.content()).isEqualTo("고양이 보리(4살)가 지난달 치과 치료를 받았다.");

        Session s = sessions.start(null);
        counseling.opening(s.id(), new Listener());
        assertThat(llm.contexts.get(0).instructions()).contains("[사용자가 말함] 고양이 보리(4살)");
        completion.complete(s.id());
    }

    @Test
    void invalidOrDuplicateManualMemoriesAreRefused() {
        memories.addManual("매일 30분 걷기를 지키고 싶다.", "goal");
        assertThatThrownBy(() -> memories.addManual("매일 30분 걷기를 지키고 싶다", "goal"))
                .isInstanceOf(ConflictException.class); // same text up to punctuation/spacing
        assertThatThrownBy(() -> memories.addManual("   ", "goal")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> memories.addManual("x", "secret-category")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> memories.addManual("가".repeat(2001), "other")).isInstanceOf(IllegalArgumentException.class);
        // refused while delete-all / restore holds the maintenance lock
        assertThat(locks.tryLockAll()).isTrue();
        try {
            assertThatThrownBy(() -> memories.addManual("유지보수 중 추가", "other")).isInstanceOf(ConflictException.class);
        } finally {
            locks.unlockAll();
        }
    }

    @Test
    void deletingASessionThatObservedAManualMemoryKeepsIt() {
        Memory manual = memories.addManual("오랜 친구와 매달 만나는 것이 큰 힘이 된다.", "relationship");
        Session s = sessions.start(null);
        counseling.opening(s.id(), new Listener());
        counseling.reply(s.id(), "이번 달에도 오랜 친구를 만났어요", new Listener());
        llm.summary = new SessionSummaryOutput("주말", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(
                        new MemoryCandidateOutput("오랜 친구와 매달 만나는 것이 큰 힘이 된다", "USER_STATED", "relationship", "반복", manual.id()),
                        new MemoryCandidateOutput("이번 상담에서만 나온 새 기억 후보 문장", "USER_STATED", "event", "r", null)),
                List.of("relationship"));
        completion.complete(s.id());
        assertThat(memories.get(manual.id()).observationCount()).isEqualTo(1);
        assertThat(memories.get(manual.id()).firstObservedSessionId()).isEqualTo(s.id());
        long sessionOnly = memories.list(java.util.EnumSet.of(MemoryStatus.CANDIDATE)).stream()
                .filter(x -> x.content().startsWith("이번 상담에서만")).findFirst().orElseThrow().id();

        deletion.deleteSession(s.id());

        Memory kept = memories.get(manual.id());
        assertThat(kept.observationCount()).isZero();
        assertThat(kept.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThatThrownBy(() -> memories.get(sessionOnly)).hasMessageContaining("not found"); // summary-only memory goes
    }

    @Test
    void httpEndpointCreatesAndValidates() throws Exception {
        mvc.perform(post("/api/memories").header("Host", "127.0.0.1:8080").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"매주 일요일 저녁에 상담을 한다\",\"category\":\"goal\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.kind").value("USER_STATED"))
                .andExpect(jsonPath("$.source").value("MANUAL"));
        mvc.perform(post("/api/memories").header("Host", "127.0.0.1:8080").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/memories").header("Host", "127.0.0.1:8080").contentType(MediaType.APPLICATION_JSON)
                        .header("Sec-Fetch-Site", "cross-site").content("{\"content\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void openingKnowsHowLongAgoTheLastSessionWasInsteadOfAssumingAWeek() {
        Session first = sessions.start(null);
        counseling.opening(first.id(), new Listener());
        completion.complete(first.id());
        llm.reset();

        Session sameDay = sessions.start(null);
        counseling.opening(sameDay.id(), new Listener());

        String today = com.mentalcare.common.Dates.isoDate(first.startedAt());
        var ctx = llm.contexts.get(0);
        assertThat(ctx.instructions()).contains("지난 상담과의 간격: " + today + " (같은 날, 0일 전)");
        String cue = ctx.messages().get(ctx.messages().size() - 1).content();
        assertThat(cue).contains("지난 상담과의 간격").contains("같은 날(0일)").contains("습관적으로 쓰지 않습니다");
        assertThat(prompts.load(PromptLoader.OPENING_CUE).version()).isEqualTo("v2");
        completion.complete(sameDay.id());
    }

    @Test
    void gapPhrasesFollowTheNumberOfDays() {
        assertThat(com.mentalcare.context.CounselingContextBuilderAccess.gapPhrase(0)).isEqualTo("같은 날, 0일 전");
        assertThat(com.mentalcare.context.CounselingContextBuilderAccess.gapPhrase(2)).isEqualTo("2일 전, 며칠 사이");
        assertThat(com.mentalcare.context.CounselingContextBuilderAccess.gapPhrase(7)).isEqualTo("7일 전, 약 한 주");
        assertThat(com.mentalcare.context.CounselingContextBuilderAccess.gapPhrase(30)).isEqualTo("30일 전, 오랜만");
    }

    @Test
    void summaryPromptV2AsksForLastingPeoplePetsAndOpenMatters() {
        PromptLoader.LoadedPrompt p = prompts.load(PromptLoader.SESSION_SUMMARY);
        assertThat(p.version()).isEqualTo("v3"); // v3 = v2 + attachment rules
        assertThat(p.content()).contains("반드시 후보로 올리는 것").contains("반려동물").contains("진행 중인 사안")
                .contains("가치관").contains("기존 기억 목록이 \"(없음)\"이면");
    }
}
