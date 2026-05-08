package com.agentplatform.auth.controller;

import com.agentplatform.auth.dto.DeviceDto;
import com.agentplatform.auth.dto.EnrollmentResponse;
import com.agentplatform.auth.service.DeviceService;
import com.agentplatform.auth.service.EnrollmentService;
import com.agentplatform.security.PrincipalContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * User-scoped device management. Mounted at {@code /api/me/devices/**} which is
 * inside {@code PathBasedJwtFilter}'s protected prefix list — a valid user JWT
 * is required to reach any handler here.
 */
@RestController
@RequestMapping("/api/me/devices")
public class MeDeviceController {

    private final EnrollmentService enrollmentService;
    private final DeviceService deviceService;

    public MeDeviceController(EnrollmentService enrollmentService, DeviceService deviceService) {
        this.enrollmentService = enrollmentService;
        this.deviceService = deviceService;
    }

    /** Create a one-time enrollment token + QR payload that an Android app can redeem. */
    @PostMapping("/enrollments")
    public EnrollmentResponse createEnrollment() {
        UUID userId = PrincipalContext.requireUserId();
        return enrollmentService.create(userId);
    }

    /** List the current user's active (non-revoked) devices. */
    @GetMapping
    public List<DeviceDto> list() {
        UUID userId = PrincipalContext.requireUserId();
        return deviceService.list(userId);
    }

    /** Revoke a bound device so its long-lived JWT can no longer reconnect. */
    @DeleteMapping("/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID deviceId) {
        deviceService.revoke(PrincipalContext.requireUserId(), deviceId);
    }
}
