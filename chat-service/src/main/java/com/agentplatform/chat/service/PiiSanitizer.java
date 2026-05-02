package com.agentplatform.chat.service;

import com.agentplatform.chat.config.ChatProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Cheap PII / payload sanitiser applied to messages on the way into the DB.
 *
 * <p>Two policies in PR 9:
 * <ol>
 *   <li><b>Truncate big bodies</b> — message {@code content} longer than
 *       {@code agent-platform.chat.max-content-bytes} is cut, with a
 *       {@code [truncated N bytes]} marker appended.</li>
 *   <li><b>Redact long base64 fields in metadata</b> — JSON keys that look
 *       base64-ish (containing {@code base64} / {@code _b64} / equal to
 *       {@code data}) and whose value exceeds
 *       {@code agent-platform.chat.redact-base64-over-bytes} are replaced
 *       with a placeholder so DB rows don't bloat with thumbnails.</li>
 * </ol>
 *
 * <p>PR 13 will extend with regex-based PII rules (phone numbers, GPS coords).
 */
@Component
public class PiiSanitizer {

    private static final Set<String> BASE64_LIKE_KEYS = Set.of(
            "thumb_b64", "data", "image_b64", "image_base64", "audio_b64", "blob_b64");

    private final ChatProperties props;
    private final ObjectMapper mapper;

    public PiiSanitizer(ChatProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public String sanitizeContent(String content) {
        if (content == null) return null;
        int max = props.chat().maxContentBytes();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= max) return content;
        byte[] cut = Arrays.copyOf(bytes, max);
        return new String(cut, StandardCharsets.UTF_8)
                + "\n[truncated " + (bytes.length - max) + " bytes]";
    }

    /** @return JSON-as-text suitable for the {@code metadata} TEXT column, or null if input is null. */
    public String sanitizeMetadata(JsonNode metadata) {
        if (metadata == null || metadata.isNull()) return null;
        return redact(metadata).toString();
    }

    private JsonNode redact(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                String key = e.getKey();
                JsonNode value = e.getValue();
                if (looksBase64(key) && value.isTextual()
                        && value.asText().length() > props.chat().redactBase64OverBytes()) {
                    result.put(key, "<redacted base64 " + value.asText().length() + " bytes>");
                } else {
                    result.set(key, redact(value));
                }
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            node.forEach(child -> result.add(redact(child)));
            return result;
        }
        return node;
    }

    private static boolean looksBase64(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        return BASE64_LIKE_KEYS.contains(lower) || lower.contains("base64") || lower.endsWith("_b64");
    }
}
