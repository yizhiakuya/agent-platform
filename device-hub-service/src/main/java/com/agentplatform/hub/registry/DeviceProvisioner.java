package com.agentplatform.hub.registry;

import java.util.UUID;

/**
 * Strategy for materialising a {@link DeviceSession} on demand when the
 * controller receives a tool call for a deviceId we don't currently have an
 * online session for. PR 5 uses {@link MockDeviceProvisioner} (auto-creates
 * fake sessions). PR 6's real-WS deployment binds a {@link #noop} provisioner
 * so unknown devices surface as 503 (forcing real WS connection first).
 */
@FunctionalInterface
public interface DeviceProvisioner {

    /**
     * @return a fresh session, or {@code null} if this provisioner declines to create one.
     */
    DeviceSession provision(UUID deviceId, UUID userId);

    /** Always returns null — used in real-WS mode. */
    static DeviceProvisioner noop() {
        return (d, u) -> null;
    }
}
