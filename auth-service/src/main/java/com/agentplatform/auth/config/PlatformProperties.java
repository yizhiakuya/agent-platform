package com.agentplatform.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code agent-platform.*} keys in application.yaml. Spring Boot
 * 3.x supports nested record binding out of the box.
 */
@ConfigurationProperties(prefix = "agent-platform")
public record PlatformProperties(
        Jwt jwt,
        Enrollment enrollment,
        String publicUrl
) {
    public record Jwt(
            String secret,
            String issuer,
            int userTokenTtlHours,
            int deviceTokenTtlDays
    ) {}

    public record Enrollment(int ttlMinutes) {}
}
