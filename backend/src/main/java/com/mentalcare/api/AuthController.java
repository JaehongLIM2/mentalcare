package com.mentalcare.api;

import com.mentalcare.auth.AuthException;
import com.mentalcare.auth.ChatGptAuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Sign in with ChatGPT endpoints. Tokens never leave the backend; the browser only ever sees the
 * authorization URL (sent once, not logged) and a status object without credentials.
 */
@RestController
@RequestMapping("/api/auth/chatgpt")
public class AuthController {

    public record StartRequest(@Size(max = 500) String returnTo, Boolean reconsent) {}
    public record StartResponse(String authorizationUrl) {}
    public record SignOutResponse(boolean revocationConfirmed) {}

    private final ChatGptAuthService auth;

    public AuthController(ChatGptAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/start")
    public StartResponse start(@RequestBody(required = false) StartRequest request) {
        String returnTo = request == null ? null : request.returnTo();
        boolean reconsent = request != null && Boolean.TRUE.equals(request.reconsent());
        return new StartResponse(auth.start(returnTo, reconsent).authorizationUrl());
    }

    /** Loopback redirect target registered with OpenAI. Query: code, state, client_id?, scope?, error?. */
    @GetMapping("/callback")
    public void callback(@RequestParam Map<String, String> query, HttpServletResponse response) {
        ChatGptAuthService.CallbackOutcome outcome = auth.handleCallback(query);
        String target = outcome.returnTo();
        String sep = target.contains("?") ? "&" : "?";
        String suffix = outcome.success()
                ? "chatgpt=connected"
                : "chatgpt=error&reason=" + URLEncoder.encode(outcome.errorCode(), StandardCharsets.UTF_8);
        response.setStatus(HttpStatus.FOUND.value());
        response.setHeader("Location", target + sep + suffix);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @GetMapping("/status")
    public ChatGptAuthService.Status status() {
        return auth.status();
    }

    @PostMapping("/signout")
    public SignOutResponse signOut() {
        return new SignOutResponse(auth.signOut());
    }

    @PostMapping("/forget")
    public ResponseEntity<Void> forget() {
        auth.forget();
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiDtosError> authError(AuthException e) {
        HttpStatus status = switch (e.code()) {
            case NETWORK, DISCOVERY -> HttpStatus.BAD_GATEWAY;
            case INVALID_REQUEST, STATE_MISMATCH -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(new ApiDtosError(e.userMessage(), e.code().name()));
    }

    public record ApiDtosError(String message, String kind) {}
}
