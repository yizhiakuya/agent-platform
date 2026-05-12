package com.agentplatform.hub.registry;

import java.util.UUID;

/**
 * Strategy for materialising a {@link DeviceSession} on demand when the
 * controller receives a tool call for a deviceId we don't currently have an
 * online session for. Dev mock mode can bind {@link MockDeviceProvisioner};
 * production binds {@link #noop} so unknown devices surface as 503 and must
 * connect over real WebSocket first.
 */
@FunctionalInterface
public interface DeviceProvisioner {

    /**
     * @return a fresh session, or {@code null} if this provisioner declines to create one.
     */
    DeviceSession provision(UUID deviceId, UUID userId);

    /** Always returns null; used when real WebSocket sessions are required. */
    static DeviceProvisioner noop() {
        return (d, u) -> null;
    }
}
