package com.agentmemory.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps bad-input failures from the {@code /api/v1} API to {@code 400 Bad Request} with a JSON
 * {@code {"error": ...}} body (issue #35). Scoped to {@link ApiV1Controller} only
 * ({@code assignableTypes}) so it does not change the status semantics of the other controllers
 * ({@code HookController}'s 202/429/400/503, {@code HealthController}).
 *
 * <p>An {@link IllegalArgumentException} here means the client sent something invalid — a malformed
 * workspace/project slug (rejected by the {@code core} value types), a non-positive {@code limit}, or
 * a negative {@code offset} — which is a 400, not a server fault. Without this it would surface as a
 * 500.
 */
@RestControllerAdvice(assignableTypes = ApiV1Controller.class)
public class ApiV1ExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
