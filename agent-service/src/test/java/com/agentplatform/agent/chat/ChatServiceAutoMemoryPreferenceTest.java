package com.agentplatform.agent.chat;

import com.agentplatform.agent.ai.ChatEventSink;
import com.agentplatform.agent.ai.ConfiguredProvider;
import com.agentplatform.agent.ai.EmbeddingService;
import com.agentplatform.agent.ai.MemoryExtractor;
import com.agentplatform.agent.ai.RemoteDeviceToolCallbackProvider;
import com.agentplatform.agent.ai.ResolvedTools;
import com.agentplatform.agent.ai.ServerToolRegistry;
import com.agentplatform.agent.ai.SessionSummaryRefresher;
import com.agentplatform.agent.ai.SkillLoadCallback;
import com.agentplatform.agent.ai.SkillRegistry;
import com.agentplatform.agent.client.DeviceToolDispatcher;
import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.chat.QueryFactRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatServiceAutoMemoryPreferenceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void disabledAutoMemorySkipsRecallAndExtraction() throws Exception {
        Fixture fixture = new Fixture(new ContextAssembler.UserContextSettings("rules", false));

        invokeHandleWithLlm(fixture.service);

        verifyNoInteractions(fixture.embeddingService);
        verifyNoInteractions(fixture.memoryExtractor);
    }

    @Test
    void enabledAutoMemoryKeepsRecallAndExtraction() throws Exception {
        Fixture fixture = new Fixture(new ContextAssembler.UserContextSettings("rules", true));
        when(fixture.embeddingService.embed("hello")).thenReturn(new float[] {0.1f, 0.2f});
        when(fixture.chatClient.queryFacts(any(QueryFactRequest.class))).thenReturn(List.of());

        invokeHandleWithLlm(fixture.service);

        verify(fixture.embeddingService).embed("hello");
        verify(fixture.memoryExtractor).extractAsync(any(), any(), any(), any());
    }

    private void invokeHandleWithLlm(ChatService service) throws Exception {
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
    }

    private class Fixture {
        final InternalChatFeignClient chatClient = mock(InternalChatFeignClient.class);
        final EmbeddingService embeddingService = mock(EmbeddingService.class);
        final MemoryExtractor memoryExtractor = mock(MemoryExtractor.class);
        final ChatService service;

        Fixture(ContextAssembler.UserContextSettings settings) {
            RemoteDeviceToolCallbackProvider toolProvider = mock(RemoteDeviceToolCallbackProvider.class);
            when(toolProvider.getForUser(any(), any()))
                    .thenReturn(new ResolvedTools(List.of(), Map.of()));

            ContextAssembler contextAssembler = mock(ContextAssembler.class);
            when(contextAssembler.loadUserContextSettings(any())).thenReturn(settings);
            when(contextAssembler.assemble(any(), any(), any(), any(), any()))
                    .thenReturn(new ContextBundle(
                            "system",
                            List.of(),
                            "hello",
                            new ArrayList<>(),
                            List.of(),
                            List.of(),
                            null,
                            new ContextStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 48_000)));

            AgentProperties props = props();
            SkillLoadCallback skillLoad = new SkillLoadCallback(new SkillRegistry(chatClient), mapper);
            service = new ChatService(
                    toolProvider,
                    chatClient,
                    mock(ExecutorService.class),
                    mapper,
                    props,
                    List.of(new ConfiguredProvider("primary", "anthropic-messages", null, null, null, "model")),
                    skillLoad,
                    new ServerToolRegistry(List.of(), mapper),
                    embeddingService,
                    memoryExtractor,
                    new SuccessfulAgentLoopRunner(mapper, props),
                    mock(CodexResponsesLoopRunner.class),
                    contextAssembler,
                    new ToolArtifactExtractor(mapper),
                    mock(SessionSummaryRefresher.class),
                    mock(DeviceToolDispatcher.class));
        }
    }

    private static AgentProperties props() {
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
                                null, null, null, null, 12, 18, 48_000, 1_200, true),
                        null));
    }

    private static class SuccessfulAgentLoopRunner extends AgentLoopRunner {

        SuccessfulAgentLoopRunner(ObjectMapper mapper, AgentProperties props) {
            super(mapper, props, mock(SkillLoadCallback.class), new ServerToolRegistry(List.of(), mapper));
        }

        @Override
        public RunResult run(RunRequest request) {
            return new RunResult("done", null, false);
        }
    }
}
