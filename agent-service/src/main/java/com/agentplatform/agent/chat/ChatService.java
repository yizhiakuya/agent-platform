package com.agentplatform.agent.chat;

import com.agentplatform.agent.ai.ChatEventSink;
import com.agentplatform.agent.ai.ConfiguredProvider;
import com.agentplatform.agent.ai.EmbeddingService;
import com.agentplatform.agent.ai.MemoryExtractor;
import com.agentplatform.agent.ai.PendingImage;
import com.agentplatform.agent.ai.RemoteDeviceToolCallbackProvider;
import com.agentplatform.agent.ai.ResolvedTools;
import com.agentplatform.agent.ai.ServerToolRegistry;
import com.agentplatform.agent.ai.SessionSummaryRefresher;
import com.agentplatform.agent.ai.SkillLoadCallback;
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
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE-streaming chat orchestrator. Per-request flow:
 * <ol>
 *   <li>Create / resolve {@code sessionId}; persist the user turn.</li>
 *   <li>Drive the provider runner through the configured agentic loop.</li>
 *       through the configured provider's agentic loop.</li>
 *   <li>Persist the final assistant text + trigger async memory extraction.</li>
 * </ol>
 *
 * <p>Heavy lifting is split into:
 * <ul>
 *   <li>{@link PromptAssembler} — pure-function prompt + tool-list builders</li>
 *   <li>{@link HistoryReplayer} — chat-service history → SDK MessageParam list</li>
 *   <li>{@link AgentLoopRunner} — Anthropic SDK streaming + tool_use loop + cancel binding</li>
 *   <li>{@link CodexResponsesLoopRunner} — custom OpenAI Responses SSE loop for mobile tools</li>
 * </ul>
 * This class is the boundary that wires SSE / persistence / memory / SDK
 * together; background LLM tasks use LangChain4j via {@link com.agentplatform.agent.ai.BackgroundLlmClient},
 * while the live mobile agent loop keeps its provider-specific controls here.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** Hard cap matching MemoryService.MAX_TOP_K — avoids pointless inflation. */
    private static final int MAX_MEMORY_TOP_K = 50;
    private static final String EVENT_ASSISTANT_MESSAGE = "assistant_message";
    private static final String EVENT_TOOL_CALL_STARTED = "tool_call_started";
    private static final String EVENT_TOOL_CALL_RESULT = "tool_call_result";
    private static final String EVENT_TOOL_CALL_ERROR = "tool_call_error";
    private static final String EVENT_ERROR = "error";
    private static final String METADATA_RESULT = "result";

    private final RemoteDeviceToolCallbackProvider toolProvider;
    private final InternalChatFeignClient chatClientFeign;
    private final ExecutorService chatExecutor;
    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final List<ConfiguredProvider> chatClients;
    private final SkillLoadCallback skillLoadCallback;
    private final ServerToolRegistry serverToolRegistry;
    private final EmbeddingService embeddingService;
    private final MemoryExtractor memoryExtractor;
    private final AgentLoopRunner agentLoopRunner;
    private final CodexResponsesLoopRunner codexResponsesLoopRunner;
    private final ContextAssembler contextAssembler;
    private final ToolArtifactExtractor artifactExtractor;
    private final SessionSummaryRefresher sessionSummaryRefresher;
    private final DeviceToolDispatcher deviceToolDispatcher;
    private final ConcurrentMap<RunKey, ChatCancellationToken> activeRuns = new ConcurrentHashMap<>();

    public ChatService(RemoteDeviceToolCallbackProvider toolProvider,
                       InternalChatFeignClient chatClientFeign,
                       ExecutorService chatExecutor,
                       ObjectMapper mapper,
                       AgentProperties props,
                       List<ConfiguredProvider> chatClients,
                       SkillLoadCallback skillLoadCallback,
                       ServerToolRegistry serverToolRegistry,
                       EmbeddingService embeddingService,
                       MemoryExtractor memoryExtractor,
                       AgentLoopRunner agentLoopRunner,
                       CodexResponsesLoopRunner codexResponsesLoopRunner,
                       ContextAssembler contextAssembler,
                       ToolArtifactExtractor artifactExtractor,
                       SessionSummaryRefresher sessionSummaryRefresher,
                       DeviceToolDispatcher deviceToolDispatcher) {
        this.toolProvider = toolProvider;
        this.chatClientFeign = chatClientFeign;
        this.chatExecutor = chatExecutor;
        this.mapper = mapper;
        this.props = props;
        this.chatClients = chatClients == null ? List.of() : chatClients;
        this.skillLoadCallback = skillLoadCallback;
        this.serverToolRegistry = serverToolRegistry;
        this.embeddingService = embeddingService;
        this.memoryExtractor = memoryExtractor;
        this.agentLoopRunner = agentLoopRunner;
        this.codexResponsesLoopRunner = codexResponsesLoopRunner;
        this.contextAssembler = contextAssembler;
        this.artifactExtractor = artifactExtractor;
        this.sessionSummaryRefresher = sessionSummaryRefresher;
        this.deviceToolDispatcher = deviceToolDispatcher;
    }

    public void handle(UUID userId, ChatRequest req, SseEmitter emitter) {
        ChatCancellationToken cancellation = new ChatCancellationToken();
        RunKey runKey = runKey(userId, req.clientRunId());
        if (runKey != null) {
            activeRuns.put(runKey, cancellation);
        }
        chatExecutor.execute(() -> {
            try {
                handleInternal(userId, req, emitter, cancellation);
            } finally {
                if (runKey != null) {
                    activeRuns.remove(runKey, cancellation);
                }
            }
        });
    }

    public boolean cancelRun(UUID userId, String clientRunId) {
        RunKey key = runKey(userId, clientRunId);
        if (key == null) return false;
        ChatCancellationToken cancellation = activeRuns.get(key);
        return cancellation != null && cancellation.cancel();
    }

    private void handleInternal(UUID userId, ChatRequest req, SseEmitter emitter,
                                ChatCancellationToken cancellation) {
        try {
            UUID sessionId = ensureSession(userId, req.sessionId(), req.message());
            ChatAttachmentContext attachments = resolveAttachments(userId, req.attachments());
            // Tell the web client what session we're using BEFORE any other event,
            // so it can echo sessionId back on subsequent /api/chat/stream calls
            // and the LLM actually sees prior turns.
            send(emitter, SseEvent.session(mapper, sessionId));
            send(emitter, SseEvent.userMessage(mapper, req.message(), attachments.metadata()));
            persist(sessionId, userId, MessageRole.USER, req.message(), userMetadata(attachments));

            if (chatClients.isEmpty()) {
                safeSend(emitter, SseEvent.error(mapper, "模型服务未配置，请配置可用的 LLM provider 后再试。"));
                emitter.complete();
                return;
            }

            handleWithLlm(userId, sessionId, req, attachments, emitter, cancellation);
        } catch (Exception e) {
            log.warn("Chat handling failed for user {}", userId, e);
            safeSend(emitter, SseEvent.error(mapper, userFacingGeneralFailureMessage(e)));
            emitter.completeWithError(e);
        }
    }

    /* -------------------- real LLM path -------------------- */

    private void handleWithLlm(UUID userId, UUID sessionId, ChatRequest req, SseEmitter emitter) {
        handleWithLlm(userId, sessionId, req,
                new ChatAttachmentContext(List.of(), List.of(), mapper.createArrayNode(), ""),
                emitter, new ChatCancellationToken());
    }

    private void handleWithLlm(UUID userId, UUID sessionId, ChatRequest req,
                               ChatAttachmentContext attachments, SseEmitter emitter) {
        handleWithLlm(userId, sessionId, req, attachments, emitter, new ChatCancellationToken());
    }

    private void handleWithLlm(UUID userId, UUID sessionId, ChatRequest req,
                               ChatAttachmentContext attachments, SseEmitter emitter,
                               ChatCancellationToken cancellation) {
        ResolvedTools resolved = toolProvider.getForUser(userId, req.deviceId());
        if (req.deviceId() != null && resolved.definitions().isEmpty()) {
            log.info("Requested device {} has no online tools for user {}", req.deviceId(), userId);
            safeSend(emitter, SseEvent.error(mapper,
                    "Target device is not connected yet, or it has not reported its tool manifest."));
            safeComplete(emitter);
            return;
        }

        // Cache layout — system text is bit-identical across requests so the
        // ephemeral cache breakpoint hits cleanly. Per-request memories ride
        // along on the user message instead so they don't churn the cache.
        ContextAssembler.UserContextSettings userContextSettings = loadUserContextSettings(userId);
        boolean autoMemoryEnabled = userContextSettings.autoMemoryEnabled();
        List<MemoryFactDto> memories = recallMemoriesIfEnabled(userId, req.message(), autoMemoryEnabled);
        ContextBundle context = contextAssembler.assemble(
                userId,
                sessionId,
                req.message(),
                memories,
                userContextSettings);
        List<com.anthropic.models.messages.Tool> executableTools = new java.util.ArrayList<>(resolved.definitions());
        executableTools.addAll(serverToolRegistry.toAnthropicTools());
        executableTools.add(skillLoadCallback.toAnthropicTool());
        List<ToolUnion> tools = PromptAssembler.buildToolUnionList(
                executableTools,
                props.agent().memory());

        List<MessageParam> messages = context.anthropicMessages();
        messages.add(buildAnthropicUserMessage(context.userText(), attachments));

        AtomicBoolean responseStarted = new AtomicBoolean(false);
        ChatEventSink sink = new PersistingChatEventSink(emitter, sessionId, userId, responseStarted);
        long t0 = System.currentTimeMillis();

        ProviderRunContext runContext = new ProviderRunContext();
        runContext.sessionId = sessionId;
        runContext.userId = userId;
        runContext.resolved = resolved;
        runContext.stableSystemText = context.stableSystemText();
        runContext.systemBlocks = context.systemBlocks();
        runContext.tools = tools;
        runContext.thinking = thinkingConfig();
        runContext.messages = messages;
        runContext.historyRows = context.historyRows();
        runContext.userText = context.userText();
        runContext.attachments = attachments;
        runContext.sink = sink;
        runContext.emitter = emitter;
        runContext.cancellation = cancellation;

        ProviderRunOutcome outcome = tryRunProviders(runContext, responseStarted);
        if (outcome.terminalHandled()) {
            return;
        }
        if (outcome.result() == null) {
            handleAllProvidersFailed(userId, sessionId, emitter, outcome.lastError());
            return;
        }

        completeProviderRun(userId, sessionId, req, emitter, autoMemoryEnabled, t0, outcome.result());
    }

    private List<MemoryFactDto> recallMemoriesIfEnabled(UUID userId, String message, boolean autoMemoryEnabled) {
        if (autoMemoryEnabled) {
            return recallMemories(userId, message);
        }
        log.debug("auto memory disabled for user {}; skipping memory recall", userId);
        return List.of();
    }

    private ThinkingConfigEnabled thinkingConfig() {
        int budget = props.agent().memory().thinkingBudgetTokens();
        return budget >= 1024
                ? ThinkingConfigEnabled.builder().budgetTokens(budget).build()
                : null;
    }

    private ProviderRunOutcome tryRunProviders(ProviderRunContext runContext, AtomicBoolean responseStarted) {
        RuntimeException lastErr = null;
        for (ConfiguredProvider provider : chatClients) {
            try {
                return ProviderRunOutcome.result(runProvider(provider, runContext));
            } catch (RuntimeException e) {
                lastErr = e;
                if (responseStarted.get()) {
                    log.warn("[agent] provider '{}' failed after streaming began; not failing over",
                            provider.name(), e);
                    safeSend(runContext.emitter, SseEvent.error(mapper, userFacingProviderFailureMessage(e)));
                    runContext.emitter.complete();
                    return ProviderRunOutcome.terminal(lastErr);
                }
                log.warn("[agent] provider '{}' failed: {} — trying next", provider.name(), e.getMessage());
            }
        }
        return ProviderRunOutcome.failure(lastErr);
    }

    private void handleAllProvidersFailed(UUID userId, UUID sessionId, SseEmitter emitter,
                                          RuntimeException lastErr) {
        log.warn("[agent] all providers failed for user {} session {}", userId, sessionId, lastErr);
        safeSend(emitter, SseEvent.error(mapper, userFacingProviderFailureMessage(lastErr)));
        safeComplete(emitter);
    }

    private void completeProviderRun(UUID userId, UUID sessionId, ChatRequest req, SseEmitter emitter,
                                     boolean autoMemoryEnabled, long startedAtMs, RunResult result) {
        if (result.cancelled()) {
            safeComplete(emitter);
            // Explicit stop or server-side timeout. Plain SSE disconnects are
            // deliberately not treated as cancellation, so the final assistant
            // row can still be persisted and shown after refresh.
            log.info("Chat run cancelled for user {} session {} - skipping persist", userId, sessionId);
            return;
        }

        if (result.exhausted()) {
            String message = result.exhaustionReason() == null || result.exhaustionReason().isBlank()
                    ? "任务还没完成，但本轮工具调用次数已达到上限。请发送“继续”，我会接着做。"
                    : result.exhaustionReason();
            log.info("Chat exhausted tool iterations for user {} session {}: {}", userId, sessionId, message);
            safeSend(emitter, SseEvent.assistantMessage(mapper, message));
            long durMs = System.currentTimeMillis() - startedAtMs;
            persist(sessionId, userId, MessageRole.ASSISTANT, message, assistantMetadata(durMs));
            logUsage(userId, result.usage(), durMs);
            safeComplete(emitter);
            return;
        }

        String fullReply = result.assistantText();
        long durMs = System.currentTimeMillis() - startedAtMs;
        persist(sessionId, userId, MessageRole.ASSISTANT, fullReply, assistantMetadata(durMs));
        logUsage(userId, result.usage(), durMs);
        if (autoMemoryEnabled) {
            try {
                memoryExtractor.extractAsync(userId, sessionId, req.message(), fullReply);
            } catch (Exception ex) {
                log.warn("memoryExtractor.extractAsync failed: {}", ex.getMessage());
            }
        } else {
            log.debug("auto memory disabled for user {}; skipping memory extraction", userId);
        }
        try {
            sessionSummaryRefresher.refreshAsync(userId, sessionId);
        } catch (Exception ex) {
            log.warn("sessionSummaryRefresher.refreshAsync failed: {}", ex.getMessage());
        }
        safeComplete(emitter);
    }

    private RunResult runProvider(ConfiguredProvider provider, ProviderRunContext runContext) {
        if (provider.isAnthropicMessages()) {
            return agentLoopRunner.run(provider, runContext.sessionId, runContext.userId, runContext.resolved,
                    runContext.systemBlocks, runContext.tools, runContext.thinking, runContext.messages,
                    runContext.sink, runContext.emitter, runContext.cancellation);
        }
        if (provider.isCodexResponses()) {
            return codexResponsesLoopRunner.run(provider, runContext.sessionId, runContext.userId,
                    runContext.resolved, runContext.stableSystemText, runContext.historyRows,
                    runContext.userText, runContext.attachments, runContext.sink, runContext.emitter,
                    runContext.cancellation);
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

    /* -------------------- per-request loaders -------------------- */

    private ContextAssembler.UserContextSettings loadUserContextSettings(UUID userId) {
        try {
            ContextAssembler.UserContextSettings settings = contextAssembler.loadUserContextSettings(userId);
            return settings == null ? ContextAssembler.UserContextSettings.defaults() : settings;
        } catch (Exception e) {
            log.debug("loadUserContextSettings failed for user {}: {}", userId, e.getMessage());
            return ContextAssembler.UserContextSettings.defaults();
        }
    }

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

    private ChatAttachmentContext resolveAttachments(UUID userId, List<ChatImageAttachment> attachments) {
        List<ChatImageAttachment> clean = normalizeAttachments(attachments);
        ArrayNode metadata = mapper.createArrayNode();
        List<PendingImage> images = new ArrayList<>();
        for (int i = 0; i < clean.size(); i++) {
            ChatImageAttachment att = clean.get(i);
            ObjectNode item = mapper.createObjectNode();
            item.put("type", "image");
            item.put("imageUrl", att.imageUrl());
            item.put("assetId", textOrNull(att.assetId()));
            item.put("contentType", textOrNull(att.contentType()));
            if (att.bytes() != null) item.put("bytes", att.bytes());
            item.put("name", textOrNull(att.name()));
            if (att.width() != null) item.put("width", att.width());
            if (att.height() != null) item.put("height", att.height());
            item.put("source", textOrNull(att.source()));
            item.put("mediaRef", textOrNull(att.mediaRef()));
            item.put("mediaType", textOrNull(att.mediaType()));
            item.put("mediaId", textOrNull(att.mediaId()));
            item.put("sourceTool", textOrNull(att.sourceTool()));
            item.put("bucketName", textOrNull(att.bucketName()));
            if (att.dateTakenMs() != null) item.put("dateTakenMs", att.dateTakenMs());
            item.put("index", i + 1);
            metadata.add(item);

            deviceToolDispatcher.fetchUploadAsset(userId, att.imageUrl())
                    .filter(asset -> asset.bytes().length < 5_250_000)
                    .ifPresent(asset -> {
                        String contentType = isSupportedImageType(asset.contentType())
                                ? asset.contentType()
                                : defaultContentType(att.contentType());
                        images.add(new PendingImage(
                                contentType,
                                Base64.getEncoder().encodeToString(asset.bytes())));
                    });
        }
        return new ChatAttachmentContext(clean, images, metadata, attachmentPromptText(clean, images.size()));
    }

    private List<ChatImageAttachment> normalizeAttachments(List<ChatImageAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<ChatImageAttachment> out = new ArrayList<>();
        for (ChatImageAttachment att : attachments) {
            ChatImageAttachment normalized = normalizeAttachment(att);
            if (normalized != null) {
                out.add(normalized);
            }
            if (out.size() >= 4) {
                return List.copyOf(out);
            }
        }
        return List.copyOf(out);
    }

    private ChatImageAttachment normalizeAttachment(ChatImageAttachment att) {
        if (att == null || att.imageUrl() == null || !att.imageUrl().startsWith("/api/uploads/photos/")) {
            return null;
        }
        return new ChatImageAttachment(
                att.imageUrl().trim(),
                trimToNull(att.assetId()),
                defaultContentType(att.contentType()),
                att.bytes(),
                trimToNull(att.name()),
                att.width(),
                att.height(),
                trimToNull(att.source()),
                trimToNull(att.mediaRef()),
                trimToNull(att.mediaType()),
                trimToNull(att.mediaId()),
                trimToNull(att.sourceTool()),
                trimToNull(att.bucketName()),
                att.dateTakenMs());
    }

    private MessageParam buildAnthropicUserMessage(String userText, ChatAttachmentContext attachments) {
        String text = userTextWithAttachments(userText, attachments);
        if (attachments == null || attachments.images().isEmpty()) {
            return MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(text)
                    .build();
        }
        List<ContentBlockParam> blocks = new ArrayList<>();
        blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(text).build()));
        for (PendingImage img : attachments.images()) {
            blocks.add(ContentBlockParam.ofImage(
                    ImageBlockParam.builder()
                            .source(Base64ImageSource.builder()
                                    .mediaType(Base64ImageSource.MediaType.of(img.mimeType()))
                                    .data(img.b64())
                                    .build())
                            .build()));
        }
        return MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(blocks)
                .build();
    }

    private String userTextWithAttachments(String userText, ChatAttachmentContext attachments) {
        if (attachments == null || attachments.promptText().isBlank()) {
            return userText == null ? "" : userText;
        }
        String base = userText == null ? "" : userText;
        if (base.isBlank()) {
            return attachments.promptText();
        }
        return base + "\n\n" + attachments.promptText();
    }

    private String attachmentPromptText(List<ChatImageAttachment> attachments, int visibleCount) {
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# USER ATTACHED IMAGES\n");
        sb.append("The current user turn includes ").append(attachments.size()).append(" image attachment(s). ");
        sb.append("Inspect the attached image pixels directly when answering. ");
        sb.append("Each image also has an authenticated platform URL that can be passed to device tools such as photos.save_to_gallery.\n");
        if (visibleCount < attachments.size()) {
            sb.append("Only ").append(visibleCount).append(" image(s) were attached to the vision model because of size/fetch limits; use the URLs as references if needed.\n");
        }
        for (int i = 0; i < attachments.size(); i++) {
            ChatImageAttachment att = attachments.get(i);
            sb.append(i + 1).append(". image_url=").append(att.imageUrl());
            appendAttachmentField(sb, "asset_id", att.assetId());
            appendAttachmentField(sb, "content_type", att.contentType());
            appendAttachmentField(sb, "name", att.name());
            appendAttachmentField(sb, "source", att.source());
            appendAttachmentField(sb, "media_ref", att.mediaRef());
            appendAttachmentField(sb, "media_type", att.mediaType());
            appendAttachmentField(sb, "media_id", att.mediaId());
            appendAttachmentField(sb, "bucket_name", att.bucketName());
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static void appendAttachmentField(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(", ").append(label).append('=').append(value);
        }
    }

    private JsonNode userMetadata(ChatAttachmentContext attachments) {
        if (attachments == null || !attachments.hasAttachments()) {
            return null;
        }
        ObjectNode m = mapper.createObjectNode();
        m.set("attachments", attachments.metadata());
        m.put("visionAttachedCount", attachments.images().size());
        return m;
    }

    private static String textOrNull(String value) {
        String clean = trimToNull(value);
        return clean == null ? null : clean;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.isEmpty() ? null : clean;
    }

    private static String defaultContentType(String value) {
        String clean = trimToNull(value);
        if (!isSupportedImageType(clean)) {
            return "image/jpeg";
        }
        return clean.toLowerCase(Locale.ROOT);
    }

    private static boolean isSupportedImageType(String value) {
        if (value == null) return false;
        String clean = value.trim().toLowerCase(Locale.ROOT);
        return clean.equals("image/jpeg") || clean.equals("image/png") || clean.equals("image/webp");
    }

    private JsonNode assistantMetadata(long durationMs) {
        ObjectNode m = mapper.createObjectNode();
        m.put("durationMs", Math.max(0L, durationMs));
        return m;
    }

    private static final class ProviderRunContext {
        private UUID sessionId;
        private UUID userId;
        private ResolvedTools resolved;
        private String stableSystemText;
        private List<TextBlockParam> systemBlocks;
        private List<ToolUnion> tools;
        private ThinkingConfigEnabled thinking;
        private List<MessageParam> messages;
        private List<MessageDto> historyRows;
        private String userText;
        private ChatAttachmentContext attachments;
        private ChatEventSink sink;
        private SseEmitter emitter;
        private ChatCancellationToken cancellation;
    }

    private record ProviderRunOutcome(RunResult result,
                                      RuntimeException lastError,
                                      boolean terminalHandled) {
        private static ProviderRunOutcome result(RunResult result) {
            return new ProviderRunOutcome(result, null, false);
        }

        private static ProviderRunOutcome failure(RuntimeException lastError) {
            return new ProviderRunOutcome(null, lastError, false);
        }

        private static ProviderRunOutcome terminal(RuntimeException lastError) {
            return new ProviderRunOutcome(null, lastError, true);
        }
    }

    private class PersistingChatEventSink implements ChatEventSink {
        private final SseEmitter emitter;
        private final UUID sessionId;
        private final UUID userId;
        private final AtomicBoolean responseStarted;
        private final Deque<String> pendingTools = new ArrayDeque<>();

        PersistingChatEventSink(SseEmitter emitter, UUID sessionId, UUID userId,
                                AtomicBoolean responseStarted) {
            this.emitter = emitter;
            this.sessionId = sessionId;
            this.userId = userId;
            this.responseStarted = responseStarted;
        }

        @Override
        public void emit(SseEvent event) {
            if (event != null && (EVENT_ASSISTANT_MESSAGE.equals(event.type())
                    || EVENT_TOOL_CALL_STARTED.equals(event.type())
                    || EVENT_TOOL_CALL_RESULT.equals(event.type()))) {
                responseStarted.set(true);
            }
            emitAndPersistToolEvent(event);
            if (event == null || event.data() == null || event.data().isNull()) return;
            if (EVENT_TOOL_CALL_STARTED.equals(event.type())) {
                pendingTools.addLast(event.data().path("tool").asText("tool"));
            } else if (EVENT_TOOL_CALL_RESULT.equals(event.type()) || EVENT_TOOL_CALL_ERROR.equals(event.type())) {
                String tool = event.data().path("tool").asText(null);
                if (tool == null) {
                    pendingTools.pollLast();
                } else {
                    pendingTools.removeFirstOccurrence(tool);
                }
            } else if (EVENT_ERROR.equals(event.type()) && !pendingTools.isEmpty()) {
                String tool = pendingTools.pollLast();
                String message = event.data().path("message").asText("tool failed");
                SseEvent terminal = SseEvent.toolCallError(mapper, tool, message);
                emitAndPersistToolEvent(terminal);
            }
        }

        private void emitAndPersistToolEvent(SseEvent event) {
            safeSend(emitter, event);
            if (event == null || event.data() == null || event.data().isNull()) return;
            if (EVENT_TOOL_CALL_STARTED.equals(event.type())) {
                persistToolCall(event.data());
            } else if (EVENT_TOOL_CALL_RESULT.equals(event.type())) {
                persistToolResult(event.data());
            }
        }

        private void persistToolCall(JsonNode data) {
            String tool = data.path("tool").asText("tool");
            UUID deviceId = parseUuid(data.path("deviceId").asText(null));
            JsonNode args = data.path("args").isMissingNode() ? mapper.createObjectNode() : data.path("args");
            persist(sessionId, userId, MessageRole.TOOL_CALL, "calling " + tool,
                    toolCallMetadata(deviceId, tool, args));
        }

        private void persistToolResult(JsonNode data) {
            String tool = data.path("tool").asText("tool");
            JsonNode result = data.path(METADATA_RESULT).isMissingNode()
                    ? mapper.createObjectNode() : data.path(METADATA_RESULT);
            MessageDto saved = persist(sessionId, userId, MessageRole.TOOL_RESULT, "tool " + tool + " returned",
                    toolResultMetadata(tool, result));
            persistArtifacts(sessionId, userId, saved == null ? null : saved.id(), tool, result);
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
            m.set(METADATA_RESULT, result == null ? mapper.createObjectNode() : result);
            return m;
        }

        private void persistArtifacts(UUID sessionId, UUID userId, UUID messageId, String tool, JsonNode result) {
            List<UpsertSessionArtifactRequest> artifacts =
                    artifactExtractor.extract(sessionId, userId, messageId, tool, result);
            for (UpsertSessionArtifactRequest artifact : artifacts) {
                persistArtifact(artifact, sessionId);
            }
        }

        private void persistArtifact(UpsertSessionArtifactRequest artifact, UUID sessionId) {
            try {
                chatClientFeign.upsertArtifact(artifact);
            } catch (Exception e) {
                log.debug("Failed to persist artifact {} for session {}: {}",
                        artifact.artifactKey(), sessionId, e.getMessage());
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

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE complete failed (client likely disconnected): {}", e.getMessage());
        }
    }

    private static RunKey runKey(UUID userId, String clientRunId) {
        if (userId == null || clientRunId == null || clientRunId.isBlank()) return null;
        return new RunKey(userId, clientRunId.trim());
    }

    private record RunKey(UUID userId, String clientRunId) {}
}
