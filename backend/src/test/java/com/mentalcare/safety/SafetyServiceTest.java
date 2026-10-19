package com.mentalcare.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalcare.ai.LlmClient;
import com.mentalcare.ai.LlmException;
import com.mentalcare.ai.LlmResult;
import com.mentalcare.ai.LlmUsage;
import com.mentalcare.ai.ModelCatalogService;
import com.mentalcare.ai.ModerationSignal;
import com.mentalcare.ai.model.CrisisAssessmentOutput;
import com.mentalcare.config.AppProperties;
import com.mentalcare.context.SafetyMode;
import com.mentalcare.counseling.Attachments;
import com.mentalcare.domain.Enums.MessageRole;
import com.mentalcare.domain.Enums.MessageStatus;
import com.mentalcare.domain.Enums.RiskLevel;
import com.mentalcare.domain.Enums.SessionStatus;
import com.mentalcare.domain.Message;
import com.mentalcare.domain.PromptVersion;
import com.mentalcare.domain.Session;
import com.mentalcare.prompt.PromptLoader;
import com.mentalcare.repository.SafetyEventRepository;
import com.mentalcare.usage.UsageRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

class SafetyServiceTest {

    private LlmClient llm;
    private SafetyEventRepository events;
    private SafetyService service;

    private static final Session SESSION = new Session(1, SessionStatus.ACTIVE, null, Instant.now(), null, 1L, false, Instant.now(), Instant.now());
    private static final Session CRISIS_SESSION = new Session(1, SessionStatus.ACTIVE, null, Instant.now(), null, 1L, true, Instant.now(), Instant.now());

    @BeforeEach
    void setUp() {
        llm = mock(LlmClient.class);
        events = mock(SafetyEventRepository.class);
        PromptLoader prompts = mock(PromptLoader.class);
        when(prompts.load(anyString())).thenReturn(new PromptLoader.LoadedPrompt("crisis-assessment", "v1", "PROMPT",
                new PromptVersion(1, "crisis-assessment", "v1", "hash", Instant.now())));
        ModelCatalogService models = mock(ModelCatalogService.class);
        when(models.counselingModel()).thenReturn("counsel-model");
        AppProperties app = new AppProperties("d", "m", "c", 3, 7, new AppProperties.Context(30, 5, 0, 0),
                new AppProperties.Memory(2, 3), new AppProperties.Safety(0.4, 6, List.of("109")), null);
        service = new SafetyService(llm, new CrisisKeywordDetector(), prompts, events,
                mock(UsageRecorder.class), app, new ObjectMapper(), models);
    }

    private static Message user(String text) {
        return new Message(10, 1, MessageRole.USER, text, MessageStatus.COMPLETE, null, Instant.now());
    }

    @Test
    void idiomaticExpressionWithoutSignalsStaysNormalAndSkipsAssessment() {
        when(llm.moderate(anyString())).thenReturn(ModerationSignal.none());
        for (String idiom : List.of(
                "회사 가기 싫어 죽겠다. 오늘 진짜 피곤하다.",
                "배고파 죽겠어요",
                "웃겨 죽는 줄 알았어요",
                "이번 주는 마감 때문에 죽을 맛이었어요",
                "그 영화 보다가 지루해 죽는 줄")) {
            Message m = user(idiom);
            SafetyDecision d = service.evaluate(SESSION, m, List.of(m));
            assertThat(d.riskLevel()).as(idiom).isEqualTo(RiskLevel.NONE);
            assertThat(d.mode()).as(idiom).isEqualTo(SafetyMode.NORMAL);
        }
        verify(llm, never()).assessCrisis(anyString(), anyString(), anyString());
        verifyNoInteractions(events);
    }

    @Test
    void keywordSignalTriggersAssessmentAndImminentSwitchesToCrisisMode() {
        when(llm.moderate(anyString())).thenReturn(ModerationSignal.none());
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenReturn(new LlmResult<>(
                new CrisisAssessmentOutput("IMMINENT", "구체적 계획", "안전 확인"), LlmUsage.none("m")));
        Message m = user("오늘 밤에 자살할 거예요. 약도 모아뒀어요.");

        SafetyDecision d = service.evaluate(SESSION, m, List.of(m));

        assertThat(d.riskLevel()).isEqualTo(RiskLevel.IMMINENT);
        assertThat(d.mode()).isEqualTo(SafetyMode.CRISIS);
        ArgumentCaptor<RiskLevel> level = ArgumentCaptor.forClass(RiskLevel.class);
        verify(events).insert(eq(1L), eq(10L), eq(false), any(), eq(true), level.capture(), any());
        assertThat(level.getValue()).isEqualTo(RiskLevel.IMMINENT);
        verify(llm).assessCrisis(eq("counsel-model"), anyString(), anyString());
    }

    @Test
    void keywordAloneNeverDecidesCrisis() {
        // keyword hit, but the context-aware assessment says NONE → normal counseling
        when(llm.moderate(anyString())).thenReturn(ModerationSignal.none());
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenReturn(new LlmResult<>(
                new CrisisAssessmentOutput("NONE", "뉴스 이야기", "일반 상담"), LlmUsage.none("m")));
        Message m = user("뉴스에서 유명인 자살 소식을 보고 마음이 복잡했어요");

        SafetyDecision d = service.evaluate(SESSION, m, List.of(m));

        assertThat(d.riskLevel()).isEqualTo(RiskLevel.NONE);
        assertThat(d.mode()).isEqualTo(SafetyMode.NORMAL);
    }

    @Test
    void moderationSignalDowngradedByAssessmentStaysNormal() {
        when(llm.moderate(anyString())).thenReturn(new ModerationSignal(true, Map.of(ModerationSignal.SELF_HARM, 0.8)));
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenReturn(new LlmResult<>(
                new CrisisAssessmentOutput("NONE", "관용 표현", "일반 상담"), LlmUsage.none("m")));
        Message m = user("이 프로젝트 때문에 죽을 맛이에요");

        SafetyDecision d = service.evaluate(SESSION, m, List.of(m));

        assertThat(d.riskLevel()).isEqualTo(RiskLevel.NONE);
        assertThat(d.mode()).isEqualTo(SafetyMode.NORMAL);
        verify(events).insert(eq(1L), eq(10L), eq(true), any(), eq(false), eq(RiskLevel.NONE), any());
    }

    @Test
    void explicitDangerIsNotMissedWhenModerationIsSilent() {
        when(llm.moderate(anyString())).thenReturn(ModerationSignal.none()); // moderation misses it
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenReturn(new LlmResult<>(
                new CrisisAssessmentOutput("IMMINENT", "r", "a"), LlmUsage.none("m")));
        Message m = user("유서를 써뒀고 오늘 뛰어내릴 생각이에요");

        SafetyDecision d = service.evaluate(SESSION, m, List.of(m));

        assertThat(d.riskLevel()).isEqualTo(RiskLevel.IMMINENT);
        verify(llm).assessCrisis(anyString(), anyString(), anyString());
    }

    @Test
    void elevatedUsesCautionMode() {
        when(llm.moderate(anyString())).thenReturn(ModerationSignal.none());
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenReturn(new LlmResult<>(
                new CrisisAssessmentOutput("ELEVATED", "수동적 사고", "안전 확인"), LlmUsage.none("m")));
        Message m = user("요즘은 그냥 사라지고 싶다는 생각이 자주 들어요");

        SafetyDecision d = service.evaluate(SESSION, m, List.of(m));

        assertThat(d.riskLevel()).isEqualTo(RiskLevel.ELEVATED);
        assertThat(d.mode()).isEqualTo(SafetyMode.CAUTION);
    }

    @Test
    void assessmentFailureFallsBackToConservativeElevated() {
        when(llm.moderate(anyString())).thenThrow(new LlmException(LlmException.Kind.NETWORK, "down"));
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenThrow(new LlmException(LlmException.Kind.TIMEOUT, "slow"));
        Message m = user("죽고 싶어요");

        SafetyDecision d = service.evaluate(SESSION, m, List.of(m));

        assertThat(d.riskLevel()).isEqualTo(RiskLevel.ELEVATED);
        assertThat(d.mode()).isEqualTo(SafetyMode.CAUTION);
    }

    private static LlmResult<CrisisAssessmentOutput> assessed(String level) {
        return new LlmResult<>(new CrisisAssessmentOutput(level, "r", "a"), LlmUsage.none("m"));
    }

    /** Risk phrased without any keyword, e.g. "정리할 건 다 정리했고 이제 끝내도 될 것 같아요". */
    private static final String NO_KEYWORD_RISK = "정리할 건 다 정리했고, 이제 그만 끝내도 될 것 같아요. 다들 저 없이 더 편할 거예요.";

    @Test
    void keywordFreeRiskIsCaughtByScreeningWhenModerationRouteIsUnsupported() {
        assertThat(new CrisisKeywordDetector().matches(NO_KEYWORD_RISK)).as("precondition: no keyword").isFalse();
        when(llm.moderate(anyString())).thenThrow(new LlmException(LlmException.Kind.ROUTE_NOT_SUPPORTED, "401 no_matching_rule"));
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenReturn(assessed("ELEVATED"));
        Message m = user(NO_KEYWORD_RISK);

        SafetyDecision d = service.evaluate(SESSION, m, List.of(m));

        assertThat(d.riskLevel()).isEqualTo(RiskLevel.ELEVATED);
        assertThat(d.mode()).isEqualTo(SafetyMode.CAUTION);
        verify(llm).assessCrisis(anyString(), anyString(), anyString());
        verify(events).insert(eq(1L), eq(10L), eq(false), any(), eq(false), eq(RiskLevel.ELEVATED), any());
    }

    @Test
    void keywordFreeRiskWithWorkingModerationThatStaysSilentIsNotAssessed() {
        // documents the boundary: with moderation available, a message with no signal at all is not assessed
        when(llm.moderate(anyString())).thenReturn(ModerationSignal.none());
        Message m = user(NO_KEYWORD_RISK);

        SafetyDecision d = service.evaluate(SESSION, m, List.of(m));

        assertThat(d.riskLevel()).isEqualTo(RiskLevel.NONE);
        verify(llm, never()).assessCrisis(anyString(), anyString(), anyString());
    }

    @Test
    void unsupportedRouteIsSuspendedButEveryMessageIsStillScreened() {
        when(llm.moderate(anyString())).thenThrow(new LlmException(LlmException.Kind.ROUTE_NOT_SUPPORTED, "401 no_matching_rule"));
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenReturn(assessed("NONE"));
        Message m = user("오늘은 괜찮아요");

        service.evaluate(SESSION, m, List.of(m));
        service.evaluate(SESSION, m, List.of(m));
        SafetyDecision d = service.evaluate(SESSION, m, List.of(m));

        verify(llm, times(1)).moderate(anyString());                          // not retried every turn
        verify(llm, times(3)).assessCrisis(anyString(), anyString(), anyString()); // but every message screened
        assertThat(service.moderationSuspended()).isTrue();
        assertThat(d.riskLevel()).isEqualTo(RiskLevel.NONE);
        verifyNoInteractions(events);                                          // routine NONE screens leave no rows
    }

    @Test
    void realAuthFailureIsNotTreatedAsUnsupportedRoute() {
        // an expired / revoked token: moderation is tried again next turn (after the token refresh)
        when(llm.moderate(anyString()))
                .thenThrow(new LlmException(LlmException.Kind.AUTH_REQUIRED, "401 invalid_token"))
                .thenReturn(ModerationSignal.none());
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenReturn(assessed("NONE"));
        Message m = user("오늘은 괜찮아요");

        service.evaluate(SESSION, m, List.of(m)); // screened because moderation failed
        service.evaluate(SESSION, m, List.of(m)); // moderation works again: no screen needed

        verify(llm, times(2)).moderate(anyString());
        verify(llm, times(1)).assessCrisis(anyString(), anyString(), anyString());
        assertThat(service.moderationSuspended()).isFalse();
    }

    @Test
    void failedScreenWithoutAnySignalKeepsCounselingGoingAndLeavesATrace() {
        when(llm.moderate(anyString())).thenThrow(new LlmException(LlmException.Kind.ROUTE_NOT_SUPPORTED, "x"));
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenThrow(new LlmException(LlmException.Kind.TIMEOUT, "slow"));
        Message m = user("오늘 회사에서 있었던 일을 이야기하고 싶어요");

        SafetyDecision d = service.evaluate(SESSION, m, List.of(m));

        assertThat(d.unavailable()).as("not checked is not the same as no risk").isTrue();
        assertThat(d.riskLevel()).isEqualTo(RiskLevel.NONE);   // routing only: no crisis mode on a timeout
        assertThat(d.mode()).isEqualTo(SafetyMode.NORMAL);
        verify(events).insert(eq(1L), eq(10L), eq(false), any(), eq(false), eq("UNAVAILABLE"),
                org.mockito.ArgumentMatchers.contains("screening unavailable: TIMEOUT"));
        verify(events, never()).insert(anyLong(), any(), anyBoolean(), any(), anyBoolean(), any(RiskLevel.class), any());
    }

    @Test
    void failedAssessmentAfterAKeywordStaysConservativeEvenWithoutModeration() {
        when(llm.moderate(anyString())).thenThrow(new LlmException(LlmException.Kind.ROUTE_NOT_SUPPORTED, "x"));
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenThrow(new LlmException(LlmException.Kind.SERVER, "500"));
        Message m = user("요즘 죽고 싶다는 생각이 들어요");

        SafetyDecision d = service.evaluate(SESSION, m, List.of(m));

        assertThat(d.riskLevel()).isEqualTo(RiskLevel.ELEVATED);
        assertThat(d.mode()).isEqualTo(SafetyMode.CAUTION);
        assertThat(d.unavailable()).isTrue();
        verify(events).insert(eq(1L), eq(10L), eq(false), any(), eq(true), eq("UNAVAILABLE"), any());
    }

    @Test
    void successfulScreensAreNotFlaggedUnavailable() {
        when(llm.moderate(anyString())).thenThrow(new LlmException(LlmException.Kind.ROUTE_NOT_SUPPORTED, "x"));
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenReturn(assessed("NONE"));
        Message m = user("오늘은 괜찮아요");
        assertThat(service.evaluate(SESSION, m, List.of(m)).unavailable()).isFalse();
    }

    @Test
    void emptyAssessmentResultCountsAsFailure() {
        when(llm.moderate(anyString())).thenReturn(ModerationSignal.none());
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenReturn(null);
        Message m = user("죽고 싶어요");

        assertThat(service.evaluate(SESSION, m, List.of(m)).riskLevel()).isEqualTo(RiskLevel.ELEVATED);
    }

    @Test
    void sessionWithEarlierCrisisStaysInCautionEvenWhenCurrentMessageIsFine() {
        when(llm.moderate(anyString())).thenReturn(ModerationSignal.none());
        Message m = user("이제 좀 괜찮아요. 친구랑 통화했어요.");

        SafetyDecision d = service.evaluate(CRISIS_SESSION, m, List.of(m));

        assertThat(d.riskLevel()).isEqualTo(RiskLevel.NONE);
        assertThat(d.mode()).isEqualTo(SafetyMode.CAUTION);
    }

    // ---- attachments: the counselor reads them in full, so the safety check must see them too ----

    private static List<Attachments.Input> attached(String content) {
        return List.of(new Attachments.Input("메모.txt", content));
    }

    @Test
    void riskWrittenOnlyInTheAttachmentIsAssessedEvenWithWorkingModeration() {
        when(llm.moderate(anyString())).thenReturn(ModerationSignal.none());
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenReturn(assessed("ELEVATED"));
        Message m = user("이 메모 좀 봐줘");

        SafetyDecision d = service.evaluate(SESSION, m, List.of(m), attached("요즘 밤마다 죽고 싶다는 생각이 든다."));

        assertThat(d.riskLevel()).isEqualTo(RiskLevel.ELEVATED);
        assertThat(d.mode()).isEqualTo(SafetyMode.CAUTION);
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(llm).assessCrisis(anyString(), anyString(), input.capture());
        assertThat(input.getValue()).contains("[첨부 자료]", "본인의 말이라는 보장이 없습니다", "죽고 싶다", "메모.txt");
        verify(events).insert(eq(1L), eq(10L), eq(false), any(), eq(true), eq(RiskLevel.ELEVATED), any());
    }

    @Test
    void keywordFreeRiskInAFileOnlyMessageIsScreened() {
        // no keyword anywhere and moderation silent: a message with files is still assessed
        when(llm.moderate(anyString())).thenReturn(ModerationSignal.none());
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenReturn(assessed("ELEVATED"));
        Message m = user("");

        SafetyDecision d = service.evaluate(SESSION, m, List.of(m), attached(NO_KEYWORD_RISK));

        assertThat(d.riskLevel()).isEqualTo(RiskLevel.ELEVATED);
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(llm).assessCrisis(anyString(), anyString(), input.capture());
        assertThat(input.getValue()).contains(NO_KEYWORD_RISK);
    }

    @Test
    void anotherPersonsWordsAreLabelledAndTheAssessmentDecides() {
        // a chat log where the other person says it: passed on as material, and the assessment may keep it NONE
        when(llm.moderate(anyString())).thenReturn(ModerationSignal.none());
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenReturn(assessed("NONE"));
        Message m = user("친구가 이런 말을 했는데 내가 뭐라고 해야 할까");

        SafetyDecision d = service.evaluate(SESSION, m, List.of(m), attached("친구\t시험 망해서 죽고 싶다 ㅋㅋ\n나\t괜찮아?"));

        assertThat(d.riskLevel()).isEqualTo(RiskLevel.NONE);
        assertThat(d.mode()).isEqualTo(SafetyMode.NORMAL);
        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(llm).assessCrisis(anyString(), anyString(), input.capture());
        assertThat(input.getValue()).contains("다른 사람의 말", "자료 속 다른 사람의 말은 사용자 본인의 위험으로 보지 않습니다");
    }

    @Test
    void failedAssessmentOfAnAttachmentSignalStaysConservative() {
        when(llm.moderate(anyString())).thenReturn(ModerationSignal.none());
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenThrow(new LlmException(LlmException.Kind.TIMEOUT, "slow"));
        Message m = user("이거 봐줘");

        SafetyDecision d = service.evaluate(SESSION, m, List.of(m), attached("유서를 써 봤다"));

        assertThat(d.riskLevel()).isEqualTo(RiskLevel.ELEVATED);
        assertThat(d.unavailable()).isTrue();
    }

    @Test
    void failedScreenOfAFileWithoutSignalIsMarkedUnchecked() {
        when(llm.moderate(anyString())).thenReturn(ModerationSignal.none());
        when(llm.assessCrisis(anyString(), anyString(), anyString())).thenThrow(new LlmException(LlmException.Kind.TIMEOUT, "slow"));
        Message m = user("회의록 정리 좀 도와줘");

        SafetyDecision d = service.evaluate(SESSION, m, List.of(m), attached("다음 주 일정: 월요일 회의"));

        assertThat(d.riskLevel()).isEqualTo(RiskLevel.NONE);
        assertThat(d.unavailable()).isTrue();
    }

    @Test
    void longAttachmentsAreExcerptedAroundTheSignalWithinTheLimit() {
        String filler = "평범한 하루 이야기. ".repeat(3_000); // ~36,000 chars
        String text = filler + "그날은 정말 뛰어내릴 생각까지 했다." + filler;
        String excerpt = AttachmentExcerpt.of(List.of(new Attachments.Input("긴글.txt", text)), new CrisisKeywordDetector());

        assertThat(excerpt).contains("뛰어내릴 생각까지");
        assertThat(excerpt.length()).isLessThan(AttachmentExcerpt.MAX_CHARS + 400);

        String plain = AttachmentExcerpt.of(List.of(new Attachments.Input("a.txt", "앞부분" + filler + "끝부분")), new CrisisKeywordDetector());
        assertThat(plain).contains("앞부분", "끝부분", "중략");
        assertThat(plain.length()).isLessThan(AttachmentExcerpt.MAX_CHARS + 400);
    }

    @Test
    void messagesWithoutFilesBehaveAsBefore() {
        when(llm.moderate(anyString())).thenReturn(ModerationSignal.none());
        Message m = user("오늘은 평범했어");

        assertThat(service.evaluate(SESSION, m, List.of(m), List.of()).riskLevel()).isEqualTo(RiskLevel.NONE);
        verify(llm, never()).assessCrisis(anyString(), anyString(), anyString());
    }
}
