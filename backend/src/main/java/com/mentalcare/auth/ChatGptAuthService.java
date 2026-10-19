package com.mentalcare.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalcare.ai.LlmException;
import com.mentalcare.ai.LlmException.Kind;
import com.mentalcare.config.OpenAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Sign in with ChatGPT for an open-source, locally hosted app, following
 * developers.openai.com/siwc/token-sharing-open-source:
 * dynamic registration ({@code client_id=dynamic_agent_client} → issued id), Authorization Code + PKCE,
 * state + nonce, ID token validation, granted-scope check, rotating refresh tokens, revocation on sign-out.
 *
 * <p>Nothing here ever logs an authorization code, verifier, token or the authorization URL
 * (it may carry {@code id_token_hint}).
 */
@Service
public class ChatGptAuthService {

    private static final Logger log = LoggerFactory.getLogger(ChatGptAuthService.class);

    public static final String DYNAMIC_CLIENT = "dynamic_agent_client";
    private static final Pattern CLIENT_ID = Pattern.compile("^[a-zA-Z0-9_-]{1,200}$");
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(90);
    private static final Set<String> REAUTH_CODES = Set.of("invalid_grant", "invalid_refresh_token", "token_expired",
            "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused");

    public record StartResult(String authorizationUrl) {}

    /** Result of the browser callback: where to send the browser next and, on failure, a short code. */
    public record CallbackOutcome(boolean success, String returnTo, String errorCode) {}

    public record Status(String mode, boolean connected, boolean reauthRequired, boolean registered,
                         String email, String name, boolean planUsage, List<String> scopes, Instant expiresAt,
                         String hostId, String credentialProvider, String credentialsDir, boolean apiKeyConfigured) {}

    private record Pending(String state, String nonce, String verifier, String clientIdUsed,
                           boolean newRegistration, String returnTo, Instant createdAt) {}

    private final OpenAiProperties props;
    private final OidcDiscovery discovery;
    private final OAuthTransport transport;
    private final CredentialStore store;
    private final IdTokenVerifierProvider verifiers;
    private final ObjectMapper mapper;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private volatile boolean forceRefresh = false;

    public ChatGptAuthService(OpenAiProperties props, OidcDiscovery discovery, OAuthTransport transport,
                              CredentialStore store, IdTokenVerifierProvider verifiers, ObjectMapper mapper) {
        this.props = props;
        this.discovery = discovery;
        this.transport = transport;
        this.store = store;
        this.verifiers = verifiers;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ start

    /**
     * @param returnTo  loopback URL of the UI to return to after the callback (validated; defaults to "/")
     * @param reconsent true to force the consent screen again (missing plan-usage scope)
     */
    public StartResult start(String returnTo, boolean reconsent) {
        OidcDiscovery.Endpoints ep = discovery.endpoints();
        Optional<ChatGptAccount> existing = safeLoad();
        String clientId = existing.map(ChatGptAccount::clientId).filter(this::validClientId).orElse(DYNAMIC_CLIENT);
        boolean newRegistration = DYNAMIC_CLIENT.equals(clientId);
        OpenAiProperties.Siwc siwc = props.siwcOrDefault();

        String state = Pkce.randomToken();
        String nonce = Pkce.randomToken();
        String verifier = Pkce.randomToken();

        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", clientId);
        q.put("response_type", "code");
        q.put("redirect_uri", redirectUri());
        q.put("scope", siwc.scopesOrDefault());
        q.put("resource", siwc.resourceOrDefault());
        q.put("state", state);
        q.put("nonce", nonce);
        q.put("code_challenge_method", "S256");
        q.put("code_challenge", Pkce.challenge(verifier));
        q.put("ext_agent_host_id", store.hostId());
        if (newRegistration) {
            q.put("agent_name_hint", siwc.appNameOrDefault());
        } else {
            existing.map(ChatGptAccount::email).filter(e -> e != null && !e.isBlank()).ifPresent(e -> q.put("login_hint", e));
            existing.map(ChatGptAccount::idToken).filter(t -> t != null && !t.isBlank()).ifPresent(t -> q.put("id_token_hint", t));
        }
        if (reconsent) {
            q.put("prompt", "consent");
        }

        String url = ep.authorization() + "?" + encode(q);
        expirePending();
        pending.put(state, new Pending(state, nonce, verifier, clientId, newRegistration, sanitizeReturnTo(returnTo), Instant.now()));
        log.info("siwc start registration={} reconsent={}", newRegistration, reconsent);
        return new StartResult(url);
    }

    // ------------------------------------------------------------------ callback

    public CallbackOutcome handleCallback(Map<String, String> query) {
        Pending p = takePending(query.get("state"));
        if (p == null) {
            log.warn("siwc callback rejected: state mismatch or expired");
            return new CallbackOutcome(false, "/", "state_mismatch");
        }
        try {
            String error = query.get("error");
            if (error != null) {
                log.info("siwc callback error={}", error.replaceAll("[^a-zA-Z0-9_\\-]", ""));
                return new CallbackOutcome(false, p.returnTo(), "access_denied".equals(error) ? "access_denied" : "provider_error");
            }
            String code = query.get("code");
            if (code == null || code.isBlank()) {
                return new CallbackOutcome(false, p.returnTo(), "missing_code");
            }
            String clientId = resolveClientId(p, query.get("client_id"));
            Optional<ChatGptAccount> existing = safeLoad();
            // Persist the issued client id before the exchange so a failed exchange can reuse the registration.
            if (p.newRegistration() && existing.isEmpty()) {
                store.save(new ChatGptAccount(1, clientId, null, null, null, List.of(), ChatGptAccount.DISCONNECTED,
                        null, null, null, null, Instant.now()));
            }

            TokenResponse tokens = exchangeCode(clientId, code, p.verifier());
            List<String> scopes = tokens.scopes != null ? tokens.scopes : parseScopes(query.get("scope"));
            OidcDiscovery.Endpoints ep = discovery.endpoints();
            IdTokenVerifier.Identity identity = verifiers.forIssuer(ep.issuer(), ep.jwks())
                    .verify(tokens.idToken, clientId, p.nonce());

            if (existing.isPresent() && existing.get().subject() != null && !existing.get().subject().equals(identity.subject())
                    && !p.newRegistration()) {
                log.warn("siwc callback rejected: identity differs from the registered account");
                return new CallbackOutcome(false, p.returnTo(), "identity_mismatch");
            }

            ChatGptAccount account = new ChatGptAccount(1, clientId, identity.subject(), identity.email(), identity.name(),
                    scopes, ChatGptAccount.CONNECTED, tokens.accessToken,
                    Instant.now().plusSeconds(tokens.expiresIn), tokens.refreshToken, tokens.idToken, Instant.now());
            store.save(account);
            forceRefresh = false;
            log.info("siwc connected planUsage={} scopes={}", account.planUsageGranted(), scopes.size());
            return new CallbackOutcome(true, p.returnTo(), null);
        } catch (AuthException e) {
            log.warn("siwc callback failed code={} reason={}", e.code(), e.getMessage());
            return new CallbackOutcome(false, p.returnTo(), e.code().name().toLowerCase());
        }
    }

    private String resolveClientId(Pending p, String returned) {
        if (returned != null && !returned.isBlank()) {
            if (!validClientId(returned)) {
                throw new AuthException(AuthException.Code.REGISTRATION_INCOMPLETE, "returned client_id is not acceptable");
            }
            if (!p.newRegistration() && !returned.equals(p.clientIdUsed())) {
                throw new AuthException(AuthException.Code.CLIENT_ID_MISMATCH, "client_id differs from pending request");
            }
            return returned;
        }
        if (p.newRegistration()) {
            throw new AuthException(AuthException.Code.REGISTRATION_INCOMPLETE, "no issued client_id in callback");
        }
        return p.clientIdUsed();
    }

    private boolean validClientId(String id) {
        return id != null && CLIENT_ID.matcher(id).matches() && !DYNAMIC_CLIENT.equals(id);
    }

    // ------------------------------------------------------------------ tokens

    private record TokenResponse(String accessToken, String refreshToken, String idToken, long expiresIn, List<String> scopes) {}

    private TokenResponse exchangeCode(String clientId, String code, String verifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", clientId);
        form.put("code", code);
        form.put("code_verifier", verifier);
        form.put("redirect_uri", redirectUri());
        form.put("resource", props.siwcOrDefault().resourceOrDefault());
        OAuthTransport.HttpResult r = transport.postForm(discovery.endpoints().token(), form);
        if (!r.ok()) {
            throw new AuthException(AuthException.Code.TOKEN_EXCHANGE, "token endpoint HTTP " + r.status() + " error=" + errorCode(r.body()));
        }
        return parseTokens(r.body());
    }

    private TokenResponse parseTokens(String body) {
        try {
            JsonNode n = mapper.readTree(body);
            String access = n.path("access_token").asText(null);
            if (access == null || access.isBlank()) {
                throw new AuthException(AuthException.Code.TOKEN_EXCHANGE, "token response without access_token");
            }
            long expiresIn = n.path("expires_in").asLong(3600);
            String scope = n.path("scope").asText(null);
            return new TokenResponse(access, n.path("refresh_token").asText(null), n.path("id_token").asText(null),
                    expiresIn, scope == null ? null : parseScopes(scope));
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException(AuthException.Code.TOKEN_EXCHANGE, "token response unreadable");
        }
    }

    /** Current access token for API calls; refreshes when within the margin. Throws LlmException for callers in the AI layer. */
    public synchronized String accessToken() {
        ChatGptAccount acc = safeLoad().orElse(null);
        if (acc == null || ChatGptAccount.DISCONNECTED.equals(acc.status())) {
            throw new LlmException(Kind.NOT_CONNECTED, "ChatGPT account not connected");
        }
        if (ChatGptAccount.REAUTH_REQUIRED.equals(acc.status()) || !acc.connected()) {
            throw new LlmException(Kind.AUTH_REQUIRED, "ChatGPT session requires sign-in");
        }
        if (!acc.planUsageGranted()) {
            throw new LlmException(Kind.NO_PLAN_USAGE, "chatgpt.tokens.use.direct not granted");
        }
        boolean nearExpiry = acc.expiresAt() == null || acc.expiresAt().minus(REFRESH_MARGIN).isBefore(Instant.now());
        if (!nearExpiry && !forceRefresh) {
            return acc.accessToken();
        }
        return refresh(acc).accessToken();
    }

    private ChatGptAccount refresh(ChatGptAccount acc) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", acc.clientId());
        form.put("refresh_token", acc.refreshToken());
        form.put("resource", props.siwcOrDefault().resourceOrDefault());
        OAuthTransport.HttpResult r;
        try {
            r = transport.postForm(discovery.endpoints().token(), form);
        } catch (AuthException e) {
            return temporaryRefreshFailure(acc, "network");
        }
        if (r.ok()) {
            TokenResponse t = parseTokens(r.body());
            if (t.idToken != null) {
                OidcDiscovery.Endpoints ep = discovery.endpoints();
                IdTokenVerifier.Identity id = verifiers.forIssuer(ep.issuer(), ep.jwks()).verify(t.idToken, acc.clientId(), null);
                if (acc.subject() != null && !acc.subject().equals(id.subject())) {
                    throw new LlmException(Kind.AUTH_REQUIRED, "refreshed identity differs from stored account");
                }
            }
            ChatGptAccount updated = acc.withTokens(t.accessToken, Instant.now().plusSeconds(t.expiresIn),
                    t.refreshToken, t.idToken, t.scopes);
            store.save(updated);
            forceRefresh = false;
            log.info("siwc token refreshed");
            return updated;
        }
        String code = errorCode(r.body());
        if (r.status() == 400 || r.status() == 401) {
            if (code == null || REAUTH_CODES.contains(code) || "invalid_client".equals(code)) {
                // Terminal: discard unusable tokens, keep the registration so re-login reuses the client id.
                store.save(acc.withoutTokens(ChatGptAccount.REAUTH_REQUIRED));
                log.warn("siwc refresh terminal failure code={}", code);
                throw new LlmException(Kind.AUTH_REQUIRED, "refresh rejected: " + code, code, null);
            }
        }
        return temporaryRefreshFailure(acc, "http " + r.status());
    }

    private ChatGptAccount temporaryRefreshFailure(ChatGptAccount acc, String why) {
        log.warn("siwc refresh temporarily failed ({}); keeping credentials", why);
        if (acc.expiresAt() != null && acc.expiresAt().isAfter(Instant.now()) && !forceRefresh) {
            return acc; // still usable
        }
        throw new LlmException(Kind.PLAN_UNAVAILABLE, "token refresh unavailable (" + why + ")");
    }

    /** The API rejected the bearer (401): verify on the next call by forcing a refresh. */
    public void onUnauthorized() {
        forceRefresh = true;
    }

    // ------------------------------------------------------------------ status / sign-out

    public Status status() {
        Optional<ChatGptAccount> acc = safeLoad();
        ChatGptAccount a = acc.orElse(null);
        return new Status(props.mode().name(),
                a != null && a.connected(),
                a != null && ChatGptAccount.REAUTH_REQUIRED.equals(a.status()),
                a != null && validClientId(a.clientId()),
                a == null ? null : a.email(),
                a == null ? null : a.name(),
                a != null && a.connected() && a.planUsageGranted(),
                a == null || a.scopes() == null ? List.of() : a.scopes(),
                a == null ? null : a.expiresAt(),
                store.hostId(),
                store.protector().id(),
                store.directory().toAbsolutePath().normalize().toString(),
                props.hasApiKey());
    }

    /** Revokes the refresh token (best effort, 2 attempts) and clears local tokens. Registration is kept. */
    public synchronized boolean signOut() {
        Optional<ChatGptAccount> acc = safeLoad();
        if (acc.isEmpty()) return true;
        ChatGptAccount a = acc.get();
        boolean confirmed = false;
        if (a.refreshToken() != null) {
            confirmed = revoke(a);
        }
        store.save(a.withoutTokens(ChatGptAccount.DISCONNECTED));
        log.info("siwc signed out revocationConfirmed={}", confirmed);
        return confirmed;
    }

    /** Deletes the registration and credentials entirely. */
    public synchronized void forget() {
        safeLoad().filter(a -> a.refreshToken() != null).ifPresent(this::revoke);
        store.delete();
        log.info("siwc registration deleted");
    }

    private boolean revoke(ChatGptAccount a) {
        URI endpoint;
        try {
            endpoint = discovery.endpoints().revocation();
        } catch (AuthException e) {
            return false;
        }
        if (endpoint == null) return false;
        Map<String, String> form = new LinkedHashMap<>();
        form.put("token", a.refreshToken());
        form.put("token_type_hint", "refresh_token");
        form.put("client_id", a.clientId());
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                OAuthTransport.HttpResult r = transport.postForm(endpoint, form);
                if (r.ok()) return true;
                if (r.status() < 500) return false;
            } catch (AuthException ignored) {
                // retry once
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    public String redirectUri() {
        OpenAiProperties.Siwc siwc = props.siwcOrDefault();
        String base = siwc.publicBaseUrlOrDefault().replaceAll("/+$", "");
        String path = siwc.callbackPathOrDefault();
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    private Pending takePending(String state) {
        if (state == null || state.isBlank()) return null;
        expirePending();
        Pending found = null;
        for (Pending p : pending.values()) {
            if (Pkce.constantTimeEquals(p.state(), state)) {
                found = p;
            }
        }
        if (found == null) return null;
        pending.remove(found.state());
        return found;
    }

    private void expirePending() {
        Instant cutoff = Instant.now().minus(PENDING_TTL);
        pending.values().removeIf(p -> p.createdAt().isBefore(cutoff));
    }

    private Optional<ChatGptAccount> safeLoad() {
        try {
            return store.load();
        } catch (AuthException e) {
            log.warn("credential store unreadable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    static List<String> parseScopes(String scope) {
        if (scope == null || scope.isBlank()) return List.of();
        return new ArrayList<>(Arrays.stream(scope.trim().split("\\s+")).filter(s -> !s.isBlank()).toList());
    }

    private String errorCode(String body) {
        try {
            JsonNode n = mapper.readTree(body);
            if (n.path("error").isTextual()) return n.path("error").asText();
            if (n.path("error").path("code").isTextual()) return n.path("error").path("code").asText();
        } catch (Exception ignored) {
            // not JSON
        }
        return null;
    }

    /** Only loopback http(s) URLs are accepted as a post-login destination; anything else becomes "/". */
    static String sanitizeReturnTo(String returnTo) {
        if (returnTo == null || returnTo.isBlank()) return "/";
        if (returnTo.startsWith("/") && !returnTo.startsWith("//") && !returnTo.contains("\\")) return returnTo;
        try {
            URI u = URI.create(returnTo);
            String host = u.getHost();
            boolean loopback = host != null && (host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost") || host.equals("[::1]") || host.equals("::1"));
            boolean http = "http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme());
            if (loopback && http && u.getUserInfo() == null) {
                return u.toString();
            }
        } catch (IllegalArgumentException ignored) {
            // invalid URL
        }
        return "/";
    }

    private static String encode(Map<String, String> q) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
