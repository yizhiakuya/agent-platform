package com.agentplatform.hub.ws;

import com.agentplatform.hub.call.PendingCallRegistry;
import com.agentplatform.hub.registry.DeviceRegistry;
import com.agentplatform.hub.registry.DeviceSession;
import com.agentplatform.protocol.JsonRpcCodec;
import com.agentplatform.protocol.JsonRpcMessage;
import com.agentplatform.protocol.JsonRpcMethods;
import com.agentplatform.protocol.JsonRpcNotification;
import com.agentplatform.protocol.JsonRpcRequest;
import com.agentplatform.protocol.JsonRpcResponse;
import com.agentplatform.protocol.ToolManifest;
import com.agentplatform.protocol.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.UUID;

/**
 * Routes incoming JSON-RPC frames from a connected device:
 *
 * <ul>
 *   <li>{@code hello}            (request)        — reply with sessionId/protocolVersion</li>
 *   <li>{@code tool.manifest}    (notification)   — store on the session</li>
 *   <li>{@code tool.confirm.ack} (notification)   — TODO PR 13 (sensitive-tool approval)</li>
 *   <li>{@code heartbeat}        (notification)   — bookkeeping only</li>
 *   <li>{@code $/progress}       (notification)   — forward to agent-service later (PR 7)</li>
 *   <li>JSON-RPC response (id present, no method) — resolve {@link PendingCallRegistry}</li>
 * </ul>
 *
 * <p>The session is wrapped in {@link ConcurrentWebSocketSessionDecorator} so
 * writes from many threads (the controller path, the heartbeat task, $/cancel
 * timer) are serialised on a 512KB / 10s back-pressure budget.
 */
@Component
public class DeviceWsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceWsHandler.class);

    private static final int SEND_BUFFER_BYTES = 512 * 1024;
    private static final int SEND_TIMEOUT_MS = 10_000;

    private final DeviceRegistry registry;
    private final PendingCallRegistry pendingCalls;
    private final JsonRpcCodec codec;
    private final ObjectMapper mapper;

    public DeviceWsHandler(DeviceRegistry registry,
                           PendingCallRegistry pendingCalls,
                           JsonRpcCodec codec,
                           ObjectMapper mapper) {
        this.registry = registry;
        this.pendingCalls = pendingCalls;
        this.codec = codec;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) {
        UUID deviceId = (UUID) rawSession.getAttributes().get(DeviceHandshakeInterceptor.ATTR_DEVICE_ID);
        UUID userId = (UUID) rawSession.getAttributes().get(DeviceHandshakeInterceptor.ATTR_USER_ID);
        if (deviceId == null || userId == null) {
            // should never happen — interceptor guarantees both attrs
            try { rawSession.close(CloseStatus.SERVER_ERROR); } catch (Exception ignored) {}
            return;
        }
        WebSocketSession decorated =
                new ConcurrentWebSocketSessionDecorator(rawSession, SEND_TIMEOUT_MS, SEND_BUFFER_BYTES);
        WsDeviceSession deviceSession = new WsDeviceSession(deviceId, userId, decorated, codec);
        registry.online(deviceSession);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        UUID deviceId = (UUID) session.getAttributes().get(DeviceHandshakeInterceptor.ATTR_DEVICE_ID);
        try {
            JsonRpcMessage msg = codec.decode(message.getPayload());
            if (msg instanceof JsonRpcRequest req) {
                handleRequest(deviceId, req);
            } else if (msg instanceof JsonRpcNotification note) {
                handleNotification(deviceId, note);
            } else if (msg instanceof JsonRpcResponse resp) {
                handleResponse(deviceId, resp);
            }
        } catch (Exception e) {
            log.warn("Failed to handle WS message from {}: {}", deviceId, e.getMessage());
        }
    }

    private void handleRequest(UUID deviceId, JsonRpcRequest req) {
        if (JsonRpcMethods.HELLO.equals(req.method())) {
            DeviceSession s = registry.getSession(deviceId).orElse(null);
            if (s == null) return;
            ObjectNode result = mapper.createObjectNode();
            result.put("protocolVersion", "1");
            result.put("sessionId", UUID.randomUUID().toString());
            s.send(JsonRpcResponse.success(req.id(), result));
            return;
        }
        log.debug("Ignored request method '{}' from {}", req.method(), deviceId);
    }

    private void handleNotification(UUID deviceId, JsonRpcNotification note) {
        switch (note.method()) {
            case JsonRpcMethods.TOOL_MANIFEST -> handleToolManifest(deviceId, note);
            case JsonRpcMethods.HEARTBEAT, JsonRpcMethods.PROGRESS, JsonRpcMethods.CONFIRM_ACK -> {
                // bookkeeping / fan-out to agent-service comes in later PRs
                log.debug("Notification {} from {}", note.method(), deviceId);
            }
            default -> log.debug("Ignored notification method '{}' from {}", note.method(), deviceId);
        }
    }

    private void handleToolManifest(UUID deviceId, JsonRpcNotification note) {
        try {
            ToolManifest manifest = mapper.treeToValue(note.params(), ToolManifest.class);
            registry.getSession(deviceId).ifPresent(s -> s.updateManifest(manifest));
            log.info("Device {} updated manifest with {} tools",
                    deviceId, manifest.tools() == null ? 0 : manifest.tools().size());
        } catch (Exception e) {
            log.warn("Bad tool.manifest payload from {}: {}", deviceId, e.getMessage());
        }
    }

    /** Device's response to a server-initiated tool.call request. */
    private void handleResponse(UUID deviceId, JsonRpcResponse resp) {
        UUID callId;
        try {
            callId = UUID.fromString(resp.id());
        } catch (IllegalArgumentException e) {
            log.warn("Non-UUID response id '{}' from {}", resp.id(), deviceId);
            return;
        }
        ToolResult result = resp.hasError()
                ? ToolResult.err(resp.error())
                : ToolResult.ok(resp.result());
        boolean delivered = pendingCalls.complete(callId, result);
        if (!delivered) {
            log.debug("Late response for callId {} (already timed out / unknown)", callId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID deviceId = (UUID) session.getAttributes().get(DeviceHandshakeInterceptor.ATTR_DEVICE_ID);
        log.info("WS closed device={} code={} reason='{}'",
                deviceId, status.getCode(), status.getReason());
        if (deviceId != null) {
            registry.offline(deviceId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        UUID deviceId = (UUID) session.getAttributes().get(DeviceHandshakeInterceptor.ATTR_DEVICE_ID);
        log.warn("WS transport error device={} type={} msg={}",
                deviceId, exception.getClass().getSimpleName(), exception.getMessage());
    }
}
