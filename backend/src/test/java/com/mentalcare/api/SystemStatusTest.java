package com.mentalcare.api;

import com.mentalcare.counseling.SessionLocks;
import com.mentalcare.testsupport.FakeLlmConfig;
import com.mentalcare.testsupport.TestDirs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The in-app status card and the "quit" button. The shutdown itself is never triggered here. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeLlmConfig.class)
class SystemStatusTest {

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        TestDirs.register(registry, "system");
    }

    @Autowired MockMvc mvc;
    @Autowired SessionLocks locks;

    @Test
    void statusReportsProcessFactsWithoutCounselingData() throws Exception {
        mvc.perform(get("/api/system/status").header("Host", "127.0.0.1:8080"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.pid").isNumber())
                .andExpect(jsonPath("$.startedAt").isString())
                .andExpect(jsonPath("$.busySessions").value(0))
                .andExpect(jsonPath("$.dataDir").isString());
        mvc.perform(get("/api/system/status").header("Host", "evil.example.com")).andExpect(status().isForbidden());
    }

    @Test
    void shutdownBodyParsingKeepsTheStopScriptWorking() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        org.assertj.core.api.Assertions.assertThat(SystemController.wantsIdleOnly(null, mapper)).isFalse();      // stop.ps1: no body
        org.assertj.core.api.Assertions.assertThat(SystemController.wantsIdleOnly("", mapper)).isFalse();
        org.assertj.core.api.Assertions.assertThat(SystemController.wantsIdleOnly("not json", mapper)).isFalse();
        org.assertj.core.api.Assertions.assertThat(SystemController.wantsIdleOnly("{\"ifIdle\":false}", mapper)).isFalse();
        org.assertj.core.api.Assertions.assertThat(SystemController.wantsIdleOnly("{\"ifIdle\":true}", mapper)).isTrue(); // in-app button
    }

    @Test
    void idleOnlyShutdownIsRefusedWhileWorkIsRunning() throws Exception {
        org.assertj.core.api.Assertions.assertThat(locks.tryLock(4242L)).isTrue();
        try {
            mvc.perform(get("/api/system/status").header("Host", "127.0.0.1:8080"))
                    .andExpect(jsonPath("$.busySessions").value(1));
            mvc.perform(post("/api/system/shutdown").header("Host", "127.0.0.1:8080")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"ifIdle\":true}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.kind").value("CONFLICT"));
        } finally {
            locks.unlock(4242L);
        }
    }
}
