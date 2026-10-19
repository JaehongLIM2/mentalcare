package com.mentalcare.config;

import com.mentalcare.ai.BearerTokenProvider;
import com.mentalcare.ai.openai.ApiKeyTokenProvider;
import com.mentalcare.auth.ChatGptAuthService;
import com.mentalcare.auth.ChatGptTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exactly one credential source, chosen by {@code openai.auth-mode}. There is no fallback between them. */
@Configuration
public class TokenProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(TokenProviderConfig.class);

    @Bean
    public BearerTokenProvider bearerTokenProvider(OpenAiProperties props, ChatGptAuthService auth) {
        if (props.mode() == OpenAiProperties.AuthMode.API_KEY) {
            log.warn("openai.auth-mode=api-key: requests are billed to OPENAI_API_KEY (developer mode)");
            return new ApiKeyTokenProvider(props);
        }
        log.info("openai.auth-mode=chatgpt: requests use the signed-in user's ChatGPT plan");
        return new ChatGptTokenProvider(auth);
    }
}
