package com.mentalcare.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A long-term memory candidate suggested by the summary model.
 *
 * @param content                 one sentence, written in the user's language
 * @param kind                    USER_STATED (user said it explicitly) or AI_OBSERVED (interpretation)
 * @param category                e.g. concern, pattern, relationship, goal, event, change
 * @param reason                  why this may be worth remembering
 * @param matchesExistingMemoryId id of an existing memory this candidate restates, or null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemoryCandidateOutput(
        String content,
        String kind,
        String category,
        String reason,
        Long matchesExistingMemoryId
) {}
