package com.agentplatform.hub.call;

import com.agentplatform.protocol.JsonRpcError;
import com.agentplatform.protocol.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

/**
 * Unit tests for {@link PendingCallRegistry}. Covers the three resolution
 * paths required by the PR 5 verification rubric: dispatch (complete) /
 * timeout / cancel.
 */
class PendingCallRegistryTest {

    private ScheduledExecutorService scheduler;
    private PendingCallRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(2);
        registry = new PendingCallRegistry(scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void complete_resolves_deferredResult_and_clears_map() {
        UUID callId = UUID.randomUUID();
        DeferredResult<ToolResult> dr = registry.register(callId, 5000, null);

        assertThat(registry.pendingCount()).isEqualTo(1);

        ToolResult expected = ToolResult.ok(mapper.createObjectNode().put("ok", true));
        boolean completed = registry.complete(callId, expected);

        assertThat(completed).isTrue();
        assertThat(dr.getResult()).isEqualTo(expected);
        assertThat(registry.pendingCount()).isZero();
    }

    @Test
    void timeout_invokes_cancelHook_and_resolves_with_TOOL_TIMEOUT() {
        UUID callId = UUID.randomUUID();
        AtomicBoolean cancelInvoked = new AtomicBoolean();
        DeferredResult<ToolResult> dr = registry.register(callId, 100, () -> cancelInvoked.set(true));

        await().atMost(Duration.ofSeconds(2))
                .until(dr::hasResult);

        ToolResult result = (ToolResult) dr.getResult();
        assertThat(result.hasError()).isTrue();
        assertThat(result.error().code()).isEqualTo(JsonRpcError.TOOL_TIMEOUT);
        assertThat(cancelInvoked).isTrue();
        assertThat(registry.pendingCount()).isZero();
    }

    @Test
    void cancel_resolves_with_TOOL_CANCELLED_and_clears_map() {
        UUID callId = UUID.randomUUID();
        DeferredResult<ToolResult> dr = registry.register(callId, 5000, null);

        boolean cancelled = registry.cancel(callId, "user requested");

        assertThat(cancelled).isTrue();
        ToolResult result = (ToolResult) dr.getResult();
        assertThat(result.hasError()).isTrue();
        assertThat(result.error().code()).isEqualTo(JsonRpcError.TOOL_CANCELLED);
        assertThat(result.error().message()).contains("user requested");
        assertThat(registry.pendingCount()).isZero();
    }

    @Test
    void complete_after_already_resolved_returns_false() {
        UUID callId = UUID.randomUUID();
        registry.register(callId, 5000, null);
        registry.complete(callId, ToolResult.ok(mapper.createObjectNode()));

        boolean second = registry.complete(callId, ToolResult.ok(mapper.createObjectNode().put("dup", true)));
        assertThat(second).isFalse();
    }

    @Test
    void complete_unknown_callId_returns_false() {
        boolean completed = registry.complete(UUID.randomUUID(), ToolResult.ok(mapper.createObjectNode()));
        assertThat(completed).isFalse();
    }

    @Test
    void cancel_unknown_callId_returns_false() {
        boolean cancelled = registry.cancel(UUID.randomUUID(), "n/a");
        assertThat(cancelled).isFalse();
    }

    @Test
    void cancel_after_complete_returns_false_no_double_resolution() {
        UUID callId = UUID.randomUUID();
        DeferredResult<ToolResult> dr = registry.register(callId, 5000, null);
        ToolResult ok = ToolResult.ok(mapper.createObjectNode().put("ok", true));
        registry.complete(callId, ok);

        boolean cancelled = registry.cancel(callId, "too late");
        assertThat(cancelled).isFalse();
        assertThat(dr.getResult()).isEqualTo(ok);
    }
}
