package com.agentplatform.agent.exception;

import com.agentplatform.api.web.ApiErrorBody;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final int UNAUTHORIZED_STATUS = 401;
    private static final int INTERNAL_ERROR_STATUS = 500;

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(ApiErrorBody.of(e.getStatusCode().value(), e.getReason()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        if (e.getMessage() != null && e.getMessage().contains("authenticated principal")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiErrorBody.of(UNAUTHORIZED_STATUS, e.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorBody.of(INTERNAL_ERROR_STATUS, e.getMessage()));
    }
}
