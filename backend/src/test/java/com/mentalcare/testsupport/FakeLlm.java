package com.mentalcare.testsupport;

import com.mentalcare.ai.LlmClient;
import com.mentalcare.ai.LlmResult;
import com.mentalcare.ai.LlmUsage;
import com.mentalcare.ai.ModelInfo;
import com.mentalcare.ai.ModerationSignal;
import com.mentalcare.ai.StreamHandler;
import com.mentalcare.ai.model.CrisisAssessmentOutput;
import com.mentalcare.ai.model.MemoryCandidateOutput;
import com.mentalcare.ai.model.SessionSummaryOutput;
import com.mentalcare.context.CounselingContext;

import java.util.ArrayList;
import java.util.List;

/** Scripted fake. Records the contexts it received so tests can assert on context building. */
public class FakeLlm implements LlmClient {
    public final List<CounselingContext> contexts = new ArrayList<>();
    public final List<String> summaryTranscripts = new ArrayList<>();
    public String reply = "지난 한 주는 어떻게 지내셨나요?";
    public SessionSummaryOutput summary = defaultSummary();
    public RuntimeException summaryFailure;
    public RuntimeException streamFailure;
    public String crisisLevel = "NONE";
    public List<ModelInfo> models = List.of(new ModelInfo("gpt-6.1-sol", "GPT-6.1 Sol"), new ModelInfo("gpt-6-luna", "GPT-6 Luna"));

    public static SessionSummaryOutput defaultSummary() {
        return new SessionSummaryOutput("회사 스트레스", List.of("work"), List.of("상사와 갈등"), List.of("불안"),
                List.of("상사가 나를 싫어하는 것 같다"), List.of("평가 걱정 가능성"), List.of(), List.of("행동 메모하기"),
                List.of("면담 결과 확인"),
                List.of(new MemoryCandidateOutput("상사와의 관계가 반복적인 스트레스의 원인", "USER_STATED", "relationship", "반복", null)),
                List.of("Work", "stress", "work"));
    }

    public static SessionSummaryOutput emptySummary() {
        return new SessionSummaryOutput("x", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    public void reset() {
        contexts.clear();
        summaryTranscripts.clear();
        summaryFailure = null;
        streamFailure = null;
        crisisLevel = "NONE";
        summary = defaultSummary();
    }

    @Override
    public LlmUsage streamCounselingResponse(CounselingContext context, StreamHandler handler) {
        contexts.add(context);
        if (streamFailure != null) {
            handler.onDelta("부분 응답");
            throw streamFailure;
        }
        for (String piece : reply.split("(?<=\\s)")) handler.onDelta(piece);
        return new LlmUsage("fake-counsel", 100, 20);
    }

    @Override
    public LlmResult<SessionSummaryOutput> generateSessionSummary(String model, String instructions, String transcript) {
        summaryTranscripts.add(transcript);
        if (summaryFailure != null) throw summaryFailure;
        return new LlmResult<>(summary, new LlmUsage("fake-util", 300, 150));
    }

    @Override
    public LlmResult<CrisisAssessmentOutput> assessCrisis(String model, String instructions, String recentConversation) {
        return new LlmResult<>(new CrisisAssessmentOutput(crisisLevel, "test", "test"), LlmUsage.none("fake-counsel"));
    }

    @Override
    public ModerationSignal moderate(String text) {
        return ModerationSignal.none();
    }

    @Override
    public List<ModelInfo> listModels() {
        return models;
    }
}
