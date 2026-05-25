package com.agentplatform.agent.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String STATUS_FIELD = "status";
    private static final String MESSAGE_FIELD = "message";
    private static final int UNAUTHORIZED_STATUS = 401;
    private static final int INTERNAL_ERROR_STATUS = 500;

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(responseBody(e.getStatusCode().value(), e.getReason()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        if (e.getMessage() != null && e.getMessage().contains("authenticated principal")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(responseBody(UNAUTHORIZED_STATUS, e.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(responseBody(INTERNAL_ERROR_STATUS, e.getMessage()));
    }

    private static Map<String, Object> responseBody(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(STATUS_FIELD, status);
        body.put(MESSAGE_FIELD, message);
        return body;
    }
}
