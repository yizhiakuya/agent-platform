package com.agentplatform.agent.ai;

import com.agentplatform.protocol.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Normalizes common LLM default-value mistakes before media tools hit Android.
 */
@Component
@Order(10)
public class PhotoToolArgsSanitizer implements ToolPreInterceptor {

    private static final Set<String> PHOTO_TOOLS = Set.of(
            "photos.list_recent",
            "photos.list_by_album",
            "photos.recent_screenshots",
            "photos.semantic_candidates",
            "videos.list_recent");
    private static final Set<String> EMPTY_STRING_FIELDS = Set.of(
            "bucket_id",
            "name_contains");
    private static final Set<String> DATE_FIELDS = Set.of(
            "date_after",
            "date_before");

    private final ObjectMapper mapper;

    public PhotoToolArgsSanitizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public JsonNode before(UUID userId, UUID deviceId, ToolSpec spec, JsonNode args, Map<String, Object> requestCtx) {
        if (spec == null || !PHOTO_TOOLS.contains(spec.name()) || args == null || !args.isObject()) {
            return args;
        }

        ObjectNode normalized = mapper.createObjectNode();
        args.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (shouldDrop(key, value)) {
                return;
            }
            normalized.set(key, value);
        });
        return normalized;
    }

    private static boolean shouldDrop(String key, JsonNode value) {
        if (value == null || value.isNull()) {
            return true;
        }
        if (EMPTY_STRING_FIELDS.contains(key) && value.isTextual() && value.asText().trim().isEmpty()) {
            return true;
        }
        return DATE_FIELDS.contains(key) && value.isNumber() && value.asLong() <= 0L;
    }
}
