package com.agentplatform.agent.ai;

import com.agentplatform.protocol.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
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
            "videos.list_recent",
            "media.gallery.browse");
    private static final Set<String> EMPTY_STRING_FIELDS = Set.of(
            "bucket_id",
            "category",
            "name_contains");
    private static final Set<String> DATE_FIELDS = Set.of(
            "date_after",
            "date_before");
    private static final Map<String, Integer> LIST_LIMIT_CAPS = new HashMap<>(Map.of(
            "photos.list_recent", 8,
            "photos.list_by_album", 8,
            "photos.recent_screenshots", 8,
            "media.gallery.browse", 80
    ));

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
            normalized.set(key, normalizeValue(spec.name(), key, value));
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

    private JsonNode normalizeValue(String toolName, String key, JsonNode value) {
        if (!"limit".equals(key) || value == null || !value.isNumber()) {
            return value;
        }
        Integer cap = LIST_LIMIT_CAPS.get(toolName);
        if (cap == null || value.asInt() <= cap) {
            return value;
        }
        return mapper.getNodeFactory().numberNode(cap);
    }
}
