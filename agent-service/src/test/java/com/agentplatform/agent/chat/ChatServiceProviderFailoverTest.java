package com.agentplatform.agent.chat;

import com.agentplatform.agent.ai.ChatEventSink;
import com.agentplatform.agent.ai.ConfiguredProvider;
import com.agentplatform.agent.ai.EmbeddingService;
import com.agentplatform.agent.ai.MemoryExtractor;
import com.agentplatform.agent.ai.RemoteDeviceToolCallbackProvider;
import com.agentplatform.agent.ai.ResolvedTools;
import com.agentplatform.agent.ai.ServerToolCallback;
import com.agentplatform.agent.ai.ServerToolRegistry;
import com.agentplatform.agent.ai.SessionSummaryRefresher;
import com.agentplatform.agent.ai.SkillLoadCallback;
import com.agentplatform.agent.ai.SkillRegistry;
import com.agentplatform.agent.client.DeviceToolDispatcher;
import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceProviderFailoverTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void doesNotTryNextProviderAfterProviderHasStartedStreaming() throws Exception {
        AtomicInteger codexCalls = new AtomicInteger();
        ChatService service = service(codexCalls);

        Method method = ChatService.class.getDeclaredMethod(
                "handleWithLlm",
                UUID.class,
                UUID.class,
                ChatRequest.class,
                SseEmitter.class);
        method.setAccessible(true);
        method.invoke(
                service,
                UUID.randomUUID(),
                UUID.randomUUID(),
                new ChatRequest("hello", null, null),
                new SseEmitter());

        assertThat(codexCalls.get()).isZero();
    }

    @Test
    void specifiedDeviceWithoutToolsShortCircuitsBeforeProviderCall() throws Exception {
        AtomicInteger codexCalls = new AtomicInteger();
        AgentLoopRunner agentLoop = mock(AgentLoopRunner.class);
        ChatService service = service(codexCalls, agentLoop, new ResolvedTools(List.of(), Map.of()));

        Method method = ChatService.class.getDeclaredMethod(
                "handleWithLlm",
                UUID.class,
                UUID.class,
                ChatRequest.class,
                SseEmitter.class);
        method.setAccessible(true);
        method.invoke(
                service,
                UUID.randomUUID(),
                UUID.randomUUID(),
                new ChatRequest("open wechat", null, UUID.randomUUID()),
                new SseEmitter());

        assertThat(codexCalls.get()).isZero();
        verifyNoInteractions(agentLoop);
    }

    @Test
    void noConfiguredProviderFailsFastWithoutDeviceMockFallback() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RemoteDeviceToolCallbackProvider toolProvider = mock(RemoteDeviceToolCallbackProvider.class);
            InternalChatFeignClient chatClient = mock(InternalChatFeignClient.class);
            ChatService service = new ChatService(
                    toolProvider,
                    chatClient,
                    executor,
                    mapper,
                    props(),
                    List.of(),
                    mock(SkillLoadCallback.class),
                    new ServerToolRegistry(List.of(), mapper),
                    mock(EmbeddingService.class),
                    mock(MemoryExtractor.class),
                    mock(AgentLoopRunner.class),
                    mock(CodexResponsesLoopRunner.class),
                    mock(ContextAssembler.class),
                    new ToolArtifactExtractor(mapper),
                    mock(SessionSummaryRefresher.class),
                    mock(DeviceToolDispatcher.class));
            CapturingEmitter emitter = new CapturingEmitter();

            service.handle(UUID.randomUUID(), new ChatRequest("hello", null, null), emitter);

            await().atMost(java.time.Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(emitter.events)
                            .anyMatch(e -> "error".equals(e.type())
                                    && e.data().path("message").asText().contains("模型服务未配置")));
            verifyNoInteractions(toolProvider);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    private ChatService service(AtomicInteger codexCalls) {
        return service(codexCalls, new StartedThenFailingAgentLoopRunner(mapper, props()),
                new ResolvedTools(List.of(), Map.of()));
    }

    private ChatService service(AtomicInteger codexCalls,
                                AgentLoopRunner agentLoop,
                                ResolvedTools resolvedTools) {
        InternalChatFeignClient chatClient = mock(InternalChatFeignClient.class);
        AgentProperties props = props();
        SkillLoadCallback skillLoad = new SkillLoadCallback(new SkillRegistry(chatClient), mapper);
        ServerToolRegistry serverTools = new ServerToolRegistry(List.of(new MarkerTool()), mapper);
        ContextAssembler contextAssembler = mock(ContextAssembler.class);
        RemoteDeviceToolCallbackProvider toolProvider = mock(RemoteDeviceToolCallbackProvider.class);
        when(toolProvider.getForUser(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(resolvedTools);
        when(contextAssembler.assemble(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ContextBundle(
                        "system",
                        List.of(),
                        "hello",
                        new ArrayList<>(),
                        List.of(),
                        List.of(),
                        null,
                        new ContextStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 48_000)));

        return new ChatService(
                toolProvider,
                chatClient,
                mock(ExecutorService.class),
                mapper,
                props,
                List.of(
                        new ConfiguredProvider("first", "anthropic-messages", null, null, null, "model"),
                        new ConfiguredProvider("second", "codex-responses", null, "http://127.0.0.1", "token", "model")),
                skillLoad,
                serverTools,
                mock(EmbeddingService.class),
                mock(MemoryExtractor.class),
                agentLoop,
                new CountingCodexRunner(mapper, props, codexCalls),
                contextAssembler,
                new ToolArtifactExtractor(mapper),
                mock(SessionSummaryRefresher.class),
                mock(DeviceToolDispatcher.class));
    }

    private AgentProperties props() {
        return new AgentProperties(
                new AgentProperties.Jwt("secret", "issuer"),
                new AgentProperties.Agent(
                        35_000,
                        "http://device-hub-service:8080",
                        4096,
                        24,
                        24,
                        10,
                        List.of(),
                        new AgentProperties.Memory(
                                null, 0, 0, null, null, 0, null, 0, false, 5,
                                null, null, null, 12, 18, 48_000, 1_200, true),
                        null));
    }

    private static class MarkerTool implements ServerToolCallback {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Override
        public String name() {
            return "marker_tool";
        }

        @Override
        public String description() {
            return "Marker tool";
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", MAPPER.createObjectNode());
            return schema;
        }

        @Override
        public com.agentplatform.agent.ai.ExecutionResult executeJsonToolUse(
                JsonNode args,
                UUID userId,
                UUID sessionId,
                ChatEventSink sink) {
            return com.agentplatform.agent.ai.ExecutionResult.text("ok");
        }
    }

    private static class StartedThenFailingAgentLoopRunner extends AgentLoopRunner {

        StartedThenFailingAgentLoopRunner(ObjectMapper mapper, AgentProperties props) {
            super(mapper, props, mock(SkillLoadCallback.class), new ServerToolRegistry(List.of(), mapper));
        }

        @Override
        public RunResult run(ConfiguredProvider provider,
                             UUID sessionId,
                             UUID userId,
                             ResolvedTools resolved,
                             List<com.anthropic.models.messages.TextBlockParam> systemBlocks,
                             List<com.anthropic.models.messages.ToolUnion> tools,
                             com.anthropic.models.messages.ThinkingConfigEnabled thinking,
                             List<com.anthropic.models.messages.MessageParam> messages,
                             ChatEventSink sink,
                             SseEmitter emitter) {
            sink.emit(SseEvent.assistantMessage(new ObjectMapper(), "partial"));
            throw new RuntimeException("stream failed");
        }
    }

    private static class CountingCodexRunner extends CodexResponsesLoopRunner {
        private final AtomicInteger calls;

        CountingCodexRunner(ObjectMapper mapper, AgentProperties props, AtomicInteger calls) {
            super(mapper, props, mock(SkillLoadCallback.class), new ServerToolRegistry(List.of(), mapper), WebClient.builder());
            this.calls = calls;
        }

        @Override
        public RunResult run(ConfiguredProvider provider,
                             UUID sessionId,
                             UUID userId,
                             ResolvedTools resolved,
                             String systemText,
                             List<com.agentplatform.api.chat.MessageDto> history,
                             String userText,
                             ChatEventSink sink,
                             SseEmitter emitter) {
            calls.incrementAndGet();
            return new RunResult("fallback", null, false);
        }
    }

    private static class CapturingEmitter extends SseEmitter {
        private final List<SseEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            for (ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                if (item.getData() instanceof com.fasterxml.jackson.databind.JsonNode data
                        && data.has("message")) {
                    events.add(new SseEvent("error", data));
                } else if (item.getData() instanceof com.fasterxml.jackson.databind.JsonNode data
                        && data.has("sessionId")) {
                    events.add(new SseEvent("session", data));
                } else if (item.getData() instanceof com.fasterxml.jackson.databind.JsonNode data
                        && data.has("content")) {
                    events.add(new SseEvent("message", data));
                }
            }
        }

        @Override
        public void send(Object object) throws IOException {
            if (object instanceof SseEvent event) {
                events.add(event);
            }
        }

        @Override
        public void send(Object object, org.springframework.http.MediaType mediaType) throws IOException {
            send(object);
        }
    }
}
