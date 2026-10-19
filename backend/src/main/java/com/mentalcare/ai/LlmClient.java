package com.mentalcare.ai;

import com.mentalcare.ai.model.CrisisAssessmentOutput;
import com.mentalcare.ai.model.SessionSummaryOutput;
import com.mentalcare.context.CounselingContext;

import java.util.List;

/**
 * AI integration boundary. Business services depend only on this interface; the OpenAI
 * implementation lives in {@code com.mentalcare.ai.openai}. Tests substitute a fake.
 * Model names are passed in by callers (resolved by {@link ModelCatalogService}), never hard-coded here.
 */
public interface LlmClient {

    /**
     * Streams the counselor's reply. Blocks until the stream ends and returns token usage.
     * Deltas are delivered through {@code handler} as they arrive.
     */
    LlmUsage streamCounselingResponse(CounselingContext context, StreamHandler handler) throws LlmException;

    /** Structured session summary (utility model). */
    LlmResult<SessionSummaryOutput> generateSessionSummary(String model, String instructions, String transcript) throws LlmException;

    /** Structured crisis assessment (counseling model). */
    LlmResult<CrisisAssessmentOutput> assessCrisis(String model, String instructions, String recentConversation) throws LlmException;

    /** Moderation signal for a single user message. */
    ModerationSignal moderate(String text) throws LlmException;

    /** Models the current credential may use (empty when the provider offers no catalog). */
    List<ModelInfo> listModels() throws LlmException;
}
