package com.mentalcare.api;

import com.mentalcare.ai.LlmException;
import com.mentalcare.api.dto.ApiDtos.ErrorDto;
import com.mentalcare.common.ConflictException;
import com.mentalcare.common.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorDto> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorDto(e.getMessage(), "NOT_FOUND", null, null));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorDto> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(e.getMessage(), "CONFLICT", e.relatedId(), null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorDto> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorDto(e.getMessage(), "BAD_REQUEST", null, null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorDto> validation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ErrorDto("입력값이 올바르지 않습니다.", "VALIDATION", null, fields));
    }

    @ExceptionHandler(LlmException.class)
    public ResponseEntity<ErrorDto> llm(LlmException e) {
        // kind + provider code only: the message may carry provider diagnostics.
        log.warn("AI call failed: kind={} code={}", e.kind(), e.providerCode());
        HttpStatus status = switch (e.kind()) {
            case NOT_CONFIGURED, NOT_CONNECTED, NO_PLAN_USAGE, AUTH_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case PLAN_LIMIT, RATE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            case PLAN_NOT_ELIGIBLE, ROUTE_NOT_SUPPORTED -> HttpStatus.FORBIDDEN;
            case UNSUPPORTED_CAPABILITY, BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(new ErrorDto(e.userMessage(), e.kind().name(), null, null));
    }

    @ExceptionHandler(com.mentalcare.auth.AuthException.class)
    public ResponseEntity<ErrorDto> auth(com.mentalcare.auth.AuthException e) {
        log.warn("Auth operation failed: code={}", e.code());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorDto(e.userMessage(), e.code().name(), null, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDto> unexpected(Exception e) {
        // Class name only: exception messages (SQL, IO) could echo user content or local paths.
        log.error("Unexpected error: {}", e.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorDto("서버 오류가 발생했습니다.", "INTERNAL", null, null));
    }
}
