package com.mentalcare.ai;

import com.mentalcare.config.OpenAiProperties;
import com.mentalcare.config.OpenAiProperties.AuthMode;
import com.mentalcare.config.SettingsService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ModelCatalogServiceTest {

    private final OpenAiProperties props = new OpenAiProperties("chatgpt", "", "https://api.openai.com/v1",
            "gpt-6.1-sol", "gpt-6-luna", "omni", "medium", "low", 5, 30, 1000, 2000, null);

    private BearerTokenProvider provider(boolean available) {
        BearerTokenProvider p = mock(BearerTokenProvider.class);
        when(p.available()).thenReturn(available);
        when(p.mode()).thenReturn(AuthMode.CHATGPT);
        when(p.usesChatGptPlan()).thenReturn(true);
        when(p.unavailableReason()).thenReturn("not connected");
        return p;
    }

    @Test
    void missingPreferredModelFallsBackToFirstAvailableWithoutCrashing() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.listModels()).thenReturn(List.of(new ModelInfo("gpt-7-nova", "Nova"), new ModelInfo("gpt-6-luna", "Luna")));
        SettingsService settings = mock(SettingsService.class);
        when(settings.counselingModelOverride()).thenReturn("gpt-does-not-exist");
        when(settings.utilityModelOverride()).thenReturn(null);
        ModelCatalogService catalog = new ModelCatalogService(llm, provider(true), props, settings);

        assertThat(catalog.counselingModel()).isEqualTo("gpt-7-nova"); // preference & default both missing → first offered
        assertThat(catalog.utilityModel()).isEqualTo("gpt-6-luna");    // default is offered
        assertThat(catalog.catalog(false).available()).hasSize(2);
    }

    @Test
    void catalogFailureKeepsConfiguredDefaults() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.listModels()).thenThrow(new LlmException(LlmException.Kind.PLAN_UNAVAILABLE, "503"));
        SettingsService settings = mock(SettingsService.class);
        ModelCatalogService catalog = new ModelCatalogService(llm, provider(true), props, settings);

        assertThat(catalog.counselingModel()).isEqualTo("gpt-6.1-sol");
        assertThat(catalog.catalog(false).catalogError()).isNotBlank();
    }

    @Test
    void notConnectedMeansEmptyCatalogAndNoNetworkCall() {
        LlmClient llm = mock(LlmClient.class);
        ModelCatalogService catalog = new ModelCatalogService(llm, provider(false), props, mock(SettingsService.class));

        assertThat(catalog.available(true)).isEmpty();
        verify(llm, never()).listModels();
        assertThat(catalog.counselingModel()).isEqualTo("gpt-6.1-sol");
    }
}
