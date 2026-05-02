package com.agentplatform.hub.ws;

import com.agentplatform.api.hub.InternalCallRequest;
import com.agentplatform.hub.registry.DeviceRegistry;
import com.agentplatform.protocol.JsonRpcCodec;
import com.agentplatform.protocol.JsonRpcMessage;
import com.agentplatform.protocol.JsonRpcMethods;
import com.agentplatform.protocol.JsonRpcNotification;
import com.agentplatform.protocol.JsonRpcRequest;
import com.agentplatform.protocol.JsonRpcResponse;
import com.agentplatform.protocol.ToolManifest;
import com.agentplatform.protocol.ToolResult;
import com.agentplatform.protocol.ToolSpec;
import com.agentplatform.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end test: a fake "device" connects via WebSocket, reports its
 * manifest, then the agent-service path (POST /internal/tools/call) routes a
 * tool call through the WS roundtrip back to the device, and the response
 * unblocks the DeferredResult.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "agent-platform.hub.mock-mode=false",   // require real WS connections
        "spring.cloud.nacos.discovery.register-enabled=false"
})
class DeviceWebSocketIntegrationTest {

    @LocalServerPort int port;

    @Autowired JwtUtil jwt;
    @Autowired DeviceRegistry registry;
    @Autowired ObjectMapper mapper;
    @Autowired JsonRpcCodec codec;
    @Autowired TestRestTemplate rest;

    private WebSocketHttpHeaders bearerHeader(String token) {
        WebSocketHttpHeaders h = new WebSocketHttpHeaders();
        h.add("Sec-WebSocket-Protocol", "bearer." + token);
        return h;
    }

    private URI wsEndpoint() {
        return URI.create("ws://localhost:" + port + "/ws/device");
    }

    @Test
    void invalid_token_rejects_handshake() {
        StandardWebSocketClient client = new StandardWebSocketClient();
        TestDeviceClient handler = new TestDeviceClient(codec);

        assertThatThrownBy(() -> client.execute(handler, bearerHeader("not.a.valid.jwt"), wsEndpoint())
                .get(3, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void no_bearer_subprotocol_rejects_handshake() {
        StandardWebSocketClient client = new StandardWebSocketClient();
        TestDeviceClient handler = new TestDeviceClient(codec);

        // No Sec-WebSocket-Protocol → subprotocol negotiation fails (TokenAwareHandshakeHandler returns null)
        assertThatThrownBy(() -> client.execute(handler, new WebSocketHttpHeaders(), wsEndpoint())
                .get(3, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void valid_token_registers_device_and_can_update_manifest() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        String token = jwt.issueDeviceToken(userId, deviceId, Duration.ofHours(1));

        StandardWebSocketClient client = new StandardWebSocketClient();
        TestDeviceClient handler = new TestDeviceClient(codec);

        WebSocketSession s = client.execute(handler, bearerHeader(token), wsEndpoint())
                .get(3, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(2))
                .until(() -> registry.getSession(deviceId).isPresent());

        // Push a manifest, expect it to land in the registry
        ToolManifest manifest = new ToolManifest(List.of(
                new ToolSpec("photos.list_recent", "fake", mapper.createObjectNode(), false),
                new ToolSpec("clipboard.read", "fake", mapper.createObjectNode(), false)));
        handler.send(new JsonRpcNotification(JsonRpcMethods.TOOL_MANIFEST,
                mapper.valueToTree(manifest)), s);

        await().atMost(Duration.ofSeconds(2))
                .until(() -> registry.getSession(deviceId)
                        .map(d -> d.manifest().tools().size())
                        .orElse(0) == 2);

        s.close(CloseStatus.NORMAL);

        await().atMost(Duration.ofSeconds(2))
                .until(() -> registry.getSession(deviceId).isEmpty());
    }

    @Test
    void e2e_tool_call_round_trips_through_websocket() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        String token = jwt.issueDeviceToken(userId, deviceId, Duration.ofHours(1));

        StandardWebSocketClient client = new StandardWebSocketClient();
        TestDeviceClient handler = new TestDeviceClient(codec);

        WebSocketSession deviceSide = client.execute(handler, bearerHeader(token), wsEndpoint())
                .get(3, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(2))
                .until(() -> registry.getSession(deviceId).isPresent());

        // Internal tool call from agent-service's perspective
        InternalCallRequest req = new InternalCallRequest(
                deviceId, userId, "photos.list_recent",
                mapper.createObjectNode().put("limit", 3), null);

        CompletableFuture<ResponseEntity<ToolResult>> futureResp = CompletableFuture.supplyAsync(() ->
                rest.postForEntity(
                        "http://localhost:" + port + "/internal/tools/call",
                        req, ToolResult.class));

        // The device should receive a tool.call request — wait for it
        JsonRpcMessage received = handler.poll(Duration.ofSeconds(3));
        assertThat(received).isInstanceOf(JsonRpcRequest.class);
        JsonRpcRequest toolCall = (JsonRpcRequest) received;
        assertThat(toolCall.method()).isEqualTo(JsonRpcMethods.TOOL_CALL);

        // Device responds with a fake result
        ObjectNode value = mapper.createObjectNode();
        value.put("photos", "fake-1,fake-2,fake-3");
        handler.send(JsonRpcResponse.success(toolCall.id(), value), deviceSide);

        // The original POST should now resolve with that result
        ResponseEntity<ToolResult> resp = futureResp.get(5, TimeUnit.SECONDS);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        ToolResult body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.hasError()).isFalse();
        assertThat(body.value().get("photos").asText()).isEqualTo("fake-1,fake-2,fake-3");

        deviceSide.close(CloseStatus.NORMAL);
    }

    /** Minimal "device" — captures inbound JSON-RPC messages, lets the test send replies. */
    static class TestDeviceClient implements WebSocketHandler {
        private final JsonRpcCodec codec;
        private final LinkedBlockingQueue<JsonRpcMessage> inbox = new LinkedBlockingQueue<>();

        TestDeviceClient(JsonRpcCodec codec) { this.codec = codec; }

        @Override public void afterConnectionEstablished(WebSocketSession session) {}
        @Override public void handleTransportError(WebSocketSession session, Throwable exception) {}
        @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {}
        @Override public boolean supportsPartialMessages() { return false; }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
            if (message instanceof TextMessage tm) {
                inbox.add(codec.decode(tm.getPayload()));
            }
        }

        void send(JsonRpcMessage msg, WebSocketSession session) throws Exception {
            session.sendMessage(new TextMessage(codec.encode(msg)));
        }

        JsonRpcMessage poll(Duration timeout) throws InterruptedException {
            return inbox.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
