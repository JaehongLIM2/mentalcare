package com.mentalcare.memory;

import com.mentalcare.ai.model.SessionSummaryOutput;
import com.mentalcare.counseling.CounselingService;
import com.mentalcare.counseling.DataDeletionService;
import com.mentalcare.counseling.SessionCompletionService;
import com.mentalcare.counseling.SessionService;
import com.mentalcare.counseling.TurnListener;
import com.mentalcare.domain.Enums.RiskLevel;
import com.mentalcare.domain.Message;
import com.mentalcare.domain.Session;
import com.mentalcare.memory.MemorySuggestionService.Suggestion;
import com.mentalcare.testsupport.FakeLlm;
import com.mentalcare.testsupport.FakeLlmConfig;
import com.mentalcare.testsupport.TestDirs;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Memory suggestions come from what the user said in recent summaries, never from AI interpretations. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeLlmConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MemorySuggestionIntegrationTest {

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        TestDirs.register(registry, "memory-suggest");
    }

    static class Listener implements TurnListener {
        @Override public void onUserSaved(Message m) {}
        @Override public void onSafety(RiskLevel r) {}
        @Override public void onDelta(String t) {}
        @Override public void onDone(Message m) {}
        @Override public void onError(String m, String k) {}
    }

    @Autowired MemorySuggestionService suggestions;
    @Autowired MemoryService memories;
    @Autowired FakeLlm llm;
    @Autowired SessionService sessions;
    @Autowired CounselingService counseling;
    @Autowired SessionCompletionService completion;
    @Autowired DataDeletionService deletion;
    @Autowired MockMvc mvc;

    static long sessionId;

    @Test
    @Order(1)
    void severalSuggestionsFromUserStatedSummaryFields() {
        llm.reset();
        Session s = sessions.start(null);
        counseling.opening(s.id(), new Listener());
        counseling.reply(s.id(), "이번 주 이야기", new Listener());
        llm.summary = new SessionSummaryOutput("한 주 정리", List.of("일"),
                List.of("회사에서 새 프로젝트의 리더를 맡게 되었다.", "친구와 다음 달 제주도 여행을 계획했다."),
                List.of("설렘"),
                List.of("건강을 위해 밤 12시 전에 자고 싶다고 생각한다."),
                List.of("AI가 본 해석 문장은 추천되면 안 된다"),               // observedPatterns
                List.of("새로 발견한 점도 추천 대상이 아니다"),               // discoveries
                List.of(), List.of(), List.of(), List.of("work"));
        completion.complete(s.id());
        sessionId = s.id();

        List<Suggestion> list = suggestions.suggestions(12);

        assertThat(list).extracting(Suggestion::text).containsExactly(
                "회사에서 새 프로젝트의 리더를 맡게 되었다.",
                "친구와 다음 달 제주도 여행을 계획했다.",
                "건강을 위해 밤 12시 전에 자고 싶다고 생각한다.");
        assertThat(list).extracting(Suggestion::source).containsExactly("이야기한 사건", "이야기한 사건", "직접 말한 생각");
        assertThat(list).extracting(Suggestion::category).containsExactly("event", "event", "other");
        assertThat(list).allSatisfy(x -> {
            assertThat(x.sessionId()).isEqualTo(sessionId);
            assertThat(x.key()).matches("[0-9a-f]{32}");
        });
        assertThat(suggestions.suggestions(2)).hasSize(2); // limit respected
    }

    @Test
    @Order(2)
    void sentencesAlreadyCoveredByAMemoryAreLeftOut() {
        memories.addManual("회사에서 새 프로젝트 리더를 맡게 되었다", "event"); // slightly different wording
        assertThat(suggestions.suggestions(12)).extracting(Suggestion::text)
                .doesNotContain("회사에서 새 프로젝트의 리더를 맡게 되었다.")
                .contains("친구와 다음 달 제주도 여행을 계획했다.");
    }

    @Test
    @Order(3)
    void dismissedSuggestionsStayHiddenAndOnlyAHashIsStored() {
        Suggestion trip = suggestions.suggestions(12).stream()
                .filter(x -> x.text().startsWith("친구와")).findFirst().orElseThrow();
        suggestions.dismiss(trip.key());
        assertThat(suggestions.suggestions(12)).extracting(Suggestion::key).doesNotContain(trip.key());
        assertThat(suggestions.dismissedKeys()).containsExactly(trip.key());
        assertThatThrownBy(() -> suggestions.dismiss("not-a-key")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Order(4)
    void httpEndpoints() throws Exception {
        mvc.perform(get("/api/memories/suggestions").header("Host", "127.0.0.1:8080"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].text").value("건강을 위해 밤 12시 전에 자고 싶다고 생각한다."))
                .andExpect(jsonPath("$[0].sessionDate").isString());
        mvc.perform(post("/api/memories/suggestions/dismiss").header("Host", "127.0.0.1:8080")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"zz\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/memories/suggestions").header("Host", "evil.example.com"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(5)
    void deleteAllForgetsDismissalsToo() {
        deletion.deleteAll("DELETE", true);
        assertThat(suggestions.dismissedKeys()).isEmpty();
        assertThat(suggestions.suggestions(12)).isEmpty();
    }
}
