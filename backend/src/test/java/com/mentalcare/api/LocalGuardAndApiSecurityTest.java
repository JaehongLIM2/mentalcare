package com.mentalcare.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalcare.config.SecurityHeadersFilter;
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
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP-level security regression: local-only guard (DNS rebinding / CSRF), security headers,
 * XSS / SQL-injection strings stored verbatim and returned as JSON data, OAuth callback behaviour.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeLlmConfig.class)
class LocalGuardAndApiSecurityTest {

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry registry) {
        TestDirs.register(registry, "api");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    // ------------------------------------------------------------------ local-only guard

    @Test
    void healthProbeIsCheapAndLocalOnly() throws Exception {
        mvc.perform(get("/api/health").header("Host", "127.0.0.1:8080"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/health").header("Host", "evil.example.com")).andExpect(status().isForbidden());
        // shutdown is a state-changing POST: cross-site and foreign-origin callers are rejected before it runs
        mvc.perform(post("/api/system/shutdown").header("Host", "127.0.0.1:8080").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/system/shutdown").header("Host", "127.0.0.1:8080").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loopbackHostIsAccepted() throws Exception {
        mvc.perform(get("/api/home").header("Host", "127.0.0.1:8080")).andExpect(status().isOk());
        mvc.perform(get("/api/home").header("Host", "localhost:8080")).andExpect(status().isOk());
        mvc.perform(get("/api/home").header("Host", "[::1]:8080")).andExpect(status().isOk());
    }

    @Test
    void foreignHostHeaderIsRejected_dnsRebinding() throws Exception {
        mvc.perform(get("/api/home").header("Host", "evil.example.com"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.kind").value("LOCAL_ONLY"));
        mvc.perform(get("/api/sessions").header("Host", "192.168.0.10:8080")).andExpect(status().isForbidden());
    }

    @Test
    void foreignOriginIsRejected_csrf() throws Exception {
        mvc.perform(post("/api/sessions").header("Host", "127.0.0.1:8080").header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/chatgpt/signout").header("Host", "127.0.0.1:8080")
                        .header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        // same-origin requests pass
        mvc.perform(get("/api/home").header("Host", "127.0.0.1:8080").header("Origin", "http://127.0.0.1:8080")
                        .header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isOk());
    }

    @Test
    void pathVariantsAndNullOriginCannotBypassTheGuard() throws Exception {
        // matrix-parameter variant: Spring would route /api;x/health to /api/health
        mvc.perform(get("/api;x/health").header("Host", "127.0.0.1:8080").header("Origin", "null").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api;x/sessions/1/complete").header("Host", "127.0.0.1:8080").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        // Origin: null (sandboxed iframe / redirect) is not a loopback origin
        mvc.perform(get("/api/health").header("Host", "127.0.0.1:8080").header("Origin", "null"))
                .andExpect(status().isForbidden());
        // encoded separators / dot segments: MockMvc decodes them before the filter runs and Tomcat
        // rejects them with 400 on its own, so the predicate is checked directly here
        assertThat(com.mentalcare.config.LocalRequestGuardFilter.isPlainPath("/api/%2e%2e/health")).isFalse();
        assertThat(com.mentalcare.config.LocalRequestGuardFilter.isPlainPath("/api/../health")).isFalse();
        assertThat(com.mentalcare.config.LocalRequestGuardFilter.isPlainPath("/api;x/health")).isFalse();
        assertThat(com.mentalcare.config.LocalRequestGuardFilter.isPlainPath("/api/health")).isTrue();
        // plain path still fine
        mvc.perform(get("/api/health").header("Host", "127.0.0.1:8080")).andExpect(status().isOk());
    }

    @Test
    void nestedCheckInIsValidatedAndSettingsUpdateIsAtomic() throws Exception {
        String longNote = "가".repeat(3000);
        mvc.perform(post("/api/sessions").header("Host", "127.0.0.1:8080").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkIn\":{\"mood\":5,\"note\":" + mapper.writeValueAsString(longNote) + "}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.kind").value("VALIDATION"));

        // a bad model name must not let the profile change slip through
        mvc.perform(patch("/api/settings").header("Host", "127.0.0.1:8080").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileSummary\":\"before\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/settings").header("Host", "127.0.0.1:8080").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileSummary\":\"after\",\"counselingModel\":\"bad model name!\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/settings").header("Host", "127.0.0.1:8080"))
                .andExpect(jsonPath("$.profileSummary").value("before"));
    }

    @Test
    void oauthCallbackNavigationFromOpenAiIsAllowedButStateIsChecked() throws Exception {
        mvc.perform(get("/api/auth/chatgpt/callback").param("code", "x").param("state", "forged")
                        .header("Host", "127.0.0.1:8080").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/?chatgpt=error&reason=state_mismatch"));
    }

    @Test
    void noCorsHeadersAreEverSent() throws Exception {
        mvc.perform(options("/api/home").header("Host", "127.0.0.1:8080")
                        .header("Origin", "http://127.0.0.1:5173").header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    // ------------------------------------------------------------------ headers

    @Test
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/api/home").header("Host", "127.0.0.1:8080"))
                .andExpect(header().string("Content-Security-Policy", SecurityHeadersFilter.CSP))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(SecurityHeadersFilter.CSP).contains("script-src 'self'").contains("frame-ancestors 'none'")
                .doesNotContain("unsafe-eval");
    }

    // ------------------------------------------------------------------ data handling

    @Test
    void xssAndSqlInjectionStringsAreStoredVerbatimAndReturnedAsJsonData() throws Exception {
        String xss = "<img src=x onerror=alert('xss')><script>alert(1)</script>";
        String sqli = "'); DROP TABLE memories; -- \" OR 1=1";
        MvcResult created = mvc.perform(post("/api/sessions").header("Host", "127.0.0.1:8080")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkIn\":{\"mood\":5,\"note\":" + mapper.writeValueAsString(xss + sqli) + "}}"))
                .andExpect(status().isCreated()).andReturn();
        long id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        MvcResult detail = mvc.perform(get("/api/sessions/" + id).header("Host", "127.0.0.1:8080"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();
        JsonNode body = mapper.readTree(detail.getResponse().getContentAsString());
        assertThat(body.get("checkIn").get("note").asText()).isEqualTo(xss + sqli); // verbatim, escaped by JSON
        assertThat(detail.getResponse().getContentAsString()).doesNotContain("<script>"); // JSON encoder escapes '<'

        // the schema survived the "injection" and the API is still consistent
        mvc.perform(get("/api/memories").header("Host", "127.0.0.1:8080")).andExpect(status().isOk());
        mvc.perform(patch("/api/memories/999999").header("Host", "127.0.0.1:8080")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":" + mapper.writeValueAsString(sqli) + "}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/sessions/" + id + "/complete").header("Host", "127.0.0.1:8080")).andExpect(status().isOk());
    }

    @Test
    void errorsNeverLeakInternals() throws Exception {
        mvc.perform(get("/api/sessions/abc").header("Host", "127.0.0.1:8080"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다."))
                .andExpect(jsonPath("$.kind").value("BAD_REQUEST"));
        mvc.perform(get("/api/does-not-exist").header("Host", "127.0.0.1:8080"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.kind").value("NOT_FOUND"));
        mvc.perform(get("/api/memories").param("status", "'; DROP").header("Host", "127.0.0.1:8080"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statusEndpointExposesNoCredentialMaterial() throws Exception {
        MvcResult r = mvc.perform(get("/api/auth/chatgpt/status").header("Host", "127.0.0.1:8080"))
                .andExpect(status().isOk()).andReturn();
        String json = r.getResponse().getContentAsString();
        assertThat(json).doesNotContainIgnoringCase("accessToken").doesNotContainIgnoringCase("refreshToken")
                .doesNotContainIgnoringCase("idToken").doesNotContain("client_id");
        assertThat(mapper.readTree(json).get("mode").asText()).isEqualTo("CHATGPT");
    }

    @Test
    void inferenceIsRefusedWhenNotConnected_noApiKeyFallback() throws Exception {
        // FakeLlm bypasses the transport, but the model catalog uses the real provider state
        MvcResult r = mvc.perform(get("/api/models").header("Host", "127.0.0.1:8080")).andExpect(status().isOk()).andReturn();
        JsonNode body = mapper.readTree(r.getResponse().getContentAsString());
        assertThat(body.get("available")).isEmpty();
        assertThat(body.get("catalogError").asText()).contains("ChatGPT");
        assertThat(body.get("counselingModel").asText()).isEqualTo("gpt-6.1-sol"); // default preference, no crash
    }
}
