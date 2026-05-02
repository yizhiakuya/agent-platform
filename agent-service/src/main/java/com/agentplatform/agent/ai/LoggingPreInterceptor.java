package com.agentplatform.agent.ai;

import com.agentplatform.protocol.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Demonstration {@link ToolPreInterceptor} — debug-logs the tool call without
 * rewriting args. Real interceptors (PII redaction, sensitive-tool gating,
 * args validation) follow the same pattern; ordering is via {@link Order}.
 */
@Component
@Order(0)
public class LoggingPreInterceptor implements ToolPreInterceptor {
    private static final Logger log = LoggerFactory.getLogger("ToolPre");

    @Override
    public JsonNode before(UUID userId, UUID deviceId, ToolSpec spec, JsonNode args, ToolContext ctx) {
        log.debug("user={} device={} tool={} args={}", userId, deviceId, spec.name(), args);
        return args;
    }
}
