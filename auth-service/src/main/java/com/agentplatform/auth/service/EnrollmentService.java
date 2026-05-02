package com.agentplatform.auth.service;

import com.agentplatform.auth.config.PlatformProperties;
import com.agentplatform.auth.dto.EnrollmentResponse;
import com.agentplatform.auth.dto.RedeemRequest;
import com.agentplatform.auth.dto.RedeemResponse;
import com.agentplatform.auth.entity.Device;
import com.agentplatform.auth.entity.Enrollment;
import com.agentplatform.auth.repository.DeviceRepository;
import com.agentplatform.auth.repository.EnrollmentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * One-time enrollment token issuance and redemption.
 *
 * <p>Tokens are 32 bytes from {@link SecureRandom}, base64-url-encoded for the
 * QR payload. The DB only stores the SHA-256 hex of the token (so a leaked DB
 * dump cannot redeem any of the outstanding tokens). On redeem, we hash the
 * presented token and look it up by primary key.
 */
@Service
public class EnrollmentService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final EnrollmentRepository enrollments;
    private final DeviceRepository devices;
    private final JwtService jwtService;
    private final PlatformProperties props;

    public EnrollmentService(EnrollmentRepository enrollments,
                             DeviceRepository devices,
                             JwtService jwtService,
                             PlatformProperties props) {
        this.enrollments = enrollments;
        this.devices = devices;
        this.jwtService = jwtService;
        this.props = props;
    }

    @Transactional
    public EnrollmentResponse create(UUID userId) {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        RNG.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        Enrollment e = new Enrollment();
        e.setTokenHash(sha256Hex(token));
        e.setUserId(userId);
        e.setExpiresAt(OffsetDateTime.now().plusMinutes(props.enrollment().ttlMinutes()));
        enrollments.save(e);

        String qrPayload = String.format("agent-platform://enroll?server=%s&token=%s",
                URLEncoder.encode(props.publicUrl(), StandardCharsets.UTF_8),
                URLEncoder.encode(token, StandardCharsets.UTF_8));

        return new EnrollmentResponse(token, qrPayload, e.getExpiresAt());
    }

    @Transactional
    public RedeemResponse redeem(String token, RedeemRequest req) {
        Enrollment e = enrollments.findById(sha256Hex(token))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found"));
        if (e.getUsedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Token already used");
        }
        if (e.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Token expired");
        }

        Device d = new Device();
        d.setUserId(e.getUserId());
        d.setName(req.name());
        d.setModel(req.model());
        d.setOsVersion(req.osVersion());
        devices.save(d);

        e.setUsedAt(OffsetDateTime.now());
        enrollments.save(e);

        String deviceJwt = jwtService.issueDeviceToken(e.getUserId(), d.getId());
        return new RedeemResponse(d.getId(), deviceJwt);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
