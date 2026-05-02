package com.agentplatform.auth.repository;

import com.agentplatform.auth.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    List<Device> findByUserIdAndRevokedAtIsNull(UUID userId);
}
