package com.mentalcare.safety;

import com.mentalcare.context.SafetyMode;
import com.mentalcare.domain.Enums.RiskLevel;

/**
 * Outcome of the safety layer for one user message.
 *
 * @param riskLevel   assessed risk of the message in context (for an unavailable check: the level used for routing)
 * @param mode        how the counselor prompt should be adjusted for this turn
 * @param reason      short assessment reason (may be null)
 * @param unavailable the structured assessment could not be done for this message (timeout, server error…):
 *                    "not checked" must never be read as "checked, no risk"
 */
public record SafetyDecision(RiskLevel riskLevel, SafetyMode mode, String reason, boolean unavailable) {

    public SafetyDecision(RiskLevel riskLevel, SafetyMode mode, String reason) {
        this(riskLevel, mode, reason, false);
    }

    public static SafetyDecision none(boolean sessionHadCrisis) {
        return new SafetyDecision(RiskLevel.NONE, sessionHadCrisis ? SafetyMode.CAUTION : SafetyMode.NORMAL, null, false);
    }

    /** No signal pointed at risk and the screen failed: counseling continues normally, flagged as unchecked. */
    public static SafetyDecision unavailable(boolean sessionHadCrisis, String reason) {
        return new SafetyDecision(RiskLevel.NONE, sessionHadCrisis ? SafetyMode.CAUTION : SafetyMode.NORMAL, reason, true);
    }
}
