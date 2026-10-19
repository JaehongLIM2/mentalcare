package com.mentalcare.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the API usable only from a browser on this machine, even though the app has no login:
 * <ul>
 *   <li><b>Host</b> must be loopback (defeats DNS rebinding: evil.com → 127.0.0.1 would carry Host: evil.com)</li>
 *   <li><b>Origin</b>, when present, must be loopback (defeats cross-site POST/PATCH from other sites)</li>
 *   <li><b>Sec-Fetch-Site: cross-site</b> is rejected for /api, except the OAuth callback which is a
 *       top-level navigation back from auth.openai.com</li>
 * </ul>
 * No CORS headers are ever sent, so cross-origin scripts cannot read responses either.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalRequestGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LocalRequestGuardFilter.class);

    private final List<String> allowedHosts;
    private final String callbackPath;

    public LocalRequestGuardFilter(AppProperties app, OpenAiProperties openAi) {
        this.allowedHosts = app.allowedHostsOrDefault().stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        this.callbackPath = openAi.siwcOrDefault().callbackPathOrDefault();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = hostWithoutPort(request.getHeader("Host"));
        if (host == null || !allowedHosts.contains(host)) {
            reject(response, "host");
            return;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !"null".equals(origin) && !isAllowedOrigin(origin)) {
            reject(response, "origin");
            return;
        }
        String path = request.getRequestURI();
        boolean api = path != null && path.startsWith("/api/");
        boolean callback = path != null && path.equals(callbackPath) && "GET".equalsIgnoreCase(request.getMethod());
        String site = request.getHeader("Sec-Fetch-Site");
        if (api && !callback && site != null && "cross-site".equalsIgnoreCase(site)) {
            reject(response, "sec-fetch-site");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isAllowedOrigin(String origin) {
        try {
            URI u = URI.create(origin);
            String h = u.getHost();
            if (h == null) return false;
            h = h.toLowerCase(Locale.ROOT);
            if (h.equals("::1")) h = "[::1]";
            return allowedHosts.contains(h);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String hostWithoutPort(String header) {
        if (header == null || header.isBlank()) return null;
        String h = header.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) { // IPv6 literal
            int end = h.indexOf(']');
            return end < 0 ? null : h.substring(0, end + 1);
        }
        int colon = h.indexOf(':');
        return colon < 0 ? h : h.substring(0, colon);
    }

    private void reject(HttpServletResponse response, String why) throws IOException {
        log.warn("request rejected by local guard ({})", why);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"이 앱은 로컬(127.0.0.1)에서만 사용할 수 있습니다.\",\"kind\":\"LOCAL_ONLY\"}");
    }
}
