package com.mentalcare.domain;

/** Small enums of the domain, kept together for readability. */
public final class Enums {
    private Enums() {}

    public enum SessionStatus { ACTIVE, COMPLETED }

    public enum MessageRole { USER, ASSISTANT }

    public enum MessageStatus { COMPLETE, INTERRUPTED, FAILED }

    public enum MemoryStatus { CANDIDATE, ACTIVE, ARCHIVED }

    /** Distinguishes what the user actually said from what the AI inferred. */
    public enum MemoryKind { USER_STATED, AI_OBSERVED }

    public enum RiskLevel {
        NONE, LOW, ELEVATED, IMMINENT;

        public boolean atLeast(RiskLevel other) {
            return this.ordinal() >= other.ordinal();
        }

        public static RiskLevel max(RiskLevel a, RiskLevel b) {
            return a.ordinal() >= b.ordinal() ? a : b;
        }
    }
}
