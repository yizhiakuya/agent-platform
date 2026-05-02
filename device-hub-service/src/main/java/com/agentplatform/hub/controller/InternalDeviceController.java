package com.agentplatform.hub.controller;

import com.agentplatform.api.hub.OnlineDeviceDto;
import com.agentplatform.hub.registry.DeviceRegistry;
import com.agentplatform.hub.registry.DeviceSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Read-only listing of currently-online devices. Used by agent-service to
 * decide which tools to inject into Spring AI's function-calling list.
 */
@RestController
@RequestMapping("/internal/devices")
public class InternalDeviceController {

    private final DeviceRegistry registry;

    public InternalDeviceController(DeviceRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param userId optional filter by owner. When omitted, returns every online device on this hub.
     */
    @GetMapping("/online")
    public List<OnlineDeviceDto> online(@RequestParam(required = false) UUID userId) {
        Stream<DeviceSession> stream = userId == null
                ? registry.listAll().stream()
                : registry.listOnlineByUser(userId).stream();
        return stream
                .filter(DeviceSession::isOpen)
                .map(s -> new OnlineDeviceDto(s.deviceId(), s.userId(), s.manifest(), s.connectedAt()))
                .toList();
    }
}
