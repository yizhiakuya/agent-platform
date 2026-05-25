package com.agentplatform.hub.registry;

import com.agentplatform.protocol.ToolManifest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public abstract class AbstractDeviceSession implements DeviceSession {

    private final UUID deviceId;
    private final UUID userId;
    private final OffsetDateTime connectedAt = OffsetDateTime.now();
    private final AtomicReference<ToolManifest> manifestRef;

    protected AbstractDeviceSession(UUID deviceId, UUID userId, ToolManifest initialManifest) {
        this.deviceId = deviceId;
        this.userId = userId;
        this.manifestRef = new AtomicReference<>(emptyIfNull(initialManifest));
    }

    @Override public UUID deviceId() { return deviceId; }
    @Override public UUID userId() { return userId; }
    @Override public OffsetDateTime connectedAt() { return connectedAt; }
    @Override public ToolManifest manifest() { return manifestRef.get(); }
    @Override public void updateManifest(ToolManifest manifest) { manifestRef.set(emptyIfNull(manifest)); }

    private static ToolManifest emptyIfNull(ToolManifest manifest) {
        return manifest == null ? new ToolManifest(List.of()) : manifest;
    }
}
