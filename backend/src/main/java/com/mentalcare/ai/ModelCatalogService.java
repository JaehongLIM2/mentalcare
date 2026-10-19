package com.mentalcare.ai;

import com.mentalcare.config.OpenAiProperties;
import com.mentalcare.config.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Model discovery and selection. With Sign in with ChatGPT the list comes from the account
 * ({@code GET /v1/models}, {@code visibility == "list"}); configured names are only preferences.
 * Never throws for a missing model: it falls back to the first available one, or to the configured
 * default when the catalog cannot be loaded.
 */
@Service
public class ModelCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalogService.class);
    private static final Duration TTL = Duration.ofMinutes(10);

    public record Catalog(List<ModelInfo> available, String counselingModel, String utilityModel,
                          String defaultCounselingModel, String defaultUtilityModel,
                          String catalogError, boolean fromAccount) {}

    private final LlmClient llm;
    private final BearerTokenProvider tokens;
    private final OpenAiProperties props;
    private final SettingsService settings;

    private volatile List<ModelInfo> cached = List.of();
    private volatile String cachedError;
    private volatile Instant cachedAt = Instant.EPOCH;

    public ModelCatalogService(LlmClient llm, BearerTokenProvider tokens, OpenAiProperties props, SettingsService settings) {
        this.llm = llm;
        this.tokens = tokens;
        this.props = props;
        this.settings = settings;
    }

    public List<ModelInfo> available(boolean refresh) {
        if (!refresh && cachedAt.plus(TTL).isAfter(Instant.now())) return cached;
        synchronized (this) {
            if (!refresh && cachedAt.plus(TTL).isAfter(Instant.now())) return cached;
            if (!tokens.available()) {
                cached = List.of();
                cachedError = tokens.unavailableReason();
            } else {
                try {
                    cached = llm.listModels();
                    cachedError = null;
                    log.info("model catalog loaded count={}", cached.size());
                } catch (LlmException e) {
                    cached = List.of();
                    cachedError = e.userMessage();
                    log.warn("model catalog unavailable kind={}", e.kind());
                }
            }
            cachedAt = Instant.now();
            return cached;
        }
    }

    public void invalidate() {
        cachedAt = Instant.EPOCH;
    }

    public String counselingModel() {
        return resolve(settings.counselingModelOverride(), props.counselingModel());
    }

    public String utilityModel() {
        return resolve(settings.utilityModelOverride(), props.utilityModel());
    }

    public Catalog catalog(boolean refresh) {
        List<ModelInfo> list = available(refresh);
        return new Catalog(list, counselingModel(), utilityModel(), props.counselingModel(), props.utilityModel(),
                cachedError, tokens.usesChatGptPlan());
    }

    /** preference → default; if a catalog is known and neither is offered, the first offered model. */
    private String resolve(String preference, String fallback) {
        List<ModelInfo> list = available(false);
        String wanted = preference == null || preference.isBlank() ? fallback : preference;
        if (list.isEmpty()) return wanted;
        if (list.stream().anyMatch(m -> m.slug().equals(wanted))) return wanted;
        if (list.stream().anyMatch(m -> m.slug().equals(fallback))) return fallback;
        String first = list.get(0).slug();
        log.warn("model '{}' not offered to this account; using '{}'", wanted, first);
        return first;
    }
}
