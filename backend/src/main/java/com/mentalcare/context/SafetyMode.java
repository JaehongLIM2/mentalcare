package com.mentalcare.context;

/** How the counselor prompt is adjusted for the current turn. */
public enum SafetyMode {
    /** Ordinary reflective counseling. */
    NORMAL,
    /** Ordinary counseling plus the safety addendum (ELEVATED risk, or a session that had a crisis earlier). */
    CAUTION,
    /** Crisis-response prompt replaces the ordinary flow for this turn (IMMINENT risk). */
    CRISIS
}
