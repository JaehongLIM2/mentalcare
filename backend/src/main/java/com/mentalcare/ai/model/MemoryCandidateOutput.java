package com.mentalcare.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A long-term memory candidate suggested by the summary model.
 *
 * @param content                 one sentence, written in the user's language
 * @param kind                    USER_STATED (user said it explicitly) or AI_OBSERVED (interpretation)
 * @param category                e.g. concern, pattern, relationship, goal, event, change
 * @param reason                  why this may be worth remembering
 * @param matchesExistingMemoryId id of the existing memory this candidate is about, or null
 * @param relation                SAME (restates that memory), UPDATE (new information or a correction about it)
 *                                or NEW; null for sources that do not say (then a linked id means SAME)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemoryCandidateOutput(
        String content,
        String kind,
        String category,
        String reason,
        Long matchesExistingMemoryId,
        String relation
) {
    public static final String SAME = "SAME";

    public MemoryCandidateOutput(String content, String kind, String category, String reason, Long matchesExistingMemoryId) {
        this(content, kind, category, reason, matchesExistingMemoryId, null);
    }

    /** True when the candidate claims to restate the linked memory rather than add to it. */
    public boolean restatesLinkedMemory() {
        return matchesExistingMemoryId != null && (relation == null || SAME.equalsIgnoreCase(relation.trim()));
    }
}
