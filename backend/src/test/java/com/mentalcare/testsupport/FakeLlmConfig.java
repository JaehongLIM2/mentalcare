package com.mentalcare.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class FakeLlmConfig {
    @Bean
    @Primary
    public FakeLlm fakeLlm() {
        return new FakeLlm();
    }
}
