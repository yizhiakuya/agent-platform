package com.agentplatform.hub.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory directory of online devices. Single instance per hub process — when
 * we add multi-instance hub (PR 13/14 hardening) this needs to become a
 * Redis-backed pub/sub directory plus per-node {@code DeviceSession} ownership.
 */
@Component
public class DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistry.class);

    private final Map<UUID, DeviceSession> byId = new ConcurrentHashMap<>();

    public Optional<DeviceSession> getSession(UUID deviceId) {
        return Optional.ofNullable(byId.get(deviceId));
    }

    public void online(DeviceSession session) {
        DeviceSession previous = byId.put(session.deviceId(), session);
        if (previous != null) {
            previous.close("replaced");
        }
        log.info("Device online: {} (user {})", session.deviceId(), session.userId());
    }

    public void offline(UUID deviceId) {
        DeviceSession s = byId.remove(deviceId);
        if (s != null) {
            s.close("offline");
            log.info("Device offline: {}", deviceId);
        }
    }

    public List<DeviceSession> listOnlineByUser(UUID userId) {
        return byId.values().stream()
                .filter(s -> userId.equals(s.userId()))
                .toList();
    }

    public List<DeviceSession> listAll() {
        return List.copyOf(byId.values());
    }

    public int size() {
        return byId.size();
    }
}
