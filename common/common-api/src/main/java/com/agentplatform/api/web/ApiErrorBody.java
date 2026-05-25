package com.agentplatform.api.web;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ApiErrorBody {

    private static final String STATUS_FIELD = "status";
    private static final String MESSAGE_FIELD = "message";

    private ApiErrorBody() {}

    public static Map<String, Object> of(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(STATUS_FIELD, status);
        body.put(MESSAGE_FIELD, message);
        return body;
    }

    public static Map<String, Object> withFields(int status, String message, Map<String, Object> extraFields) {
        Map<String, Object> body = of(status, message);
        if (extraFields != null && !extraFields.isEmpty()) {
            body.putAll(extraFields);
        }
        return body;
    }
}
