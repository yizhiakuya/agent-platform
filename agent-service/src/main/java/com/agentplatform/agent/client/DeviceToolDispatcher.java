package com.agentplatform.agent.client;

import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.hub.InternalCallRequest;
import com.agentplatform.protocol.JsonRpcError;
import com.agentplatform.protocol.ToolResult;
import com.agentplatform.security.Principal;
import com.agentplatform.security.TrustedHeaderAuthFilter;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Synchronous wrapper around {@code POST /internal/tools/call} on device-hub.
 * Called from {@link com.agentplatform.agent.ai.RemoteToolCallback#executeToolUse}
 * inside the SDK agentic loop.
 *
 * <p>Internally non-blocking (WebClient + reactor), but the loop owner needs
 * a synchronous result to feed back to the next LLM turn, so we
 * {@code .block(timeout)} here. The blocking call runs on the
 * {@code chatExecutor} pool from {@link com.agentplatform.agent.config.AgentBeans},
 * so it doesn't starve Tomcat request threads.
 */
@Component
public class DeviceToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DeviceToolDispatcher.class);

    private final WebClient webClient;
    private final String baseUri;
    private final Duration timeout;

    public DeviceToolDispatcher(WebClient hubWebClient, AgentProperties props) {
        this.webClient = hubWebClient;
        this.baseUri = props.agent().hubBaseUri();
        this.timeout = Duration.ofMillis(props.agent().toolCallTimeoutMs());
    }

    public ToolResult dispatch(UUID deviceId, UUID userId, String toolName, JsonNode args) {
        InternalCallRequest req = new InternalCallRequest(deviceId, userId, toolName, args, null);
        try {
            ToolResult result = webClient.post()
                    .uri(baseUri + "/internal/tools/call")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(ToolResult.class)
                    .block(timeout);
            return result != null ? result
                    : ToolResult.err(new JsonRpcError(JsonRpcError.INTERNAL_ERROR, "device-hub returned empty body"));
        } catch (Exception e) {
            log.warn("dispatch({}, {}) failed: {}", deviceId, toolName, e.getMessage());
            return ToolResult.err(new JsonRpcError(JsonRpcError.INTERNAL_ERROR,
                    "device-hub call failed: " + e.getMessage()));
        }
    }

    public Optional<FetchedAsset> fetchUploadAsset(UUID userId, String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith("/api/uploads/photos/")) {
            return Optional.empty();
        }
        try {
            ResponseEntity<byte[]> response = webClient.get()
                    .uri(baseUri + imageUrl)
                    .header(TrustedHeaderAuthFilter.H_TYPE, Principal.TYPE_USER)
                    .header(TrustedHeaderAuthFilter.H_USER, userId == null ? "" : userId.toString())
                    .header(TrustedHeaderAuthFilter.H_JTI, "agent-service")
                    .retrieve()
                    .toEntity(byte[].class)
                    .block(timeout);
            if (response == null || response.getBody() == null || response.getBody().length == 0) {
                return Optional.empty();
            }
            String contentType = response.getHeaders().getContentType() == null
                    ? "image/jpeg"
                    : response.getHeaders().getContentType().toString();
            return Optional.of(new FetchedAsset(contentType, response.getBody()));
        } catch (Exception e) {
            log.warn("fetchUploadAsset({}) failed: {}", imageUrl, e.getMessage());
            return Optional.empty();
        }
    }

    public record FetchedAsset(String contentType, byte[] bytes) {
        public FetchedAsset {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof FetchedAsset other
                    && Objects.equals(contentType, other.contentType)
                    && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(contentType);
            result = 31 * result + Arrays.hashCode(bytes);
            return result;
        }

        @Override
        public String toString() {
            return "FetchedAsset[contentType=" + contentType + ", bytes=" + Arrays.toString(bytes) + "]";
        }
    }
}
