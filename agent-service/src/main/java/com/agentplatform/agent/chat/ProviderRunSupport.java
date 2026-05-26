package com.agentplatform.agent.chat;

import com.agentplatform.agent.ai.ChatEventSink;
import com.agentplatform.agent.ai.ConfiguredProvider;
import com.agentplatform.agent.ai.ResolvedTools;
import com.agentplatform.agent.config.AgentProperties;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ProviderRunSupport {

    private ProviderRunSupport() {
    }

    static ToolCallBudget toolBudget(AgentProperties props) {
        return new ToolCallBudget(
                props.agent().maxToolCallsPerTurn(),
                props.agent().maxConsecutiveUiToolCalls());
    }

    static RunResult exhausted(String assistantText, Object usage, ToolCallBudget toolBudget, int maxIterations) {
        return RunResult.exhausted(
                assistantText,
                usage,
                "任务还没完成，但本轮思考/工具循环已达到上限（" + maxIterations + " 轮，已调用 "
                        + toolBudget.used() + "/" + toolBudget.maxToolCalls()
                        + " 个工具）。请发送“继续”，我会接着当前页面状态往下做。"
        );
    }

    static class Request<T extends Request<T>> {
        ConfiguredProvider provider;
        UUID sessionId;
        UUID userId;
        ResolvedTools resolved;
        ChatEventSink sink;
        SseEmitter emitter;
        ChatCancellationToken cancellation;

        public T withProvider(ConfiguredProvider provider) {
            this.provider = provider;
            return self();
        }

        public T withSessionId(UUID sessionId) {
            this.sessionId = sessionId;
            return self();
        }

        public T withUserId(UUID userId) {
            this.userId = userId;
            return self();
        }

        public T withResolved(ResolvedTools resolved) {
            this.resolved = resolved;
            return self();
        }

        public T withSink(ChatEventSink sink) {
            this.sink = sink;
            return self();
        }

        public T withEmitter(SseEmitter emitter) {
            this.emitter = emitter;
            return self();
        }

        public T withCancellation(ChatCancellationToken cancellation) {
            this.cancellation = cancellation;
            return self();
        }

        ResolvedTools resolved() {
            if (resolved == null) {
                resolved = new ResolvedTools(List.of(), Map.of());
            }
            return resolved;
        }

        ChatEventSink sink() {
            if (sink == null) {
                sink = event -> { };
            }
            return sink;
        }

        SseEmitter emitter() {
            if (emitter == null) {
                emitter = new SseEmitter();
            }
            return emitter;
        }

        ChatCancellationToken cancellation() {
            if (cancellation == null) {
                cancellation = new ChatCancellationToken();
            }
            return cancellation;
        }

        @SuppressWarnings("unchecked")
        private T self() {
            return (T) this;
        }
    }

    static class State {
        ConfiguredProvider provider;
        UUID sessionId;
        UUID userId;
        ResolvedTools resolved;
        ChatEventSink sink;
        SseEmitter emitter;
        ChatCancellationToken cancellation;
        StringBuilder textBuf;
        ToolCallBudget toolBudget;

        void apply(Request<?> request) {
            provider = request.provider;
            sessionId = request.sessionId;
            userId = request.userId;
            resolved = request.resolved();
            sink = request.sink();
            emitter = request.emitter();
            cancellation = request.cancellation();
            textBuf = new StringBuilder();
        }
    }
}
