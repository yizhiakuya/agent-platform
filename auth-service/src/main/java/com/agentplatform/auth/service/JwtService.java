package com.agentplatform.auth.service;

import com.agentplatform.auth.config.PlatformProperties;
import com.agentplatform.auth.repository.DeviceRepository;
import com.agentplatform.auth.repository.RevokedJtiRepository;
import com.agentplatform.security.JwtUtil;
import com.agentplatform.security.Principal;
import io.jsonwebtoken.JwtException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Service-level wrapper around {@link JwtUtil} that adds revocation lookups.
 * <ul>
 *   <li>Issuing — delegate straight to {@link JwtUtil}.</li>
 *   <li>Verifying — verify signature/issuer/expiration first, then check the
 *       jti against {@link RevokedJtiRepository}. Token rejected if revoked.</li>
 * </ul>
 */
@Service
public class JwtService {

    private final JwtUtil jwt;
    private final RevokedJtiRepository revoked;
    private final DeviceRepository devices;
    private final Duration userTtl;
    private final Duration deviceTtl;

    public JwtService(JwtUtil jwt, PlatformProperties props, RevokedJtiRepository revoked, DeviceRepository devices) {
        this.jwt = jwt;
        this.revoked = revoked;
        this.devices = devices;
        this.userTtl = Duration.ofHours(props.jwt().userTokenTtlHours());
        this.deviceTtl = Duration.ofDays(props.jwt().deviceTokenTtlDays());
    }

    public String issueUserToken(UUID userId) {
        return jwt.issueUserToken(userId, userTtl);
    }

    public String issueDeviceToken(UUID userId, UUID deviceId) {
        return jwt.issueDeviceToken(userId, deviceId, deviceTtl);
    }

    public Principal verifyAndCheckRevocation(String token) {
        Principal p = jwt.verify(token);
        if (p.jti() != null && revoked.existsById(p.jti())) {
            throw new JwtException("Token has been revoked");
        }
        if (p.isDevice() && !devices.existsByIdAndUserIdAndRevokedAtIsNull(p.subjectAsUuid(), p.userIdAsUuid())) {
            throw new JwtException("Device has been revoked");
        }
        return p;
    }
}
