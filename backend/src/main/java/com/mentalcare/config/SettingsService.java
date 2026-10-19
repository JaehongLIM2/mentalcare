package com.mentalcare.config;

import com.mentalcare.repository.SettingsRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Runtime settings = application.yml defaults overridden by rows in app_settings.
 * Only a handful of values are user-editable; secrets never live here.
 */
@Service
public class SettingsService {

    public static final String KEY_REASONING_EFFORT = "counseling.reasoning_effort";
    public static final String KEY_RECENT_COUNT = "context.recent_session_count";
    public static final String KEY_PROFILE = "profile.summary";
    public static final String KEY_GOALS = "goals";
    public static final String KEY_MODEL_COUNSELING = "model.counseling";
    public static final String KEY_MODEL_UTILITY = "model.utility";
    public static final String KEY_WELCOME_SHOWN = "siwc.welcome_shown";

    private static final Set<String> EFFORTS = Set.of("low", "medium", "high");
    private static final Pattern MODEL_SLUG = Pattern.compile("^[a-zA-Z0-9._:\\-]{1,200}$");

    private final SettingsRepository repository;
    private final AppProperties app;
    private final OpenAiProperties openAi;

    public SettingsService(SettingsRepository repository, AppProperties app, OpenAiProperties openAi) {
        this.repository = repository;
        this.app = app;
        this.openAi = openAi;
    }

    public String counselingReasoningEffort() {
        return repository.get(KEY_REASONING_EFFORT).filter(EFFORTS::contains).orElse(openAi.counselingReasoningEffort());
    }

    public int recentSessionCount() {
        return repository.get(KEY_RECENT_COUNT).map(Integer::parseInt).filter(n -> n >= 0 && n <= 10)
                .orElse(app.recentSessionCount());
    }

    /** Short description of the user written by the user. Empty when not set. */
    public String profileSummary() {
        return repository.get(KEY_PROFILE).orElse("");
    }

    /** Current counseling goals, free text, one per line. */
    public String goals() {
        return repository.get(KEY_GOALS).orElse("");
    }

    /** User-chosen model slug or null (then the configured default / catalog decides). */
    public String counselingModelOverride() {
        return repository.get(KEY_MODEL_COUNSELING).filter(s -> MODEL_SLUG.matcher(s).matches()).orElse(null);
    }

    public String utilityModelOverride() {
        return repository.get(KEY_MODEL_UTILITY).filter(s -> MODEL_SLUG.matcher(s).matches()).orElse(null);
    }

    public boolean welcomeShown() {
        return repository.get(KEY_WELCOME_SHOWN).map(Boolean::parseBoolean).orElse(false);
    }

    public String emergencyResourcesText() {
        List<String> resources = app.safety().emergencyResources();
        if (resources == null || resources.isEmpty()) return "(설정된 긴급 연락처 없음)";
        return String.join("\n", resources.stream().map(r -> "- " + r).toList());
    }

    public void updateReasoningEffort(String effort) {
        if (effort != null && !EFFORTS.contains(effort)) {
            throw new IllegalArgumentException("reasoning effort must be one of " + EFFORTS);
        }
        repository.set(KEY_REASONING_EFFORT, effort);
    }

    public void updateRecentSessionCount(Integer count) {
        if (count != null && (count < 0 || count > 10)) {
            throw new IllegalArgumentException("recent session count must be 0..10");
        }
        repository.set(KEY_RECENT_COUNT, count == null ? null : String.valueOf(count));
    }

    public void updateProfileSummary(String profile) {
        repository.set(KEY_PROFILE, profile == null || profile.isBlank() ? null : profile.trim());
    }

    public void updateGoals(String goals) {
        repository.set(KEY_GOALS, goals == null || goals.isBlank() ? null : goals.trim());
    }

    public void updateCounselingModel(String slug) {
        repository.set(KEY_MODEL_COUNSELING, validSlugOrNull(slug));
    }

    public void updateUtilityModel(String slug) {
        repository.set(KEY_MODEL_UTILITY, validSlugOrNull(slug));
    }

    public void updateWelcomeShown(boolean shown) {
        repository.set(KEY_WELCOME_SHOWN, shown ? "true" : null);
    }

    private static String validSlugOrNull(String slug) {
        if (slug == null || slug.isBlank()) return null;
        String s = slug.trim();
        if (!MODEL_SLUG.matcher(s).matches()) {
            throw new IllegalArgumentException("model name contains unexpected characters");
        }
        return s;
    }
}
