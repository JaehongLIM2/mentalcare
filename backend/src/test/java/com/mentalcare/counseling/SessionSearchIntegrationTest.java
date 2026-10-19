package com.mentalcare.counseling;

import com.mentalcare.ai.model.SessionSummaryOutput;
import com.mentalcare.domain.Enums.RiskLevel;
import com.mentalcare.domain.Message;
import com.mentalcare.domain.Session;
import com.mentalcare.repository.SessionRepository;
import com.mentalcare.testsupport.FakeLlm;
import com.mentalcare.testsupport.FakeLlmConfig;
import com.mentalcare.testsupport.TestDirs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** History search: keyword over conversation/summary/title, local date range, tag. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeLlmConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionSearchIntegrationTest {

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        TestDirs.register(registry, "search");
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
    @Autowired MockMvc mvc;

    long work, family;

    @BeforeAll
    void seed() {
        llm.reset();
        Session a = sessions.start(null);
        counseling.opening(a.id(), new Listener());
        counseling.reply(a.id(), "회사에서 평가 때문에 unique-alpha 걱정이 큽니다", new Listener());
        completion.complete(a.id()); // default summary: title 회사 스트레스, tags work/stress
        work = a.id();

        Session b = sessions.start(null);
        counseling.opening(b.id(), new Listener());
        counseling.reply(b.id(), "동생과 오랜만에 통화했어요 unique-beta", new Listener());
        llm.summary = new SessionSummaryOutput("가족과의 거리", List.of("family"), List.of("동생과 통화"), List.of("반가움"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of("family"));
        completion.complete(b.id());
        family = b.id();
        llm.reset();
    }

    @Test
    void keywordMatchesConversationSummaryAndTitle() {
        assertThat(ids(new SessionRepository.Filter("unique-alpha", null, null, null))).containsExactly(work);
        assertThat(ids(new SessionRepository.Filter("UNIQUE-BETA", null, null, null))).containsExactly(family); // ASCII case-insensitive
        assertThat(ids(new SessionRepository.Filter("가족과의 거리", null, null, null))).containsExactly(family); // title
        assertThat(ids(new SessionRepository.Filter("동생과 통화", null, null, null))).containsExactly(family); // summary json
        assertThat(ids(new SessionRepository.Filter("없는 단어 zzz", null, null, null))).isEmpty();
        // LIKE wildcards in the query are literal
        assertThat(ids(new SessionRepository.Filter("%", null, null, null))).isEmpty();
        assertThat(ids(new SessionRepository.Filter("unique_alpha", null, null, null))).isEmpty();
    }

    @Test
    void dateRangeAndTagFilters() {
        LocalDate today = LocalDate.now();
        assertThat(ids(new SessionRepository.Filter(null, today, today, null))).containsExactlyInAnyOrder(work, family);
        assertThat(ids(new SessionRepository.Filter(null, today.plusDays(1), null, null))).isEmpty();
        assertThat(ids(new SessionRepository.Filter(null, null, today.minusDays(1), null))).isEmpty();
        assertThat(ids(new SessionRepository.Filter(null, null, null, "family"))).containsExactly(family);
        assertThat(ids(new SessionRepository.Filter(null, null, null, "work"))).containsExactly(work);
        assertThat(ids(new SessionRepository.Filter("unique-beta", today, today, "work"))).isEmpty(); // filters combine with AND
        assertThat(sessions.search(new SessionRepository.Filter(null, null, null, "work"), 50, 0).total()).isEqualTo(1);
    }

    @Test
    void listEndpointExposesFiltersAndTotalCount() throws Exception {
        mvc.perform(get("/api/sessions").param("q", "unique-alpha").header("Host", "127.0.0.1:8080"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$[0].session.id").value(work));
        mvc.perform(get("/api/sessions").param("tag", "family").header("Host", "127.0.0.1:8080"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].session.id").value(family));
        mvc.perform(get("/api/sessions").param("from", "2026/01/01").header("Host", "127.0.0.1:8080"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/sessions/tags").header("Host", "127.0.0.1:8080"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'family')].count").value(1));
    }

    private List<Long> ids(SessionRepository.Filter f) {
        return sessions.search(f, 50, 0).items().stream().map(i -> i.session().id()).toList();
    }
}
