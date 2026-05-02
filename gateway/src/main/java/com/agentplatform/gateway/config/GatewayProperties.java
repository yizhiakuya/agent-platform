package com.agentplatform.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "agent-platform")
public record GatewayProperties(
        Jwt jwt,
        List<String> protectedPaths
) {
    public record Jwt(String secret, String issuer) {}
}
