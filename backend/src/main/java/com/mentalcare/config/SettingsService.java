package com.mentalcare.config;

import com.mentalcare.common.ConflictException;
import com.mentalcare.counseling.SessionLocks;
import com.mentalcare.repository.SettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
    public static final String KEY_LAST_BACKUP = "backup.last_at";

    private static final Set<String> EFFORTS = Set.of("low", "medium", "high");
    private static final Pattern MODEL_SLUG = Pattern.compile("^[a-zA-Z0-9._:\\-]{1,200}$");

    /** All user-editable settings in one request; null fields are left unchanged. */
    public record SettingsUpdate(String counselingReasoningEffort, Integer recentSessionCount, String profileSummary,
                                 String goals, String counselingModel, String utilityModel, Boolean welcomeShown) {}

    private final SettingsRepository repository;
    private final AppProperties app;
    private final OpenAiProperties openAi;
    private final TransactionTemplate tx;
    private final SessionLocks locks;

    public SettingsService(SettingsRepository repository, AppProperties app, OpenAiProperties openAi, TransactionTemplate tx,
                           SessionLocks locks) {
        this.repository = repository;
        this.app = app;
        this.openAi = openAi;
        this.tx = tx;
        this.locks = locks;
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

    /** When the user last downloaded a backup (null = never). */
    public java.time.Instant lastBackupAt() {
        return repository.get(KEY_LAST_BACKUP).map(v -> {
            try { return java.time.Instant.parse(v); } catch (java.time.format.DateTimeParseException e) { return null; }
        }).orElse(null);
    }

    /** Called by backup and restore, which already hold the global lock; does not take it again. */
    public void markBackedUp(java.time.Instant at) {
        repository.set(KEY_LAST_BACKUP, at.toString());
    }

    public String emergencyResourcesText() {
        List<String> resources = app.safety().emergencyResources();
        if (resources == null || resources.isEmpty()) return "(설정된 긴급 연락처 없음)";
        return String.join("\n", resources.stream().map(r -> "- " + r).toList());
    }

    /**
     * Validates every field first, then writes all of them in one transaction (all-or-nothing). The write takes
     * part in the global lock and releases it only after the commit, so a restore cannot replace app_settings
     * underneath it and an idle shutdown waits for it.
     */
    public void updateAll(SettingsUpdate u) {
        String effort = blankToNull(u.counselingReasoningEffort());
        if (effort != null && !EFFORTS.contains(effort)) {
            throw new IllegalArgumentException("reasoning effort must be one of " + EFFORTS);
        }
        if (u.recentSessionCount() != null && (u.recentSessionCount() < 0 || u.recentSessionCount() > 10)) {
            throw new IllegalArgumentException("recent session count must be 0..10");
        }
        String counselingModel = validSlugOrNull(u.counselingModel());
        String utilityModel = validSlugOrNull(u.utilityModel());

        if (!locks.tryLockGlobalRead()) {
            throw new ConflictException("데이터 삭제·복원 또는 종료가 진행 중입니다. 잠시 후 다시 시도하세요.", null);
        }
        try {
            tx.executeWithoutResult(status -> {
                if (u.counselingReasoningEffort() != null) repository.set(KEY_REASONING_EFFORT, effort);
                if (u.recentSessionCount() != null) repository.set(KEY_RECENT_COUNT, String.valueOf(u.recentSessionCount()));
                if (u.profileSummary() != null) repository.set(KEY_PROFILE, blankToNull(u.profileSummary()));
                if (u.goals() != null) repository.set(KEY_GOALS, blankToNull(u.goals()));
                if (u.counselingModel() != null) repository.set(KEY_MODEL_COUNSELING, counselingModel);
                if (u.utilityModel() != null) repository.set(KEY_MODEL_UTILITY, utilityModel);
                if (u.welcomeShown() != null) repository.set(KEY_WELCOME_SHOWN, u.welcomeShown() ? "true" : null);
            });
        } finally {
            locks.unlockGlobalRead();
        }
    }

    public void updateGoals(String goals) {
        updateAll(new SettingsUpdate(null, null, null, goals == null ? "" : goals, null, null, null));
    }

    public void updateWelcomeShown(boolean shown) {
        updateAll(new SettingsUpdate(null, null, null, null, null, null, shown));
    }

    private static String validSlugOrNull(String slug) {
        if (slug == null || slug.isBlank()) return null;
        String s = slug.trim();
        if (!MODEL_SLUG.matcher(s).matches()) {
            throw new IllegalArgumentException("model name contains unexpected characters");
        }
        return s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
