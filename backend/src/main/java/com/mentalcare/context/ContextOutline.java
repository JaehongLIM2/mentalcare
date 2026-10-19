package com.mentalcare.context;

import java.util.List;

/**
 * What the counselor would see on the next turn of a session, without the prompt text itself:
 * which memories, summaries and imported archives are included and how much of the current
 * conversation fits the character budget. Shown to the user as "이번 상담의 참고 맥락".
 */
public record ContextOutline(
        boolean profileIncluded,
        boolean goalsIncluded,
        boolean checkInIncluded,
        List<MemoryRef> memories,
        int activeMemoryTotal,
        List<SummaryRef> recentSummaries,
        int recentSummaryLimit,
        List<ImportedRef> importedArchives,
        int messagesIncluded,
        int messagesOmitted,
        int charsIncluded,
        int charsBudget) {

    public record MemoryRef(long id, String content, String kind, String category) {}

    public record SummaryRef(long sessionId, String date, String title, boolean hasSummary) {}

    public record ImportedRef(long sessionId, String date, String title, int charsIncluded, boolean truncated) {}

    /** Result of fitting session messages into the per-turn character budget. */
    public record Budget(List<ContextMessage> messages, int included, int omitted, int chars) {}
}
