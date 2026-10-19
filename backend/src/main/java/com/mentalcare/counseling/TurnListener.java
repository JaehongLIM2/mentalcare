package com.mentalcare.counseling;

import com.mentalcare.domain.Enums.RiskLevel;
import com.mentalcare.domain.Message;

/** Callbacks for one counseling turn; the SSE controller maps them to events. */
public interface TurnListener {
    void onUserSaved(Message userMessage);
    void onSafety(RiskLevel riskLevel);
    void onDelta(String text);
    void onDone(Message assistantMessage);

    /** How much of the current session the model will see this turn (older messages are omitted first). */
    default void onContext(int includedMessages, int omittedMessages) {}

    /**
     * @param userFacingMessage Korean explanation safe to show
     * @param kind              machine-readable kind (an {@code LlmException.Kind} name or "INTERNAL")
     */
    void onError(String userFacingMessage, String kind);
}
