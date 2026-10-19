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
    private final com.mentalcare.backup.BackupService backups;

    public SettingsController(SettingsService settings, OpenAiProperties openAi, AppProperties app, UsageRepository usage,
                              BearerTokenProvider tokens, ModelCatalogService models, CredentialStore credentials,
                              com.mentalcare.backup.BackupService backups) {
        this.backups = backups;
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
                openAi.hasApiKey(), settings.welcomeShown(), rows,
                settings.lastBackupAt(), backups.backupsDir().toString());
    }

    @PatchMapping
    public SettingsDto update(@Valid @RequestBody UpdateSettingsRequest request) {
        settings.updateAll(new SettingsService.SettingsUpdate(request.counselingReasoningEffort(),
                request.recentSessionCount(), request.profileSummary(), request.goals(),
                request.counselingModel(), request.utilityModel(), request.welcomeShown()));
        return get();
    }
}
