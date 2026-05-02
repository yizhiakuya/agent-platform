package com.agentplatform.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    /** 64 zero bytes, base64-encoded — only used for tests, never in real configs. */
    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[64]);

    private final JwtUtil jwt = new JwtUtil(SECRET, "agent-platform");

    @Test
    void user_token_roundtrip() {
        UUID userId = UUID.randomUUID();
        String token = jwt.issueUserToken(userId, Duration.ofMinutes(10));
        Principal p = jwt.verify(token);

        assertThat(p.isUser()).isTrue();
        assertThat(p.subjectAsUuid()).isEqualTo(userId);
        assertThat(p.userIdAsUuid()).isEqualTo(userId);
        assertThat(p.jti()).isNotBlank();
    }

    @Test
    void device_token_roundtrip() {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        String token = jwt.issueDeviceToken(userId, deviceId, Duration.ofDays(30));
        Principal p = jwt.verify(token);

        assertThat(p.isDevice()).isTrue();
        assertThat(p.subjectAsUuid()).isEqualTo(deviceId);
        assertThat(p.userIdAsUuid()).isEqualTo(userId);
    }

    @Test
    void expired_token_rejected() throws InterruptedException {
        String token = jwt.issueUserToken(UUID.randomUUID(), Duration.ofMillis(1));
        Thread.sleep(20);
        assertThatThrownBy(() -> jwt.verify(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void wrong_secret_rejected() {
        String token = jwt.issueUserToken(UUID.randomUUID(), Duration.ofMinutes(10));

        // Different secret, same length
        byte[] otherBytes = new byte[64];
        otherBytes[0] = 1;  // ensure non-equal
        String otherSecret = Base64.getEncoder().encodeToString(otherBytes);
        JwtUtil otherJwt = new JwtUtil(otherSecret, "agent-platform");

        assertThatThrownBy(() -> otherJwt.verify(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void wrong_issuer_rejected() {
        String token = jwt.issueUserToken(UUID.randomUUID(), Duration.ofMinutes(10));
        JwtUtil otherIssuer = new JwtUtil(SECRET, "another-platform");

        assertThatThrownBy(() -> otherIssuer.verify(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void short_secret_rejected_at_construction() {
        // 16 bytes is below the 32-byte HS256 minimum
        String shortSecret = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new JwtUtil(shortSecret, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
