package com.agentplatform.agent.chat;

import org.springframework.lang.Nullable;

/**
 * Outcome of one provider runner call.
 *
 * <p>{@code cancelled=true} means the user explicitly stopped the run or the
 * server-side request timed out before the loop completed cleanly, so
 * {@code assistantText} may be partial. Callers must not persist a cancelled
 * reply because replaying truncated text pollutes LLM context. A plain SSE
 * client disconnect is not cancellation; the backend should finish and persist
 * the reply.
 *
 * <p>{@code usage} is the last seen provider usage object; it may be null if
 * no streaming response landed before cancellation.
 */
public record RunResult(
        String assistantText,
        @Nullable Object usage,
        boolean cancelled,
        boolean exhausted,
        @Nullable String exhaustionReason
) {
    public RunResult(String assistantText, @Nullable Object usage, boolean cancelled) {
        this(assistantText, usage, cancelled, false, null);
    }

    public static RunResult exhausted(String assistantText, @Nullable Object usage, String reason) {
        return new RunResult(assistantText, usage, false, true, reason);
    }
}
