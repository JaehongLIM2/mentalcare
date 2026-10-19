package com.mentalcare.auth;

import java.net.URI;
import java.util.Map;

/**
 * HTTP boundary for the OpenAI authorization server (token, revocation, discovery).
 * Implementations never log request bodies or responses: they contain authorization codes and tokens.
 */
public interface OAuthTransport {

    record HttpResult(int status, String body) {
        public boolean ok() { return status / 100 == 2; }
    }

    /** POST application/x-www-form-urlencoded. */
    HttpResult postForm(URI uri, Map<String, String> form);

    /** GET with Accept: application/json. */
    HttpResult getJson(URI uri);
}
