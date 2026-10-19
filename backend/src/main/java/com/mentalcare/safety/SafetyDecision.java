package com.mentalcare.safety;

import com.mentalcare.context.SafetyMode;
import com.mentalcare.domain.Enums.RiskLevel;

/**
 * Outcome of the safety layer for one user message.
 *
 * @param riskLevel assessed risk of the message in context
 * @param mode      how the counselor prompt should be adjusted for this turn
 * @param reason    short assessment reason (may be null)
 */
public record SafetyDecision(RiskLevel riskLevel, SafetyMode mode, String reason) {
    public static SafetyDecision none(boolean sessionHadCrisis) {
        return new SafetyDecision(RiskLevel.NONE, sessionHadCrisis ? SafetyMode.CAUTION : SafetyMode.NORMAL, null);
    }
}
