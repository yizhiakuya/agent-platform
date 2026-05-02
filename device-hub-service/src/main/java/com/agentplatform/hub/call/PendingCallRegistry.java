package com.agentplatform.hub.call;

import com.agentplatform.protocol.JsonRpcError;
import com.agentplatform.protocol.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Maps in-flight tool {@code callId}s to their {@link DeferredResult}. The HTTP
 * controller registers a callId, sends the JSON-RPC request to the device, and
 * returns the DeferredResult — Tomcat's worker thread is freed immediately.
 * When the device responds (PR 6: real WS handler; PR 5: mock scheduled task),
 * {@link #complete} resolves the DeferredResult and Spring writes the response.
 *
 * <p>Timeouts are driven by an explicit {@link ScheduledExecutorService} (not by
 * {@code DeferredResult}'s built-in timeoutValue, which relies on servlet
 * container async timeout — that doesn't fire in MockMvc tests). When the timer
 * fires we set the fallback result and invoke the {@code onTimeoutCancel} hook
 * (PR 6: send {@code $/cancel} JSON-RPC notification to the device).
 */
@Component
public class PendingCallRegistry {

    private static final Logger log = LoggerFactory.getLogger(PendingCallRegistry.class);

    private final ScheduledExecutorService scheduler;
    private final Map<UUID, PendingCall> pending = new ConcurrentHashMap<>();

    public PendingCallRegistry(ScheduledExecutorService hubScheduler) {
        this.scheduler = hubScheduler;
    }

    private record PendingCall(DeferredResult<ToolResult> dr, ScheduledFuture<?> timer) {}

    /**
     * Reserve a slot for callId and return its DeferredResult. The timeout timer
     * starts immediately. If complete() or cancel() resolves the call earlier,
     * the timer is cancelled.
     *
     * @param onTimeoutCancel optional hook fired exactly once when the timer
     *                        expires before the call is resolved. PR 6 will use
     *                        this to send {@code $/cancel} to the device.
     */
    public DeferredResult<ToolResult> register(UUID callId, long timeoutMs, Runnable onTimeoutCancel) {
        DeferredResult<ToolResult> dr = new DeferredResult<>();

        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            PendingCall pc = pending.remove(callId);
            if (pc == null) {
                return; // already resolved
            }
            if (onTimeoutCancel != null) {
                try {
                    onTimeoutCancel.run();
                } catch (Exception e) {
                    log.warn("onTimeoutCancel hook failed for {}", callId, e);
                }
            }
            pc.dr.setResult(ToolResult.err(
                    new JsonRpcError(JsonRpcError.TOOL_TIMEOUT, "Tool call timed out")));
            log.debug("Call {} timed out after {}ms", callId, timeoutMs);
        }, timeoutMs, TimeUnit.MILLISECONDS);

        pending.put(callId, new PendingCall(dr, timer));

        dr.onCompletion(() -> {
            PendingCall removed = pending.remove(callId);
            if (removed != null && removed.timer != null) {
                removed.timer.cancel(false);
            }
        });
        dr.onError(t -> {
            PendingCall removed = pending.remove(callId);
            if (removed != null && removed.timer != null) {
                removed.timer.cancel(false);
            }
            log.warn("Call {} errored", callId, t);
        });

        return dr;
    }

    /** Resolve the call with a successful or error tool result. Returns false if no such callId is pending. */
    public boolean complete(UUID callId, ToolResult result) {
        PendingCall pc = pending.remove(callId);
        if (pc == null) {
            log.debug("complete({}) — no pending call (already resolved or unknown)", callId);
            return false;
        }
        if (pc.timer != null) {
            pc.timer.cancel(false);
        }
        return pc.dr.setResult(result);
    }

    /** Resolve the call with a {@code TOOL_CANCELLED} error. */
    public boolean cancel(UUID callId, String reason) {
        PendingCall pc = pending.remove(callId);
        if (pc == null) {
            return false;
        }
        if (pc.timer != null) {
            pc.timer.cancel(false);
        }
        return pc.dr.setResult(ToolResult.err(
                new JsonRpcError(JsonRpcError.TOOL_CANCELLED, reason == null ? "cancelled" : reason)));
    }

    public int pendingCount() {
        return pending.size();
    }
}
