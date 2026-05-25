package com.agentplatform.auth.exception;

import com.agentplatform.api.web.ApiErrorBody;
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
    private static final String ERRORS_FIELD = "errors";
    private static final String VALIDATION_FAILED_MESSAGE = "Validation failed";
    private static final int BAD_REQUEST_STATUS = 400;
    private static final int UNAUTHORIZED_STATUS = 401;
    private static final int INTERNAL_ERROR_STATUS = 500;

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(ApiErrorBody.of(e.getStatusCode().value(), e.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> errors = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            errors.put(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(ApiErrorBody.withFields(BAD_REQUEST_STATUS, VALIDATION_FAILED_MESSAGE,
                        Map.of(ERRORS_FIELD, errors)));
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
