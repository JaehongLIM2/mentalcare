package com.mentalcare.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalcare.config.OpenAiProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/**
 * Loads {@code <issuer>/.well-known/openid-configuration}. Endpoints are taken from the document,
 * never hard-coded, and each must live on the issuer's origin (mirrors OpenAI's reference client).
 */
@Component
public class OidcDiscovery {

    public record Endpoints(String issuer, URI authorization, URI token, URI jwks, URI revocation, URI userinfo) {}

    private static final Duration TTL = Duration.ofHours(6);

    private final OAuthTransport transport;
    private final ObjectMapper mapper;
    private final String issuer;
    private volatile Endpoints cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public OidcDiscovery(OAuthTransport transport, ObjectMapper mapper, OpenAiProperties props) {
        this.transport = transport;
        this.mapper = mapper;
        this.issuer = props.siwcOrDefault().issuerOrDefault().replaceAll("/+$", "");
    }

    public String issuer() {
        return issuer;
    }

    public Endpoints endpoints() {
        Endpoints e = cached;
        if (e != null && cachedAt.plus(TTL).isAfter(Instant.now())) return e;
        synchronized (this) {
            if (cached != null && cachedAt.plus(TTL).isAfter(Instant.now())) return cached;
            cached = load();
            cachedAt = Instant.now();
            return cached;
        }
    }

    private Endpoints load() {
        OAuthTransport.HttpResult result = transport.getJson(URI.create(issuer + "/.well-known/openid-configuration"));
        if (!result.ok()) {
            throw new AuthException(AuthException.Code.DISCOVERY, "Discovery HTTP " + result.status());
        }
        try {
            JsonNode doc = mapper.readTree(result.body());
            String docIssuer = doc.path("issuer").asText("").replaceAll("/+$", "");
            if (!issuer.equals(docIssuer)) {
                throw new AuthException(AuthException.Code.DISCOVERY, "Discovery issuer mismatch");
            }
            if (!doc.path("code_challenge_methods_supported").toString().contains("S256")) {
                throw new AuthException(AuthException.Code.DISCOVERY, "S256 not supported by issuer");
            }
            return new Endpoints(issuer,
                    sameOrigin(doc, "authorization_endpoint", true),
                    sameOrigin(doc, "token_endpoint", true),
                    sameOrigin(doc, "jwks_uri", true),
                    sameOrigin(doc, "revocation_endpoint", false),
                    sameOrigin(doc, "userinfo_endpoint", false));
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException(AuthException.Code.DISCOVERY, "Discovery document unreadable", e);
        }
    }

    private URI sameOrigin(JsonNode doc, String field, boolean required) {
        String value = doc.path(field).asText(null);
        if (value == null || value.isBlank()) {
            if (required) throw new AuthException(AuthException.Code.DISCOVERY, "Discovery missing " + field);
            return null;
        }
        URI uri = URI.create(value);
        URI iss = URI.create(issuer);
        boolean ok = "https".equalsIgnoreCase(uri.getScheme())
                && iss.getHost() != null && iss.getHost().equalsIgnoreCase(uri.getHost())
                && uri.getPort() == iss.getPort();
        if (!ok) {
            throw new AuthException(AuthException.Code.DISCOVERY, field + " is not on the issuer origin");
        }
        return uri;
    }
}
