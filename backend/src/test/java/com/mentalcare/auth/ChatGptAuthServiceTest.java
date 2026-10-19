package com.mentalcare.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalcare.ai.LlmException;
import com.mentalcare.config.OpenAiProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Full OAuth flow against a scripted authorization server; no network, no real tokens. */
class ChatGptAuthServiceTest {

    static final String ISSUER = "https://auth.openai.com";
    static final String ISSUED_CLIENT = "oaiapp_test_client_123";

    @TempDir Path tmp;

    RSAKey key;
    FakeOAuthServer server;
    CredentialStore store;
    ChatGptAuthService auth;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    /** Scripted token/discovery/revocation endpoint. */
    class FakeOAuthServer implements OAuthTransport {
        final List<Map<String, String>> tokenRequests = new ArrayList<>();
        final List<Map<String, String>> revocations = new ArrayList<>();
        int tokenStatus = 200;
        String tokenError;              // e.g. invalid_grant
        String nonceForIdToken;         // nonce to embed in the next id_token
        String scopeInResponse = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
        boolean includeIdToken = true;
        boolean networkDown = false;
        int refreshCount = 0;

        @Override
        public HttpResult postForm(URI uri, Map<String, String> form) {
            if (networkDown) throw new AuthException(AuthException.Code.NETWORK, "down");
            if (uri.getPath().endsWith("/revoke")) {
                revocations.add(form);
                return new HttpResult(200, "");
            }
            tokenRequests.add(new LinkedHashMap<>(form));
            if (tokenStatus != 200) {
                return new HttpResult(tokenStatus, "{\"error\":\"" + tokenError + "\"}");
            }
            boolean refresh = "refresh_token".equals(form.get("grant_type"));
            if (refresh) refreshCount++;
            Map<String, Object> body = new HashMap<>();
            body.put("access_token", (refresh ? "refreshed-access-" + refreshCount : "access-1"));
            body.put("refresh_token", (refresh ? "refreshed-refresh-" + refreshCount : "refresh-1"));
            body.put("token_type", "Bearer");
            body.put("expires_in", 3600);
            body.put("scope", scopeInResponse);
            if (includeIdToken) body.put("id_token", idToken(form.get("client_id"), nonceForIdToken, 300));
            try {
                return new HttpResult(200, mapper.writeValueAsString(body));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public HttpResult getJson(URI uri) {
            if (networkDown) throw new AuthException(AuthException.Code.NETWORK, "down");
            return new HttpResult(200, """
                    {"issuer":"https://auth.openai.com",
                     "authorization_endpoint":"https://auth.openai.com/api/accounts/authorize",
                     "token_endpoint":"https://auth.openai.com/api/accounts/oauth/token",
                     "revocation_endpoint":"https://auth.openai.com/api/accounts/oauth/revoke",
                     "jwks_uri":"https://auth.openai.com/.well-known/jwks.json",
                     "code_challenge_methods_supported":["S256"],
                     "id_token_signing_alg_values_supported":["RS256"]}""");
        }
    }

    String idToken(String aud, String nonce, long ttlSeconds) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER).audience(aud).subject("user-sub-1")
                    .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(ttlSeconds)))
                    .claim("email", "user@example.com").claim("name", "Test User");
            if (nonce != null) claims.claim("nonce", nonce);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        server = new FakeOAuthServer();
        OpenAiProperties props = new OpenAiProperties("chatgpt", "", "https://api.openai.com/v1",
                "gpt-6.1-sol", "gpt-6-luna", "omni-moderation-latest", "medium", "low", 5, 30, 1000, 2000,
                new OpenAiProperties.Siwc(ISSUER, "https://api.openai.com/v1", "mentalcare", null,
                        "/api/auth/chatgpt/callback", "http://127.0.0.1:8080"), null, null);
        store = new CredentialStore(tmp.resolve("credentials"), mapper, new AesFileKeyCredentialProtector(tmp.resolve("credentials")));
        IdTokenVerifierProvider verifiers = new IdTokenVerifierProvider() {
            @Override
            public IdTokenVerifier forIssuer(String issuer, URI jwksUri) {
                return new IdTokenVerifier(issuer, new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())));
            }
        };
        auth = new ChatGptAuthService(props, new OidcDiscovery(server, mapper, props), server, store, verifiers, mapper);
    }

    static Map<String, String> query(String url) {
        Map<String, String> q = new HashMap<>();
        for (String kv : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] p = kv.split("=", 2);
            q.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), URLDecoder.decode(p[1], StandardCharsets.UTF_8));
        }
        return q;
    }

    /** Runs a full first-time sign-in and returns the callback outcome. */
    ChatGptAuthService.CallbackOutcome signInFresh() {
        String url = auth.start("http://127.0.0.1:5173/settings", false).authorizationUrl();
        Map<String, String> q = query(url);
        server.nonceForIdToken = q.get("nonce");
        return auth.handleCallback(Map.of("code", "code-1", "state", q.get("state"), "client_id", ISSUED_CLIENT,
                "scope", server.scopeInResponse));
    }

    @Test
    void authorizationUrlFollowsTheOpenSourceRegistrationSpec() {
        String url = auth.start("http://127.0.0.1:5173/settings", false).authorizationUrl();
        assertThat(url).startsWith("https://auth.openai.com/api/accounts/authorize?");
        Map<String, String> q = query(url);
        assertThat(q).containsEntry("client_id", "dynamic_agent_client")
                .containsEntry("response_type", "code")
                .containsEntry("redirect_uri", "http://127.0.0.1:8080/api/auth/chatgpt/callback")
                .containsEntry("resource", "https://api.openai.com/v1")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("agent_name_hint", "mentalcare");
        assertThat(q.get("scope")).isEqualTo("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct");
        assertThat(q.get("ext_agent_host_id")).startsWith("urn:uuid:");
        assertThat(q.get("state")).hasSize(43);
        assertThat(q.get("nonce")).hasSize(43);
        assertThat(q.get("code_challenge")).hasSize(43);
        assertThat(q).doesNotContainKeys("id_token_hint", "login_hint", "prompt");
        // host id is stable across attempts
        assertThat(query(auth.start(null, false).authorizationUrl())).containsEntry("ext_agent_host_id", q.get("ext_agent_host_id"));
    }

    @Test
    void successfulSignInStoresEncryptedCredentialsAndGrantsPlanUsage() throws Exception {
        ChatGptAuthService.CallbackOutcome outcome = signInFresh();

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.returnTo()).isEqualTo("http://127.0.0.1:5173/settings");
        Map<String, String> exchange = server.tokenRequests.get(0);
        assertThat(exchange).containsEntry("grant_type", "authorization_code")
                .containsEntry("client_id", ISSUED_CLIENT)
                .containsEntry("code", "code-1")
                .containsEntry("redirect_uri", "http://127.0.0.1:8080/api/auth/chatgpt/callback")
                .containsEntry("resource", "https://api.openai.com/v1");
        assertThat(exchange.get("code_verifier")).hasSize(43);
        assertThat(exchange).doesNotContainKey("client_secret");

        ChatGptAuthService.Status s = auth.status();
        assertThat(s.connected()).isTrue();
        assertThat(s.planUsage()).isTrue();
        assertThat(s.email()).isEqualTo("user@example.com");
        assertThat(s.scopes()).contains("chatgpt.tokens.use.direct");
        assertThat(auth.accessToken()).isEqualTo("access-1");

        // nothing in the credential files is plaintext
        String authFile = Files.readString(tmp.resolve("credentials/chatgpt-auth.json"));
        assertThat(authFile).doesNotContain("access-1").doesNotContain("refresh-1").doesNotContain("user@example.com");
        assertThat(authFile).contains("\"provider\":\"aes-gcm-file-key\"");
    }

    @Test
    void stateMismatchIsRejectedWithoutExchange() {
        auth.start(null, false);
        ChatGptAuthService.CallbackOutcome outcome = auth.handleCallback(Map.of("code", "c", "state", "wrong", "client_id", ISSUED_CLIENT));
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("state_mismatch");
        assertThat(server.tokenRequests).isEmpty();
        assertThat(auth.status().connected()).isFalse();
    }

    @Test
    void stateCannotBeReused() {
        signInFresh();
        String state = query(auth.start(null, false).authorizationUrl()).get("state");
        auth.handleCallback(Map.of("error", "access_denied", "state", state));
        ChatGptAuthService.CallbackOutcome replay = auth.handleCallback(Map.of("code", "c", "state", state));
        assertThat(replay.errorCode()).isEqualTo("state_mismatch");
    }

    @Test
    void accessDeniedStopsWithoutExchange() {
        String state = query(auth.start(null, false).authorizationUrl()).get("state");
        ChatGptAuthService.CallbackOutcome outcome = auth.handleCallback(Map.of("error", "access_denied", "state", state));
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("access_denied");
        assertThat(server.tokenRequests).isEmpty();
    }

    @Test
    void newRegistrationWithoutIssuedClientIdIsIncomplete() {
        String state = query(auth.start(null, false).authorizationUrl()).get("state");
        ChatGptAuthService.CallbackOutcome outcome = auth.handleCallback(Map.of("code", "c", "state", state));
        assertThat(outcome.errorCode()).isEqualTo("registration_incomplete");
        assertThat(server.tokenRequests).isEmpty();

        state = query(auth.start(null, false).authorizationUrl()).get("state");
        outcome = auth.handleCallback(Map.of("code", "c", "state", state, "client_id", "dynamic_agent_client"));
        assertThat(outcome.errorCode()).isEqualTo("registration_incomplete");
    }

    @Test
    void nonceMismatchRejectsTheIdToken() {
        String url = auth.start(null, false).authorizationUrl();
        server.nonceForIdToken = "some-other-nonce";
        ChatGptAuthService.CallbackOutcome outcome = auth.handleCallback(Map.of("code", "c", "state", query(url).get("state"), "client_id", ISSUED_CLIENT));
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("invalid_id_token");
        assertThat(auth.status().connected()).isFalse();
        assertThat(auth.status().registered()).isTrue(); // issued client id was kept for retry
    }

    @Test
    void expiredOrWrongAudienceIdTokenIsRejected() {
        IdTokenVerifier verifier = new IdTokenVerifier(ISSUER, new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())));
        assertThatThrownBy(() -> verifier.verify(idToken(ISSUED_CLIENT, "n", -600), ISSUED_CLIENT, "n"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.code()).isEqualTo(AuthException.Code.INVALID_ID_TOKEN));
        assertThatThrownBy(() -> verifier.verify(idToken("other-client", "n", 300), ISSUED_CLIENT, "n"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.code()).isEqualTo(AuthException.Code.INVALID_ID_TOKEN));
        // token signed by an unknown key
        try {
            RSAKey other = new RSAKeyGenerator(2048).keyID("k1").generate();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(),
                    new JWTClaimsSet.Builder().issuer(ISSUER).audience(ISSUED_CLIENT).subject("s").issueTime(new Date())
                            .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("nonce", "n").build());
            jwt.sign(new RSASSASigner(other));
            assertThatThrownBy(() -> verifier.verify(jwt.serialize(), ISSUED_CLIENT, "n"))
                    .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.code()).isEqualTo(AuthException.Code.INVALID_ID_TOKEN));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void identityLoginWithoutPlanScopeDoesNotEnableInference() {
        server.scopeInResponse = "openid profile email offline_access";
        ChatGptAuthService.CallbackOutcome outcome = signInFresh();

        assertThat(outcome.success()).isTrue();
        ChatGptAuthService.Status s = auth.status();
        assertThat(s.connected()).isTrue();
        assertThat(s.planUsage()).isFalse();
        assertThatThrownBy(() -> auth.accessToken())
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.NO_PLAN_USAGE));
        assertThat(new ChatGptTokenProvider(auth).available()).isFalse();

        // re-consent request carries prompt=consent and the issued client id, no agent_name_hint
        Map<String, String> q = query(auth.start(null, true).authorizationUrl());
        assertThat(q).containsEntry("prompt", "consent").containsEntry("client_id", ISSUED_CLIENT)
                .containsEntry("login_hint", "user@example.com").containsKey("id_token_hint")
                .doesNotContainKey("agent_name_hint");
    }

    @Test
    void expiredAccessTokenIsRefreshedWithRotation() throws Exception {
        signInFresh();
        // force expiry by rewriting the stored record
        ChatGptAccount acc = store.load().orElseThrow();
        store.save(acc.withTokens(acc.accessToken(), Instant.now().minusSeconds(10), acc.refreshToken(), acc.idToken(), acc.scopes()));
        server.nonceForIdToken = null;

        String token = auth.accessToken();

        assertThat(token).isEqualTo("refreshed-access-1");
        Map<String, String> refresh = server.tokenRequests.get(1);
        assertThat(refresh).containsEntry("grant_type", "refresh_token").containsEntry("client_id", ISSUED_CLIENT)
                .containsEntry("refresh_token", "refresh-1").containsEntry("resource", "https://api.openai.com/v1")
                .doesNotContainKey("scope");
        assertThat(store.load().orElseThrow().refreshToken()).isEqualTo("refreshed-refresh-1");
        // second call within the hour does not refresh again
        assertThat(auth.accessToken()).isEqualTo("refreshed-access-1");
        assertThat(server.refreshCount).isEqualTo(1);
    }

    @Test
    void terminalRefreshFailureRequiresSignInButKeepsRegistration() throws Exception {
        signInFresh();
        ChatGptAccount acc = store.load().orElseThrow();
        store.save(acc.withTokens(acc.accessToken(), Instant.now().minusSeconds(10), acc.refreshToken(), acc.idToken(), acc.scopes()));
        server.tokenStatus = 400;
        server.tokenError = "invalid_grant";

        assertThatThrownBy(() -> auth.accessToken())
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.AUTH_REQUIRED));
        ChatGptAuthService.Status s = auth.status();
        assertThat(s.connected()).isFalse();
        assertThat(s.reauthRequired()).isTrue();
        assertThat(s.registered()).isTrue();
        assertThat(store.load().orElseThrow().refreshToken()).isNull();

        // re-login reuses the issued client id (no new registration)
        server.tokenStatus = 200;
        Map<String, String> q = query(auth.start(null, false).authorizationUrl());
        assertThat(q).containsEntry("client_id", ISSUED_CLIENT).doesNotContainKey("agent_name_hint");
    }

    @Test
    void temporaryRefreshFailureKeepsCredentials() throws Exception {
        signInFresh();
        ChatGptAccount acc = store.load().orElseThrow();
        store.save(acc.withTokens(acc.accessToken(), Instant.now().minusSeconds(10), acc.refreshToken(), acc.idToken(), acc.scopes()));
        server.tokenStatus = 503;
        server.tokenError = "temporarily_unavailable";

        assertThatThrownBy(() -> auth.accessToken())
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.PLAN_UNAVAILABLE));
        assertThat(store.load().orElseThrow().refreshToken()).isEqualTo("refresh-1");
        assertThat(auth.status().reauthRequired()).isFalse();
    }

    @Test
    void unauthorizedApiResponseForcesRefreshOnNextCall() {
        signInFresh();
        server.nonceForIdToken = null;
        auth.onUnauthorized();
        assertThat(auth.accessToken()).isEqualTo("refreshed-access-1");
    }

    @Test
    void signOutRevokesRefreshTokenAndKeepsRegistration() {
        signInFresh();
        boolean confirmed = auth.signOut();

        assertThat(confirmed).isTrue();
        assertThat(server.revocations).singleElement().satisfies(f -> {
            assertThat(f).containsEntry("token", "refresh-1").containsEntry("token_type_hint", "refresh_token")
                    .containsEntry("client_id", ISSUED_CLIENT);
        });
        ChatGptAuthService.Status s = auth.status();
        assertThat(s.connected()).isFalse();
        assertThat(s.registered()).isTrue();
        assertThatThrownBy(() -> auth.accessToken())
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.NOT_CONNECTED));

        auth.forget();
        assertThat(auth.status().registered()).isFalse();
    }

    @Test
    void reauthorizationWithDifferentAccountIsRejected() {
        signInFresh();
        String url = auth.start(null, false).authorizationUrl();
        Map<String, String> q = query(url);
        server.nonceForIdToken = q.get("nonce");
        // the fake server always issues subject user-sub-1; simulate a different account by changing the stored subject
        ChatGptAccount acc = store.load().orElseThrow();
        store.save(new ChatGptAccount(1, acc.clientId(), "someone-else", acc.email(), acc.name(), acc.scopes(), acc.status(),
                acc.accessToken(), acc.expiresAt(), acc.refreshToken(), acc.idToken(), acc.savedAt()));
        ChatGptAuthService.CallbackOutcome outcome = auth.handleCallback(Map.of("code", "c2", "state", q.get("state")));
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("identity_mismatch");
    }

    @Test
    void returnedClientIdMustMatchPendingRegistration() {
        signInFresh();
        String state = query(auth.start(null, false).authorizationUrl()).get("state");
        ChatGptAuthService.CallbackOutcome outcome = auth.handleCallback(Map.of("code", "c", "state", state, "client_id", "oaiapp_other"));
        assertThat(outcome.errorCode()).isEqualTo("client_id_mismatch");
    }
}
