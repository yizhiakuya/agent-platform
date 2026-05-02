package com.agentplatform.hub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent-platform.jwt")
public record JwtProperties(String secret, String issuer) {}
