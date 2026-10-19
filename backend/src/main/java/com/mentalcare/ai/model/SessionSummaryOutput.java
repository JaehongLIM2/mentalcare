package com.mentalcare.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Structured summary produced by the utility model after a session ends.
 * Mirrors resources/schemas/session-summary.schema.json.
 * Fields that describe what the user said (keyEvents, userThoughts) are kept apart from
 * fields that hold the AI's interpretation (observedPatterns, discoveries).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionSummaryOutput(
        String title,
        List<String> topics,
        List<String> keyEvents,
        List<String> emotions,
        List<String> userThoughts,
        List<String> observedPatterns,
        List<String> discoveries,
        List<String> actionItems,
        List<String> nextSessionTopics,
        List<MemoryCandidateOutput> memoryCandidates,
        List<String> tags
) {
    public SessionSummaryOutput {
        title = title == null ? "" : title;
        topics = safe(topics);
        keyEvents = safe(keyEvents);
        emotions = safe(emotions);
        userThoughts = safe(userThoughts);
        observedPatterns = safe(observedPatterns);
        discoveries = safe(discoveries);
        actionItems = safe(actionItems);
        nextSessionTopics = safe(nextSessionTopics);
        memoryCandidates = memoryCandidates == null ? List.of() : List.copyOf(memoryCandidates);
        tags = safe(tags);
    }

    private static List<String> safe(List<String> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
