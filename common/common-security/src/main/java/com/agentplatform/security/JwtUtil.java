package com.agentplatform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Sign and verify JWTs used for both user and device authentication.
 *
 * <p>Algorithm: HS256 with a base64-encoded shared secret (>= 32 bytes raw,
 * required by HS256). Both {@code auth-service} (signing) and other services
 * (verifying for direct calls) share the same secret; gateway-internal traffic
 * uses trusted-header forwarding instead, so verification only happens once.
 *
 * <p>This class only signs/verifies — revocation is handled by {@code auth-service}'s
 * {@code revoked_jtis} table. Callers should look up {@link Principal#jti()} after
 * verifying to reject revoked tokens.
 */
public final class JwtUtil {

    private final SecretKey key;
    private final String issuer;

    public JwtUtil(String secretBase64, String issuer) {
        byte[] bytes = Decoders.BASE64.decode(secretBase64);
        if (bytes.length < 32) {
            throw new IllegalArgumentException(
                    "JWT secret must be at least 32 bytes (got " + bytes.length + ")");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.issuer = issuer;
    }

    public String issueUserToken(UUID userId, Duration ttl) {
        return baseBuilder(ttl)
                .subject(userId.toString())
                .claim("type", Principal.TYPE_USER)
                .compact();
    }

    public String issueDeviceToken(UUID userId, UUID deviceId, Duration ttl) {
        return baseBuilder(ttl)
                .subject(deviceId.toString())
                .claim("type", Principal.TYPE_DEVICE)
                .claim("uid", userId.toString())
                .compact();
    }

    public Principal verify(String token) {
        Claims c = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String type = c.get("type", String.class);
        String userId = Principal.TYPE_DEVICE.equals(type)
                ? c.get("uid", String.class)
                : c.getSubject();
        return new Principal(type, c.getSubject(), userId, c.getId());
    }

    private JwtBuilder baseBuilder(Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256);
    }
}
