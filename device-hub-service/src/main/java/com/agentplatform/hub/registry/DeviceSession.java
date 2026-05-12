package com.agentplatform.hub.registry;

import com.agentplatform.protocol.JsonRpcMessage;
import com.agentplatform.protocol.ToolManifest;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Abstract device-side connection. Production sessions are backed by Spring
 * WebSocket; tests and explicit dev mock mode can use {@link MockDeviceSession}.
 * Both speak JSON-RPC 2.0 (see {@link JsonRpcMessage}).
 */
public interface DeviceSession {

    UUID deviceId();

    UUID userId();

    OffsetDateTime connectedAt();

    boolean isOpen();

    ToolManifest manifest();

    void updateManifest(ToolManifest manifest);

    /**
     * Send a JSON-RPC message towards the device. May be invoked from many
     * threads. Implementations must be thread-safe.
     */
    void send(JsonRpcMessage msg);

    void close(String reason);
}
