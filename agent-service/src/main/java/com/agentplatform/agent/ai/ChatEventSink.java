package com.agentplatform.agent.ai;

import com.agentplatform.agent.chat.SseEvent;

/**
 * Functional bridge between Spring AI tool callbacks and the per-request
 * {@code SseEmitter}. We don't pass {@code SseEmitter} directly into
 * {@code ToolContext} so the AI layer stays free of Spring MVC types.
 *
 * <p>The implementation lives inside {@code ChatService} as a lambda that
 * wraps the current request's {@code SseEmitter}.
 */
@FunctionalInterface
public interface ChatEventSink {

    /** Key under which the sink is placed into Spring AI's {@code ToolContext}. */
    String KEY = "agent.event.sink";

    void emit(SseEvent event);
}
