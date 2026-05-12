package com.agentplatform.hub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "agent-platform.hub")
public record HubProperties(
        long toolCallTimeoutMs,
        boolean mockMode,
        long mockFakeLatencyMs,
        /* WebSocket-specific knobs */
        List<String> wsAllowedOrigins,
        long wsHeartbeatIntervalMs
) {
    /** Origins always-allowed when nothing was configured (mainly for tests). */
    public List<String> wsAllowedOriginsOrDefault() {
        return (wsAllowedOrigins == null || wsAllowedOrigins.isEmpty())
                ? List.of("*")
                : wsAllowedOrigins;
    }
}
