package com.mentalcare.auth;

import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

/** Creates (and caches per JWKS URL) the verifier. Tests replace this bean with a fixed key set. */
@Component
public class IdTokenVerifierProvider {

    private final ConcurrentHashMap<String, IdTokenVerifier> cache = new ConcurrentHashMap<>();

    public IdTokenVerifier forIssuer(String issuer, URI jwksUri) {
        return cache.computeIfAbsent(jwksUri.toString(), k -> {
            try {
                return IdTokenVerifier.remote(issuer, jwksUri.toURL());
            } catch (MalformedURLException e) {
                throw new AuthException(AuthException.Code.DISCOVERY, "Bad jwks_uri", e);
            }
        });
    }
}
