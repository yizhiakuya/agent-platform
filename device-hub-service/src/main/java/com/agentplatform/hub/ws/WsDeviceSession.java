package com.agentplatform.hub.ws;

import com.agentplatform.hub.registry.DeviceSession;
import com.agentplatform.protocol.JsonRpcCodec;
import com.agentplatform.protocol.JsonRpcMessage;
import com.agentplatform.protocol.ToolManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link DeviceSession} backed by a real Spring {@link WebSocketSession}.
 *
 * <p>WebSocketSession.sendMessage is NOT thread-safe. We rely on
 * {@code ConcurrentWebSocketSessionDecorator} (wired in {@link DeviceWsHandler})
 * to serialise writes; here we just call {@code sendMessage} directly.
 */
public class WsDeviceSession implements DeviceSession {

    private static final Logger log = LoggerFactory.getLogger(WsDeviceSession.class);

    private final UUID deviceId;
    private final UUID userId;
    private final WebSocketSession ws;
    private final JsonRpcCodec codec;
    private final OffsetDateTime connectedAt = OffsetDateTime.now();
    private final AtomicReference<ToolManifest> manifestRef =
            new AtomicReference<>(new ToolManifest(List.of()));

    public WsDeviceSession(UUID deviceId, UUID userId, WebSocketSession ws, JsonRpcCodec codec) {
        this.deviceId = deviceId;
        this.userId = userId;
        this.ws = ws;
        this.codec = codec;
    }

    @Override public UUID deviceId() { return deviceId; }
    @Override public UUID userId() { return userId; }
    @Override public OffsetDateTime connectedAt() { return connectedAt; }
    @Override public boolean isOpen() { return ws.isOpen(); }
    @Override public ToolManifest manifest() { return manifestRef.get(); }
    @Override public void updateManifest(ToolManifest manifest) {
        manifestRef.set(manifest == null ? new ToolManifest(List.of()) : manifest);
    }

    @Override
    public void send(JsonRpcMessage msg) {
        if (!ws.isOpen()) {
            log.warn("send() to closed WS session {}", deviceId);
            return;
        }
        String wire = codec.encode(msg);
        try {
            log.info("WS→{} sending {}b: {}", deviceId, wire.length(),
                    wire.length() > 200 ? wire.substring(0, 200) + "..." : wire);
            ws.sendMessage(new TextMessage(wire));
            log.info("WS→{} send OK", deviceId);
        } catch (IOException e) {
            log.warn("WS→{} send IO failed: {}", deviceId, e.getMessage(), e);
        } catch (Exception e) {
            log.warn("WS→{} send unexpected error", deviceId, e);
        }
    }

    @Override
    public void close(String reason) {
        try {
            if (ws.isOpen()) {
                ws.close(CloseStatus.NORMAL.withReason(reason == null ? "" : reason));
            }
        } catch (IOException ignored) {
        }
    }
}
