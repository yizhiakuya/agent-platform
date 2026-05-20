package com.agentplatform.hub.controller;

import com.agentplatform.api.auth.DeviceSeenRequest;
import com.agentplatform.api.hub.DeviceOnlineStatusDto;
import com.agentplatform.hub.client.AuthInternalClient;
import com.agentplatform.hub.registry.DeviceRegistry;
import com.agentplatform.hub.registry.DeviceSession;
import com.agentplatform.security.PrincipalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/devices")
public class UserDeviceStatusController {

    private static final Logger log = LoggerFactory.getLogger(UserDeviceStatusController.class);

    private final DeviceRegistry registry;
    private final AuthInternalClient authClient;

    public UserDeviceStatusController(DeviceRegistry registry, AuthInternalClient authClient) {
        this.registry = registry;
        this.authClient = authClient;
    }

    @GetMapping("/online-status")
    public List<DeviceOnlineStatusDto> onlineStatus() {
        UUID userId = PrincipalContext.requireUserId();
        return registry.listOnlineByUser(userId).stream()
                .filter(DeviceSession::isOpen)
                .filter(this::isStillActive)
                .map(s -> new DeviceOnlineStatusDto(
                        s.deviceId(),
                        true,
                        s.connectedAt(),
                        s.manifest().tools() == null ? 0 : s.manifest().tools().size()))
                .toList();
    }

    private boolean isStillActive(DeviceSession session) {
        try {
            authClient.markDeviceSeen(new DeviceSeenRequest(
                    session.deviceId(), session.userId(), OffsetDateTime.now()));
            return true;
        } catch (Exception e) {
            log.warn("Device {} failed active check while listing online status: {}",
                    session.deviceId(), e.toString());
            registry.offline(session.deviceId());
            return false;
        }
    }
}
