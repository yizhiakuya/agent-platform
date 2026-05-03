package com.agentplatform.agent.ai;

import com.agentplatform.agent.chat.SseEvent;

/**
 * Per-request channel for tool execution to push SSE events back to the web
 * client without depending on {@code SseEmitter} directly. {@code ChatService}
 * supplies a lambda wrapping the current request's emitter; tool callbacks
 * (device tools, skill_load) accept a sink param and emit
 * {@code tool_call_started} / {@code tool_call_result} through it.
 */
@FunctionalInterface
public interface ChatEventSink {

    void emit(SseEvent event);
}
