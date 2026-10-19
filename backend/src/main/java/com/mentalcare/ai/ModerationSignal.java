package com.mentalcare.ai;

import java.util.Map;

/**
 * Result of the moderation call. This is a signal, never a verdict on its own.
 *
 * @param flagged provider-level flag
 * @param scores  category -> score (0..1) for the categories the app cares about
 */
public record ModerationSignal(boolean flagged, Map<String, Double> scores) {

    public static final String SELF_HARM = "self-harm";
    public static final String SELF_HARM_INTENT = "self-harm/intent";
    public static final String SELF_HARM_INSTRUCTIONS = "self-harm/instructions";
    public static final String VIOLENCE = "violence";

    public static ModerationSignal none() {
        return new ModerationSignal(false, Map.of());
    }

    public double score(String category) {
        return scores.getOrDefault(category, 0.0);
    }

    public boolean selfHarmSignal(double threshold) {
        return score(SELF_HARM) >= threshold || score(SELF_HARM_INTENT) >= threshold
                || score(SELF_HARM_INSTRUCTIONS) >= threshold;
    }

    public boolean violenceSignal(double threshold) {
        return score(VIOLENCE) >= threshold;
    }

    public boolean anySignal(double threshold) {
        return flagged || selfHarmSignal(threshold) || violenceSignal(threshold);
    }
}
