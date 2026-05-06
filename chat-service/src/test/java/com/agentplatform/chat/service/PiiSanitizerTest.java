package com.agentplatform.chat.service;

import com.agentplatform.chat.config.ChatProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiiSanitizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PiiSanitizer sanitizer = new PiiSanitizer(
            new ChatProperties(
                    new ChatProperties.Jwt("test-secret", "test-issuer"),
                    new ChatProperties.Chat(10_000, 8)),
            mapper);

    @Test
    void preservesThumbnailsButRedactsFullVisionImages() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode result = mapper.createObjectNode();
        ArrayNode photos = mapper.createArrayNode();
        ObjectNode photo = mapper.createObjectNode();
        photo.put("thumb_b64", "123456789ABCDE");
        photo.put("cover_thumb_b64", "ABCDE123456789");
        photo.put("vision_b64", "abcdefghijklmnopqrstuvwxyz");
        photos.add(photo);
        result.set("photos", photos);
        root.set("result", result);

        ObjectNode sanitized = (ObjectNode) mapper.readTree(sanitizer.sanitizeMetadata(root));
        ObjectNode sanitizedPhoto = (ObjectNode) sanitized.path("result").path("photos").path(0);

        assertEquals("123456789ABCDE", sanitizedPhoto.path("thumb_b64").asText());
        assertEquals("ABCDE123456789", sanitizedPhoto.path("cover_thumb_b64").asText());
        assertTrue(sanitizedPhoto.path("vision_b64").asText().startsWith("<redacted base64 "));
    }
}
