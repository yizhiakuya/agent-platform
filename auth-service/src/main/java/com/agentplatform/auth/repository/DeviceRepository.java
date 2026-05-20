package com.agentplatform.auth.repository;

import com.agentplatform.auth.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    List<Device> findByUserIdAndRevokedAtIsNull(UUID userId);

    boolean existsByIdAndUserIdAndRevokedAtIsNull(UUID id, UUID userId);

    @Modifying
    @Query("""
            update Device d
               set d.lastSeenAt = :seenAt
             where d.id = :deviceId
               and d.userId = :userId
               and d.revokedAt is null
            """)
    int markSeen(@Param("deviceId") UUID deviceId,
                 @Param("userId") UUID userId,
                 @Param("seenAt") OffsetDateTime seenAt);
}
