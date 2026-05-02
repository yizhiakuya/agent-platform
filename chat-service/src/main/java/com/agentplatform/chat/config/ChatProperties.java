package com.agentplatform.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent-platform")
public record ChatProperties(
        Jwt jwt,
        Chat chat
) {
    public record Jwt(String secret, String issuer) {}

    public record Chat(int maxContentBytes, int redactBase64OverBytes) {}
}
