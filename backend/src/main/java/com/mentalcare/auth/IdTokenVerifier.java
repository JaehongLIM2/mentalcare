package com.mentalcare.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.net.URL;
import java.text.ParseException;
import java.util.List;
import java.util.Set;

/**
 * Validates an OpenID Connect ID token from OpenAI:
 * RS256 signature against the issuer's JWKS, {@code iss}, {@code aud} = issued client_id, {@code exp}
 * (5 s skew), {@code nonce} of the pending attempt, {@code azp} when present, non-empty {@code sub}.
 */
public class IdTokenVerifier {

    /** Identity claims the app keeps. {@code subject} is the stable account key; email is display only. */
    public record Identity(String subject, String email, String name) {}

    private static final int CLOCK_SKEW_SECONDS = 5;

    private final String issuer;
    private final JWKSource<SecurityContext> jwkSource;

    public IdTokenVerifier(String issuer, JWKSource<SecurityContext> jwkSource) {
        this.issuer = issuer;
        this.jwkSource = jwkSource;
    }

    /** Builds a verifier that fetches (and caches) the issuer's JWKS from {@code jwksUrl}. */
    public static IdTokenVerifier remote(String issuer, URL jwksUrl) {
        JWKSource<SecurityContext> source = JWKSourceBuilder.create(jwksUrl).cache(true).retrying(true).build();
        return new IdTokenVerifier(issuer, source);
    }

    /**
     * @param idToken       compact JWS
     * @param clientId      issued client id (expected audience)
     * @param expectedNonce nonce of the pending attempt, or null when verifying a refreshed token
     */
    public Identity verify(String idToken, String clientId, String expectedNonce) {
        try {
            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
            JWTClaimsSet exact = new JWTClaimsSet.Builder().issuer(issuer).build();
            DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier = new DefaultJWTClaimsVerifier<>(
                    Set.of(clientId), exact, Set.of("iss", "aud", "exp", "iat", "sub"), Set.of());
            claimsVerifier.setMaxClockSkew(CLOCK_SKEW_SECONDS);
            processor.setJWTClaimsSetVerifier(claimsVerifier);
            JWTClaimsSet claims = processor.process(idToken, null);

            if (expectedNonce != null) {
                String nonce = claims.getStringClaim("nonce");
                if (nonce == null || !Pkce.constantTimeEquals(nonce, expectedNonce)) {
                    throw new AuthException(AuthException.Code.INVALID_ID_TOKEN, "nonce mismatch");
                }
            }
            List<String> aud = claims.getAudience();
            String azp = claims.getStringClaim("azp");
            if (azp != null && !azp.equals(clientId)) {
                throw new AuthException(AuthException.Code.INVALID_ID_TOKEN, "azp mismatch");
            }
            if (aud != null && aud.size() > 1 && (azp == null || !azp.equals(clientId))) {
                throw new AuthException(AuthException.Code.INVALID_ID_TOKEN, "multiple audiences without azp");
            }
            String sub = claims.getSubject();
            if (sub == null || sub.isBlank()) {
                throw new AuthException(AuthException.Code.INVALID_ID_TOKEN, "missing sub");
            }
            return new Identity(sub, claims.getStringClaim("email"), claims.getStringClaim("name"));
        } catch (AuthException e) {
            throw e;
        } catch (BadJOSEException | ParseException e) {
            throw new AuthException(AuthException.Code.INVALID_ID_TOKEN, "ID token rejected: " + e.getClass().getSimpleName());
        } catch (JOSEException e) {
            throw new AuthException(AuthException.Code.NETWORK, "Key service unavailable", e);
        }
    }
}
