package com.agentplatform.auth.service;

import com.agentplatform.auth.dto.DeviceDto;
import com.agentplatform.auth.entity.Device;
import com.agentplatform.auth.repository.DeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private DeviceDto toDto(Device d) {
        return new DeviceDto(d.getId(), d.getName(), d.getModel(), d.getOsVersion(),
                d.getLastSeenAt(), d.getCreatedAt());
    }
}
