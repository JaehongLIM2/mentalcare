package com.mentalcare.context;

import com.mentalcare.ai.model.SessionSummaryOutput;
import com.mentalcare.config.AppProperties;
import com.mentalcare.counseling.CounselingService;
import com.mentalcare.counseling.SessionCompletionService;
import com.mentalcare.counseling.SessionService;
import com.mentalcare.counseling.TurnListener;
import com.mentalcare.domain.Enums.RiskLevel;
import com.mentalcare.domain.Message;
import com.mentalcare.domain.Session;
import com.mentalcare.testsupport.FakeLlm;
import com.mentalcare.testsupport.FakeLlmConfig;
import com.mentalcare.testsupport.TestDirs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** A long session's summary must reach the next session in full enough detail (not cut at five items). */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeLlmConfig.class)
class ContextCapacityIntegrationTest {

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        TestDirs.register(registry, "context-capacity");
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
    @Autowired AppProperties app;

    @Test
    void twelveKeyEventsAndTheDiscoveriesOfALongSessionReachTheNextSession() {
        llm.reset();
        List<String> events = IntStream.rangeClosed(1, 12).mapToObj(i -> "연혁 사건 " + i + "번").toList();
        Session s = sessions.start(null);
        counseling.opening(s.id(), new Listener());
        counseling.reply(s.id(), "지난 일을 시간순으로 정리하고 싶어", new Listener());
        llm.summary = new SessionSummaryOutput("연혁 정리", List.of("연혁"), events, List.of("담담함"), List.of("생각 1"),
                List.of("패턴 1"), List.of("새로 드러난 점 1"), List.of(), List.of("다음에 볼 것"), List.of(), List.of("change"));
        completion.complete(s.id());
        llm.reset();

        Session next = sessions.start(null);
        counseling.opening(next.id(), new Listener());

        String instructions = llm.contexts.get(0).instructions();
        events.forEach(e -> assertThat(instructions).contains(e));
        assertThat(instructions).contains("새롭게 드러난 점(해석 포함): 새로 드러난 점 1");
        assertThat(app.context().maxListItemsPerSummary()).isEqualTo(12);
        assertThat(app.context().importedContextMaxCharsOrDefault()).isEqualTo(15000);
        completion.complete(next.id());
    }
}
