package com.agentplatform.agent.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Async audit listener for {@link ToolPostEvent}. Writes one structured log
 * line per tool call to the {@code ToolAudit} logger so audit output can be
 * routed independently from regular application logs.
 */
@Component
public class ToolAuditListener {
    private static final Logger log = LoggerFactory.getLogger("ToolAudit");

    @EventListener
    @Async
    public void onToolCall(ToolPostEvent ev) {
        if (ev.errorMessage() != null) {
            log.info("user={} device={} tool={} args={} dur={}ms ERROR={}",
                    ev.userId(), ev.deviceId(), ev.toolName(), ev.args(),
                    ev.durationMs(), ev.errorMessage());
        } else {
            log.info("user={} device={} tool={} args={} dur={}ms OK (result={}b)",
                    ev.userId(), ev.deviceId(), ev.toolName(), ev.args(),
                    ev.durationMs(), ev.result() == null ? 0 : ev.result().toString().length());
        }
    }
}
