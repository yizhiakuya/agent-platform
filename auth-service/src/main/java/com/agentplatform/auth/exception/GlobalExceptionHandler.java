package com.agentplatform.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String STATUS_FIELD = "status";
    private static final String MESSAGE_FIELD = "message";
    private static final String ERRORS_FIELD = "errors";
    private static final String VALIDATION_FAILED_MESSAGE = "Validation failed";
    private static final int BAD_REQUEST_STATUS = 400;
    private static final int UNAUTHORIZED_STATUS = 401;
    private static final int INTERNAL_ERROR_STATUS = 500;

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(responseBody(e.getStatusCode().value(), e.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> errors = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            errors.put(fe.getField(), fe.getDefaultMessage());
        }
        Map<String, Object> body = responseBody(BAD_REQUEST_STATUS, VALIDATION_FAILED_MESSAGE);
        body.put(ERRORS_FIELD, errors);
        return ResponseEntity.badRequest().body(body);
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
