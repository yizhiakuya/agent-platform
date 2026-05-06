package com.agentplatform.agent.chat;

import com.agentplatform.agent.ai.ChatEventSink;
import com.agentplatform.agent.ai.ConfiguredProvider;
import com.agentplatform.agent.ai.EmbeddingService;
import com.agentplatform.agent.ai.MemoryExtractor;
import com.agentplatform.agent.ai.RemoteDeviceToolCallbackProvider;
import com.agentplatform.agent.ai.ResolvedTools;
import com.agentplatform.agent.ai.SessionSummaryRefresher;
import com.agentplatform.agent.ai.SkillLoadCallback;
import com.agentplatform.agent.client.DeviceHubClient;
import com.agentplatform.agent.client.DeviceToolDispatcher;
import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.chat.CreateSessionRequest;
import com.agentplatform.api.chat.MemoryFactDto;
import com.agentplatform.api.chat.MessageDto;
import com.agentplatform.api.chat.MessageRole;
import com.agentplatform.api.chat.QueryFactRequest;
import com.agentplatform.api.chat.SessionDto;
import com.agentplatform.api.chat.UpsertSessionArtifactRequest;
import com.agentplatform.api.chat.WriteMessageRequest;
import com.agentplatform.api.hub.OnlineDeviceDto;
import com.agentplatform.protocol.ToolResult;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * SSE-streaming chat orchestrator. Per-request flow:
 * <ol>
 *   <li>Create / resolve {@code sessionId}; persist the user turn.</li>
 *   <li>If a real LLM provider is configured, drive
 *       {@link AgentLoopRunner#run} through the SDK agentic loop;
 *       otherwise fall back to {@link #handleWithMock} (one hard-coded tool
 *       call, dev-only).</li>
 *   <li>Persist the final assistant text + trigger async memory extraction.</li>
 * </ol>
 *
 * <p>Heavy lifting is split into:
 * <ul>
 *   <li>{@link PromptAssembler} — pure-function prompt + tool-list builders</li>
 *   <li>{@link HistoryReplayer} — chat-service history → SDK MessageParam list</li>
 *   <li>{@link AgentLoopRunner} — SDK streaming + tool_use loop + cancel binding</li>
 * </ul>
 * This class is the boundary that wires SSE / persistence / memory / SDK
 * together; it doesn't itself touch the SDK message API.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** Hard cap matching MemoryService.MAX_TOP_K — avoids pointless inflation. */
    private static final int MAX_MEMORY_TOP_K = 50;

    private final DeviceHubClient deviceHubClient;
    private final DeviceToolDispatcher dispatcher;
    private final RemoteDeviceToolCallbackProvider toolProvider;
    private final InternalChatFeignClient chatClientFeign;
    private final ExecutorService chatExecutor;
    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final List<ConfiguredProvider> chatClients;
    private final SkillLoadCallback skillLoadCallback;
    private final EmbeddingService embeddingService;
    private final MemoryExtractor memoryExtractor;
    private final AgentLoopRunner agentLoopRunner;
    private final CodexResponsesLoopRunner codexResponsesLoopRunner;
    private final ContextAssembler contextAssembler;
    private final ToolArtifactExtractor artifactExtractor;
    private final SessionSummaryRefresher sessionSummaryRefresher;

    public ChatService(DeviceHubClient deviceHubClient,
                       DeviceToolDispatcher dispatcher,
                       RemoteDeviceToolCallbackProvider toolProvider,
                       InternalChatFeignClient chatClientFeign,
                       ExecutorService chatExecutor,
                       ObjectMapper mapper,
                       AgentProperties props,
                       List<ConfiguredProvider> chatClients,
                       SkillLoadCallback skillLoadCallback,
                       EmbeddingService embeddingService,
                       MemoryExtractor memoryExtractor,
                       AgentLoopRunner agentLoopRunner,
                       CodexResponsesLoopRunner codexResponsesLoopRunner,
                       ContextAssembler contextAssembler,
                       ToolArtifactExtractor artifactExtractor,
                       SessionSummaryRefresher sessionSummaryRefresher) {
        this.deviceHubClient = deviceHubClient;
        this.dispatcher = dispatcher;
        this.toolProvider = toolProvider;
        this.chatClientFeign = chatClientFeign;
        this.chatExecutor = chatExecutor;
        this.mapper = mapper;
        this.props = props;
        this.chatClients = chatClients == null ? List.of() : chatClients;
        this.skillLoadCallback = skillLoadCallback;
        this.embeddingService = embeddingService;
        this.memoryExtractor = memoryExtractor;
        this.agentLoopRunner = agentLoopRunner;
        this.codexResponsesLoopRunner = codexResponsesLoopRunner;
        this.contextAssembler = contextAssembler;
        this.artifactExtractor = artifactExtractor;
        this.sessionSummaryRefresher = sessionSummaryRefresher;
    }

    public void handle(UUID userId, ChatRequest req, SseEmitter emitter) {
        chatExecutor.execute(() -> handleInternal(userId, req, emitter));
    }

    private void handleInternal(UUID userId, ChatRequest req, SseEmitter emitter) {
        try {
            UUID sessionId = ensureSession(userId, req.sessionId(), req.message());
            // Tell the web client what session we're using BEFORE any other event,
            // so it can echo sessionId back on subsequent /api/chat/stream calls
            // and the LLM actually sees prior turns.
            send(emitter, SseEvent.session(mapper, sessionId));
            send(emitter, SseEvent.userMessage(mapper, req.message()));
            persist(sessionId, userId, MessageRole.USER, req.message(), null);

            if (!chatClients.isEmpty()) {
                handleWithLlm(userId, sessionId, req, emitter);
            } else {
                handleWithMock(userId, sessionId, req, emitter);
            }
        } catch (Exception e) {
            log.warn("Chat handling failed for user {}", userId, e);
            safeSend(emitter, SseEvent.error(mapper, userFacingGeneralFailureMessage(e)));
            emitter.completeWithError(e);
        }
    }

    /* -------------------- real LLM path -------------------- */

    private void handleWithLlm(UUID userId, UUID sessionId, ChatRequest req, SseEmitter emitter) {
        ResolvedTools resolved = toolProvider.getForUser(userId);
        if (resolved.definitions().isEmpty()) {
            // No tools registered yet (no real device bound). Fall back to the
            // mock path so we still emit a useful SSE response —
            // InternalToolController auto-provisions a MockDeviceSession when
            // mock-mode is on.
            log.info("LLM path has no device tools — falling back to mock for user {}", userId);
            try {
                handleWithMock(userId, sessionId, req, emitter);
            } catch (IOException e) {
                safeSend(emitter, SseEvent.error(mapper, "Fallback failed: " + e.getMessage()));
                emitter.completeWithError(e);
            }
            return;
        }

        // Cache layout — system text is bit-identical across requests so the
        // ephemeral cache breakpoint hits cleanly. Per-request memories ride
        // along on the user message instead so they don't churn the cache.
        List<MemoryFactDto> memories = recallMemories(userId, req.message());
        ContextBundle context = contextAssembler.assemble(userId, sessionId, req.message(), memories);
        List<ToolUnion> tools = PromptAssembler.buildToolUnionList(
                resolved.definitions(),
                skillLoadCallback.toAnthropicTool(),
                props.agent().memory());

        ThinkingConfigEnabled thinking = null;
        int budget = props.agent().memory().thinkingBudgetTokens();
        if (budget >= 1024) {
            thinking = ThinkingConfigEnabled.builder().budgetTokens(budget).build();
        }

        List<MessageParam> messages = context.anthropicMessages();
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(context.userText())
                .build());

        ChatEventSink sink = ev -> emitAndPersistToolEvent(emitter, sessionId, userId, ev);

        long t0 = System.currentTimeMillis();

        // Provider failover — synchronous setup only. If the SDK throws on
        // createStreaming we try the next provider; once stream events start
        // flowing we don't switch (TODO P2: mid-stream failover).
        RunResult result = null;
        RuntimeException lastErr = null;
        for (ConfiguredProvider provider : chatClients) {
            try {
                result = runProvider(provider, sessionId, userId, resolved,
                        context.stableSystemText(), context.systemBlocks(), tools, thinking,
                        messages, context.historyRows(), context.userText(), sink, emitter);
                break;
            } catch (RuntimeException e) {
                lastErr = e;
                log.warn("[agent] provider '{}' failed: {} — trying next", provider.name(), e.getMessage());
            }
        }
        if (result == null) {
            log.warn("[agent] all providers failed for user {} session {}",
                    userId, sessionId, lastErr);
            safeSend(emitter, SseEvent.error(mapper, userFacingProviderFailureMessage(lastErr)));
            emitter.complete();
            return;
        }

        if (result.cancelled()) {
            // SSE emitter ended (esc / tab close / async timeout). The text
            // buffer may be partial — persisting it would replay a truncated
            // assistant turn next session and pollute LLM context. Skip
            // persist + memory extraction; emitter is already closed by the
            // cancel callback so there's no SSE work left.
            log.info("Chat cancelled mid-stream for user {} session {} — skipping persist", userId, sessionId);
            return;
        }

        if (result.exhausted()) {
            String message = result.exhaustionReason() == null || result.exhaustionReason().isBlank()
                    ? "任务还没完成，但本轮工具调用次数已达到上限。请发送“继续”，我会接着做。"
                    : result.exhaustionReason();
            log.info("Chat exhausted tool iterations for user {} session {}: {}", userId, sessionId, message);
            safeSend(emitter, SseEvent.error(mapper, message));
            long durMs = System.currentTimeMillis() - t0;
            persist(sessionId, userId, MessageRole.ASSISTANT, message, assistantMetadata(durMs));
            logUsage(userId, result.usage(), durMs);
            emitter.complete();
            return;
        }

        String fullReply = result.assistantText();
        long durMs = System.currentTimeMillis() - t0;
        persist(sessionId, userId, MessageRole.ASSISTANT, fullReply, assistantMetadata(durMs));
        logUsage(userId, result.usage(), durMs);
        try {
            memoryExtractor.extractAsync(userId, sessionId, req.message(), fullReply);
        } catch (Exception ex) {
            log.warn("memoryExtractor.extractAsync failed: {}", ex.getMessage());
        }
        try {
            sessionSummaryRefresher.refreshAsync(userId, sessionId);
        } catch (Exception ex) {
            log.warn("sessionSummaryRefresher.refreshAsync failed: {}", ex.getMessage());
        }
        emitter.complete();
    }

    private RunResult runProvider(ConfiguredProvider provider,
                                  UUID sessionId,
                                  UUID userId,
                                  ResolvedTools resolved,
                                  String stableSystemText,
                                  List<TextBlockParam> systemBlocks,
                                  List<ToolUnion> tools,
                                  ThinkingConfigEnabled thinking,
                                  List<MessageParam> messages,
                                  List<MessageDto> historyRows,
                                  String userText,
                                  ChatEventSink sink,
                                  SseEmitter emitter) {
        if (provider.isAnthropicMessages()) {
            return agentLoopRunner.run(provider, sessionId, userId, resolved,
                    systemBlocks, tools, thinking, messages, sink, emitter);
        }
        if (provider.isCodexResponses()) {
            return codexResponsesLoopRunner.run(provider, sessionId, userId, resolved,
                    stableSystemText,
                    historyRows,
                    userText, sink, emitter);
        }
        throw new IllegalArgumentException("unsupported provider kind: " + provider.kind());
    }

    static String userFacingProviderFailureMessage(@Nullable Throwable error) {
        String raw = error == null || error.getMessage() == null
                ? ""
                : error.getMessage().toLowerCase();
        if (raw.contains("no available accounts")
                || raw.contains("service unavailable")
                || raw.contains("status code 503")
                || raw.contains("503")) {
            return "模型服务暂时不可用，可能是上游账号池没有可用账号。请稍后再试。";
        }
        if (raw.contains("rate limit")
                || raw.contains("too many requests")
                || raw.contains("quota")
                || raw.contains("429")) {
            return "模型服务当前被限流或额度不足，请稍后再试。";
        }
        if (raw.contains("unauthorized")
                || raw.contains("forbidden")
                || raw.contains("invalid api key")
                || raw.contains("401")
                || raw.contains("403")) {
            return "模型服务认证配置异常，请稍后联系管理员处理。";
        }
        if (raw.contains("timeout")
                || raw.contains("timed out")
                || raw.contains("connection refused")
                || raw.contains("connection reset")) {
            return "模型服务连接超时或网络暂时不可达，请稍后再试。";
        }
        return "模型服务暂时无法完成请求，请稍后再试。";
    }

    static String userFacingGeneralFailureMessage(Throwable error) {
        if (isLikelyProviderFailure(error)) {
            return userFacingProviderFailureMessage(error);
        }
        return "请求处理失败，请稍后再试。";
    }

    private static boolean isLikelyProviderFailure(@Nullable Throwable error) {
        String raw = error == null || error.getMessage() == null
                ? ""
                : error.getMessage().toLowerCase();
        return raw.contains("no available accounts")
                || raw.contains("service unavailable")
                || raw.contains("api_error")
                || raw.contains("rate limit")
                || raw.contains("too many requests")
                || raw.contains("quota")
                || raw.contains("unauthorized")
                || raw.contains("forbidden")
                || raw.contains("invalid api key")
                || raw.contains("timeout")
                || raw.contains("timed out")
                || raw.contains("connection refused")
                || raw.contains("connection reset")
                || raw.contains("status code 503")
                || raw.contains("503")
                || raw.contains("429")
                || raw.contains("401")
                || raw.contains("403");
    }

    private void logUsage(UUID userId, Object usage, long durMs) {
        if (usage instanceof Usage anthropicUsage) {
            AgentLoopRunner.logCacheUsage(userId, anthropicUsage, durMs);
        } else {
            log.info("promptUsage user={} provider=codex usage={} dur={}ms",
                    userId, usage == null ? "unavailable" : usage, durMs);
        }
    }

    /* -------------------- mock path -------------------- */

    private void handleWithMock(UUID userId, UUID sessionId, ChatRequest req, SseEmitter emitter) throws IOException {
        UUID deviceId = req.deviceId();
        if (deviceId == null) {
            List<OnlineDeviceDto> online = deviceHubClient.listOnlineByUser(userId);
            if (online == null || online.isEmpty()) {
                send(emitter, SseEvent.error(mapper, "No online devices for this user"));
                emitter.complete();
                return;
            }
            deviceId = online.get(0).deviceId();
        }

        String toolName = props.agent().mockToolName();
        JsonNode args = mapper.readTree(props.agent().mockToolArgsJson());

        send(emitter, SseEvent.toolCallStarted(mapper, deviceId, toolName, args));
        persist(sessionId, userId, MessageRole.TOOL_CALL, "calling " + toolName,
                toolCallMetadata(deviceId, toolName, args));

        ToolResult result = dispatcher.dispatch(deviceId, userId, toolName, args);

        if (result.hasError()) {
            send(emitter, SseEvent.error(mapper,
                    "Tool '" + toolName + "' failed: " + result.error().message()));
            persist(sessionId, userId, MessageRole.TOOL_RESULT,
                    "tool " + toolName + " failed: " + result.error().message(), null);
        } else {
            send(emitter, SseEvent.toolCallResult(mapper, toolName, result.value()));
            MessageDto saved = persist(sessionId, userId, MessageRole.TOOL_RESULT,
                    "tool " + toolName + " returned",
                    toolResultMetadata(toolName, result.value()));
            persistArtifacts(sessionId, userId, saved == null ? null : saved.id(), toolName, result.value());
            String reply = "[mock-llm] Tool '" + toolName + "' returned the result above. " +
                    "Set ANTHROPIC_API_KEY to use a real LLM.";
            send(emitter, SseEvent.assistantMessage(mapper, reply));
            persist(sessionId, userId, MessageRole.ASSISTANT, reply, null);
        }
        emitter.complete();
    }

    /* -------------------- per-request loaders -------------------- */

    private List<MemoryFactDto> recallMemories(UUID userId, String query) {
        if (query == null || query.isBlank()) return List.of();
        try {
            float[] qEmb = embeddingService.embed(query);
            // chat-service splits the topK budget across curated + raw tiers
            // internally; ask for a bit more so both sections have room.
            int baseTopK = props.agent().memory().topK();
            int topK = Math.min(MAX_MEMORY_TOP_K, Math.max(baseTopK, baseTopK + 2));
            List<MemoryFactDto> hits = chatClientFeign.queryFacts(
                    new QueryFactRequest(userId, qEmb, topK));
            return hits == null ? List.of() : hits;
        } catch (Exception e) {
            log.debug("recallMemories failed for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /* -------------------- chat-service Feign helpers -------------------- */

    /** Returns the existing sessionId or creates a new one (best-effort, never throws). */
    private UUID ensureSession(UUID userId, UUID requestedSessionId, String firstMessage) {
        if (requestedSessionId != null) return requestedSessionId;
        try {
            String title = firstMessage == null
                    ? null
                    : firstMessage.substring(0, Math.min(64, firstMessage.length()));
            SessionDto created = chatClientFeign.createSession(new CreateSessionRequest(userId, title));
            return created.id();
        } catch (Exception e) {
            log.warn("Failed to create chat session for user {}: {}", userId, e.getMessage());
            return null;  // null sessionId → persist() becomes a no-op
        }
    }

    private MessageDto persist(UUID sessionId, UUID userId, MessageRole role,
                               String content, @Nullable JsonNode metadata) {
        if (sessionId == null) return null;  // session creation failed earlier
        try {
            return chatClientFeign.writeMessage(new WriteMessageRequest(
                    sessionId, userId, role, content, metadata));
        } catch (Exception e) {
            log.warn("Failed to persist {} message for session {}: {}",
                    role, sessionId, e.getMessage());
            return null;
        }
    }

    private JsonNode toolCallMetadata(UUID deviceId, String toolName, JsonNode args) {
        ObjectNode m = mapper.createObjectNode();
        m.put("deviceId", deviceId == null ? null : deviceId.toString());
        m.put("tool", toolName);
        m.set("args", args == null ? mapper.createObjectNode() : args);
        return m;
    }

    private JsonNode toolResultMetadata(String toolName, JsonNode result) {
        ObjectNode m = mapper.createObjectNode();
        m.put("tool", toolName);
        m.set("result", result == null ? mapper.createObjectNode() : result);
        return m;
    }

    private JsonNode assistantMetadata(long durationMs) {
        ObjectNode m = mapper.createObjectNode();
        m.put("durationMs", Math.max(0L, durationMs));
        return m;
    }

    private void emitAndPersistToolEvent(SseEmitter emitter, UUID sessionId, UUID userId, SseEvent event) {
        safeSend(emitter, event);
        if (event == null || event.data() == null || event.data().isNull()) return;
        if ("tool_call_started".equals(event.type())) {
            JsonNode data = event.data();
            String tool = data.path("tool").asText("tool");
            UUID deviceId = parseUuid(data.path("deviceId").asText(null));
            JsonNode args = data.path("args").isMissingNode() ? mapper.createObjectNode() : data.path("args");
            persist(sessionId, userId, MessageRole.TOOL_CALL, "calling " + tool,
                    toolCallMetadata(deviceId, tool, args));
        } else if ("tool_call_result".equals(event.type())) {
            JsonNode data = event.data();
            String tool = data.path("tool").asText("tool");
            JsonNode result = data.path("result").isMissingNode() ? mapper.createObjectNode() : data.path("result");
            MessageDto saved = persist(sessionId, userId, MessageRole.TOOL_RESULT, "tool " + tool + " returned",
                    toolResultMetadata(tool, result));
            persistArtifacts(sessionId, userId, saved == null ? null : saved.id(), tool, result);
        }
    }

    private void persistArtifacts(UUID sessionId, UUID userId, UUID messageId, String tool, JsonNode result) {
        List<UpsertSessionArtifactRequest> artifacts =
                artifactExtractor.extract(sessionId, userId, messageId, tool, result);
        for (UpsertSessionArtifactRequest artifact : artifacts) {
            try {
                chatClientFeign.upsertArtifact(artifact);
            } catch (Exception e) {
                log.debug("Failed to persist artifact {} for session {}: {}",
                        artifact.artifactKey(), sessionId, e.getMessage());
            }
        }
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /* -------------------- sse helpers -------------------- */

    private void send(SseEmitter emitter, SseEvent event) throws IOException {
        emitter.send(SseEmitter.event().name(event.type()).data(event.data()));
    }

    private void safeSend(SseEmitter emitter, SseEvent event) {
        try {
            send(emitter, event);
        } catch (IOException e) {
            log.debug("SSE send failed (client likely disconnected): {}", e.getMessage());
        }
    }
}
