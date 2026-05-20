package com.agentplatform.auth.service;

import com.agentplatform.auth.dto.DeviceDto;
import com.agentplatform.auth.entity.Device;
import com.agentplatform.auth.repository.DeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class DeviceService {

    private final DeviceRepository devices;

    public DeviceService(DeviceRepository devices) {
        this.devices = devices;
    }

    @Transactional(readOnly = true)
    public List<DeviceDto> list(UUID userId) {
        return devices.findByUserIdAndRevokedAtIsNull(userId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public void revoke(UUID userId, UUID deviceId) {
        Device device = devices.findById(deviceId)
                .filter(d -> d.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
        if (device.getRevokedAt() == null) {
            device.setRevokedAt(OffsetDateTime.now());
            devices.save(device);
        }
    }

    @Transactional
    public boolean markSeen(UUID userId, UUID deviceId, OffsetDateTime seenAt) {
        if (userId == null || deviceId == null) {
            return false;
        }
        OffsetDateTime effectiveSeenAt = seenAt == null ? OffsetDateTime.now() : seenAt;
        return devices.markSeen(deviceId, userId, effectiveSeenAt) > 0;
    }

    private DeviceDto toDto(Device d) {
        return new DeviceDto(d.getId(), d.getName(), d.getModel(), d.getOsVersion(),
                d.getLastSeenAt(), d.getCreatedAt());
    }
}
