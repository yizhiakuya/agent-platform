package com.agentplatform.hub.controller;

import com.agentplatform.hub.client.AuthInternalClient;
import com.agentplatform.security.InternalToken;
import com.agentplatform.security.Principal;
import com.agentplatform.security.TrustedHeaderAuthFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "agent-platform.hub.mock-mode=true",
        "agent-platform.hub.mock-fake-latency-ms=50",
        "agent-platform.uploads.photos.max-size=64B",
        "agent-platform.uploads.photos.cache-max-age=PT1H",
        "spring.cloud.nacos.discovery.register-enabled=false"
})
class PhotoUploadControllerTest {

    private static final String INTERNAL_TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==";
    private static final Path UPLOAD_DIR = Path.of(System.getProperty("java.io.tmpdir"),
            "agent-platform-photo-upload-test-" + UUID.randomUUID());

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;

    @MockitoBean private AuthInternalClient authClient;

    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        registry.add("agent-platform.uploads.photos.storage-dir", UPLOAD_DIR::toString);
    }

    @AfterAll
    static void cleanUp() throws IOException {
        if (!Files.exists(UPLOAD_DIR)) {
            return;
        }
        try (var paths = Files.walk(UPLOAD_DIR)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Best-effort cleanup for temporary test files.
                        }
                    });
        }
    }

    @Test
    void multipart_upload_returns_url_and_serves_asset_for_same_user() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00};
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpeg);

        MvcResult uploaded = mvc.perform(withAuth(multipart("/api/uploads/photos").file(file), userId, deviceId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assetId").isString())
                .andExpect(jsonPath("$.imageUrl").value(containsString("/api/uploads/photos/")))
                .andExpect(jsonPath("$.bytes").value(jpeg.length))
                .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                .andReturn();

        JsonNode body = mapper.readTree(uploaded.getResponse().getContentAsString());
        String assetId = body.path("assetId").asText();

        mvc.perform(withAuth(get("/api/uploads/photos/{assetId}", assetId), userId, deviceId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(jpeg));
    }

    @Test
    void raw_binary_upload_accepts_supported_image_bytes() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        byte[] png = new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };

        mvc.perform(withAuth(post("/api/uploads/photos")
                        .contentType("application/octet-stream")
                        .content(png), userId, deviceId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bytes").value(png.length))
                .andExpect(jsonPath("$.contentType").value("image/png"));
    }

    @Test
    void upload_rejects_unsupported_image_data() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", "nope".getBytes());

        mvc.perform(withAuth(multipart("/api/uploads/photos").file(file), userId, deviceId))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value("unsupported image format"));
    }

    @Test
    void upload_rejects_images_over_configured_limit() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        byte[] oversized = new byte[65];
        oversized[0] = (byte) 0xff;
        oversized[1] = (byte) 0xd8;
        oversized[2] = (byte) 0xff;
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", oversized);

        mvc.perform(withAuth(multipart("/api/uploads/photos").file(file), userId, deviceId))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("image exceeds max size"));
    }

    @Test
    void get_is_scoped_to_authenticated_user() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        byte[] webp = new byte[] {
                0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00,
                0x57, 0x45, 0x42, 0x50
        };
        MockMultipartFile file = new MockMultipartFile("image", "photo.webp", "image/webp", webp);

        MvcResult uploaded = mvc.perform(withAuth(multipart("/api/uploads/photos").file(file), ownerUserId, deviceId))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = mapper.readTree(uploaded.getResponse().getContentAsString());
        String assetId = body.path("assetId").asText();

        mvc.perform(withAuth(get("/api/uploads/photos/{assetId}", assetId), otherUserId, deviceId))
                .andExpect(status().isNotFound());
    }

    @Test
    void upload_requires_authentication() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg",
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});

        mvc.perform(multipart("/api/uploads/photos").file(file))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder,
                                                   UUID userId,
                                                   UUID deviceId) {
        return builder
                .header(InternalToken.HEADER, INTERNAL_TOKEN)
                .header(TrustedHeaderAuthFilter.H_TYPE, Principal.TYPE_DEVICE)
                .header(TrustedHeaderAuthFilter.H_USER, userId.toString())
                .header(TrustedHeaderAuthFilter.H_DEVICE, deviceId.toString())
                .header(TrustedHeaderAuthFilter.H_JTI, UUID.randomUUID().toString());
    }
}
