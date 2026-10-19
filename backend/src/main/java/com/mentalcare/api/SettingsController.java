package com.mentalcare.api;

import com.mentalcare.ai.BearerTokenProvider;
import com.mentalcare.ai.ModelCatalogService;
import com.mentalcare.api.dto.ApiDtos.SettingsDto;
import com.mentalcare.api.dto.ApiDtos.UpdateSettingsRequest;
import com.mentalcare.api.dto.ApiDtos.UsageDto;
import com.mentalcare.auth.CredentialStore;
import com.mentalcare.config.AppProperties;
import com.mentalcare.config.OpenAiProperties;
import com.mentalcare.config.SettingsService;
import com.mentalcare.repository.UsageRepository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settings;
    private final OpenAiProperties openAi;
    private final AppProperties app;
    private final UsageRepository usage;
    private final BearerTokenProvider tokens;
    private final ModelCatalogService models;
    private final CredentialStore credentials;

    public SettingsController(SettingsService settings, OpenAiProperties openAi, AppProperties app, UsageRepository usage,
                              BearerTokenProvider tokens, ModelCatalogService models, CredentialStore credentials) {
        this.settings = settings;
        this.openAi = openAi;
        this.app = app;
        this.usage = usage;
        this.tokens = tokens;
        this.models = models;
        this.credentials = credentials;
    }

    @GetMapping
    public SettingsDto get() {
        List<UsageDto> rows = usage.summarizeSince(Instant.now().minus(30, ChronoUnit.DAYS)).stream()
                .map(r -> new UsageDto(r.model(), r.requestType(), r.requests(), r.inputTokens(), r.outputTokens()))
                .toList();
        boolean ready = tokens.available();
        return new SettingsDto(
                tokens.mode().name(), ready, ready ? null : tokens.unavailableReason(),
                models.counselingModel(), models.utilityModel(), openAi.moderationModel(),
                openAi.counselingModel(), openAi.utilityModel(),
                settings.counselingReasoningEffort(), openAi.counselingReasoningEffort(),
                settings.recentSessionCount(), settings.profileSummary(), settings.goals(),
                Path.of(app.dataDir()).toAbsolutePath().normalize().toString(),
                Path.of(app.markdownDir()).toAbsolutePath().normalize().toString(),
                credentials.directory().toAbsolutePath().normalize().toString(),
                credentials.protector().description(),
                openAi.hasApiKey(), settings.welcomeShown(), rows);
    }

    @PatchMapping
    public SettingsDto update(@Valid @RequestBody UpdateSettingsRequest request) {
        if (request.counselingReasoningEffort() != null) settings.updateReasoningEffort(blankToNull(request.counselingReasoningEffort()));
        if (request.recentSessionCount() != null) settings.updateRecentSessionCount(request.recentSessionCount());
        if (request.profileSummary() != null) settings.updateProfileSummary(request.profileSummary());
        if (request.goals() != null) settings.updateGoals(request.goals());
        if (request.counselingModel() != null) settings.updateCounselingModel(request.counselingModel());
        if (request.utilityModel() != null) settings.updateUtilityModel(request.utilityModel());
        if (request.welcomeShown() != null) settings.updateWelcomeShown(request.welcomeShown());
        return get();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
