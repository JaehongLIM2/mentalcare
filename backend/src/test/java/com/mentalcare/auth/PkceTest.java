package com.mentalcare.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PkceTest {

    @Test
    void s256ChallengeMatchesRfc7636Vector() {
        // RFC 7636 Appendix B
        assertThat(Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    void randomTokensAreUrlSafeAndUnique() {
        String a = Pkce.randomToken(), b = Pkce.randomToken();
        assertThat(a).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(a).isNotEqualTo(b);
        assertThat(Pkce.constantTimeEquals(a, a)).isTrue();
        assertThat(Pkce.constantTimeEquals(a, b)).isFalse();
        assertThat(Pkce.constantTimeEquals(a, null)).isFalse();
    }

    @Test
    void returnToIsRestrictedToLoopback() {
        assertThat(ChatGptAuthService.sanitizeReturnTo("http://127.0.0.1:5173/settings")).isEqualTo("http://127.0.0.1:5173/settings");
        assertThat(ChatGptAuthService.sanitizeReturnTo("/settings")).isEqualTo("/settings");
        assertThat(ChatGptAuthService.sanitizeReturnTo("https://evil.example/steal")).isEqualTo("/");
        assertThat(ChatGptAuthService.sanitizeReturnTo("//evil.example")).isEqualTo("/");
        assertThat(ChatGptAuthService.sanitizeReturnTo("http://127.0.0.1@evil.example/")).isEqualTo("/");
        assertThat(ChatGptAuthService.sanitizeReturnTo("javascript:alert(1)")).isEqualTo("/");
        assertThat(ChatGptAuthService.sanitizeReturnTo(null)).isEqualTo("/");
    }
}
