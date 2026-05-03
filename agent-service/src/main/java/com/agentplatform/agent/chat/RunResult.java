package com.agentplatform.agent.chat;

import com.anthropic.models.messages.Usage;
import org.springframework.lang.Nullable;

/**
 * Outcome of one {@link AgentLoopRunner#run} call.
 *
 * <p>{@code cancelled = true} means the SSE emitter ended (client esc / tab
 * close / spring-mvc async-request timeout) before the loop completed
 * cleanly, so the assistant text in {@code assistantText} may be a partial
 * snippet. Callers must NOT persist a cancelled reply — replaying truncated
 * text on the next turn pollutes LLM context.
 *
 * <p>{@code usage} is the last seen Anthropic Usage record (prompt cache
 * stats etc); may be {@code null} if no streaming response landed before
 * cancellation.
 */
public record RunResult(String assistantText, @Nullable Usage usage, boolean cancelled) {
}
