package com.agentplatform.agent.chat;

import com.agentplatform.agent.ai.ChatEventSink;
import com.agentplatform.agent.ai.ConfiguredProvider;
import com.agentplatform.agent.ai.ExecutionResult;
import com.agentplatform.agent.ai.PendingImage;
import com.agentplatform.agent.ai.RemoteToolCallback;
import com.agentplatform.agent.ai.ResolvedTools;
import com.agentplatform.agent.ai.ServerToolCallback;
import com.agentplatform.agent.ai.ServerToolRegistry;
import com.agentplatform.agent.ai.SkillLoadCallback;
import com.agentplatform.agent.ai.ToolInputParser;
import com.agentplatform.agent.config.AgentProperties;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives one full agentic-loop session against a single Anthropic provider.
 * Owns: the SDK streaming call, accumulator-based message reassembly,
 * stop_reason branch (TOOL_USE → execute → next turn; otherwise return),
 * native multimodal {@code tool_result} packing (text + image blocks), and
 * the emitter-cancel → SDK-stream-close binding.
 *
 * <p>Throws {@code RuntimeException} on setup failure so {@code ChatService}
 * can fail over to the next {@link ConfiguredProvider}; once the stream is
 * flowing mid-turn errors propagate to the SSE emitter and re-throw (no
 * mid-stream failover — see {@code ChatService} future provider-retry work).
 */
@Service
public class AgentLoopRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopRunner.class);

    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final SkillLoadCallback skillLoadCallback;
    private final ServerToolRegistry serverToolRegistry;

    public AgentLoopRunner(ObjectMapper mapper,
                           AgentProperties props,
                           SkillLoadCallback skillLoadCallback,
                           ServerToolRegistry serverToolRegistry) {
        this.mapper = mapper;
        this.props = props;
        this.skillLoadCallback = skillLoadCallback;
        this.serverToolRegistry = serverToolRegistry;
    }

    /**
     * Run the loop. Mutates {@code messages} (appending assistant + tool_result
     * turns) in place; collects the streamed assistant text + last-seen
     * {@link Usage} + cancellation flag into the returned {@link RunResult}.
     *
     * <p>Returns {@code cancelled=true} for explicit stop requests or
     * server-side timeout. Plain SSE disconnects are allowed to finish so the
     * final assistant row can be persisted for session history.
     */
    public RunResult run(RunRequest request) {
        RunRequest runRequest = request == null ? new RunRequest() : request;
        ConfiguredProvider provider = runRequest.provider;
        if (!provider.isAnthropicMessages() || provider.client() == null) {
            throw new IllegalArgumentException("AgentLoopRunner requires anthropic-messages provider");
        }

        AnthropicRunState state = initialRunState(runRequest, provider);
        state.cancellation.setCancelAction(() -> closeCurrentStream(state));
        state.emitter.onTimeout(state.cancellation::cancel);

        int maxIterations = props.agent().maxAgentIterations();
        for (int iter = 0; iter < maxIterations; iter++) {
            RunResult terminal = runIteration(state);
            if (terminal != null) {
                return terminal;
            }
        }
        return exhaustedRunResult(state, maxIterations);
    }

    private AnthropicRunState initialRunState(RunRequest request, ConfiguredProvider provider) {
        AnthropicRunState state = new AnthropicRunState();
        state.provider = provider;
        state.sessionId = request.sessionId;
        state.userId = request.userId;
        state.resolved = request.resolved();
        state.systemBlocks = request.systemBlocks();
        state.tools = request.tools();
        state.thinking = request.thinking;
        state.messages = request.messages();
        state.sink = request.sink();
        state.emitter = request.emitter();
        state.cancellation = request.cancellation();
        state.textBuf = new StringBuilder();
        state.currentStream = new AtomicReference<>();
        state.toolBudget = new ToolCallBudget(
                props.agent().maxToolCallsPerTurn(),
                props.agent().maxConsecutiveUiToolCalls());
        return state;
    }

    private void closeCurrentStream(AnthropicRunState state) {
        StreamResponse<RawMessageStreamEvent> stream = state.currentStream.getAndSet(null);
        if (stream == null) {
            return;
        }
        log.info("chat run cancelled - closing SDK stream for user {}", state.userId);
        try {
            stream.close();
        } catch (Exception ignore) {
            // close-on-already-closed is fine
        }
    }

    private RunResult runIteration(AnthropicRunState state) {
        if (state.cancellation.isCancelled()) {
            return new RunResult(state.textBuf.toString(), state.lastUsage, true);
        }
        state.textBuf.setLength(0);

        Message finalMsg = streamMessage(state);
        if (finalMsg == null) {
            return new RunResult(state.textBuf.toString(), state.lastUsage, true);
        }
        if (finalMsg.usage() != null) {
            state.lastUsage = finalMsg.usage();
        }

        state.messages.add(finalMsg.toParam());
        if (!isToolUseTurn(finalMsg)) {
            return new RunResult(state.textBuf.toString(), state.lastUsage, state.cancellation.isCancelled());
        }
        return executeToolUseTurn(state, finalMsg);
    }

    private Message streamMessage(AnthropicRunState state) {
        MessageAccumulator acc = MessageAccumulator.create();
        try (StreamResponse<RawMessageStreamEvent> stream =
                     state.provider.client().messages().createStreaming(messageCreateParams(state))) {
            state.currentStream.set(stream);
            stream.stream().forEach(event -> handleStreamEvent(event, acc, state));
            return acc.message();
        } catch (RuntimeException e) {
            if (state.cancellation.isCancelled()) {
                return null;
            }
            throw e;
        } finally {
            state.currentStream.set(null);
        }
    }

    private MessageCreateParams messageCreateParams(AnthropicRunState state) {
        MessageCreateParams.Builder pb = MessageCreateParams.builder()
                .model(state.provider.model())
                .maxTokens(props.agent().maxTokens())
                .systemOfTextBlockParams(state.systemBlocks)
                .messages(state.messages)
                .tools(state.tools);
        if (state.thinking != null) {
            pb.thinking(state.thinking);
        }
        return pb.build();
    }

    private void handleStreamEvent(RawMessageStreamEvent event,
                                   MessageAccumulator acc,
                                   AnthropicRunState state) {
        acc.accumulate(event);
        event.contentBlockDelta().ifPresent(d -> d.delta().text().ifPresent(t -> {
            String chunk = t.text();
            if (chunk != null && !chunk.isEmpty()) {
                state.textBuf.append(chunk);
                state.sink.emit(SseEvent.assistantMessage(mapper, chunk));
            }
        }));
    }

    private boolean isToolUseTurn(Message finalMsg) {
        if (finalMsg == null) {
            return false;
        }
        Optional<StopReason> stop = finalMsg.stopReason();
        return stop.isPresent() && StopReason.TOOL_USE.equals(stop.get());
    }

    private RunResult executeToolUseTurn(AnthropicRunState state, Message finalMsg) {
        List<ContentBlockParam> toolResults = new ArrayList<>();
        for (ContentBlock cb : finalMsg.content()) {
            Optional<ToolUseBlock> tuOpt = cb.toolUse();
            if (tuOpt.isPresent()) {
                RunResult exhausted = addToolResult(state, toolResults, tuOpt.get());
                if (exhausted != null) {
                    return exhausted;
                }
            }
        }
        if (toolResults.isEmpty()) {
            log.warn("stop_reason=tool_use but no tool_use blocks present — aborting loop");
            return new RunResult(state.textBuf.toString(), state.lastUsage, state.cancellation.isCancelled());
        }
        state.messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(toolResults)
                .build());
        return null;
    }

    private RunResult addToolResult(AnthropicRunState state, List<ContentBlockParam> toolResults, ToolUseBlock tu) {
        ToolCallBudget.Decision budgetDecision = state.toolBudget.before(tu.name());
        if (!budgetDecision.allowed()) {
            log.info("agentic loop paused by tool budget user={} used={}/{} consecutiveUi={}/{} tool={}",
                    state.userId, state.toolBudget.used(), state.toolBudget.maxToolCalls(),
                    state.toolBudget.consecutiveUi(), state.toolBudget.maxConsecutiveUiToolCalls(), tu.name());
            return RunResult.exhausted(state.textBuf.toString(), state.lastUsage,
                    budgetDecision.exhaustionReason());
        }
        ExecutionResult er = executeToolUseSafely(state, tu, budgetDecision);
        toolResults.add(ContentBlockParam.ofToolResult(buildToolResultBlock(tu, er)));
        return null;
    }

    private ExecutionResult executeToolUseSafely(AnthropicRunState state,
                                                ToolUseBlock tu,
                                                ToolCallBudget.Decision budgetDecision) {
        try {
            return executeOneToolUse(tu, state.resolved, state.userId, state.sessionId,
                    budgetDecision.decorate(state.sink));
        } catch (Exception t) {
            log.error("tool_use threw name={} err={}", tu.name(), t.toString(), t);
            return ExecutionResult.error("tool execution failed: " + t.getMessage());
        }
    }

    private RunResult exhaustedRunResult(AnthropicRunState state, int maxIterations) {
        log.warn("agentic loop hit maxAgentIterations={} for user {}", maxIterations, state.userId);
        return RunResult.exhausted(
                state.textBuf.toString(),
                state.lastUsage,
                "任务还没完成，但本轮思考/工具循环已达到上限（" + maxIterations + " 轮，已调用 "
                        + state.toolBudget.used() + "/" + state.toolBudget.maxToolCalls()
                        + " 个工具）。请发送“继续”，我会接着当前页面状态往下做。"
        );
    }

    public static final class RunRequest {
        private ConfiguredProvider provider;
        private UUID sessionId;
        private UUID userId;
        private ResolvedTools resolved;
        private List<TextBlockParam> systemBlocks;
        private List<ToolUnion> tools;
        private ThinkingConfigEnabled thinking;
        private List<MessageParam> messages;
        private ChatEventSink sink;
        private SseEmitter emitter;
        private ChatCancellationToken cancellation;

        public RunRequest withProvider(ConfiguredProvider provider) {
            this.provider = provider;
            return this;
        }

        public RunRequest withSessionId(UUID sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public RunRequest withUserId(UUID userId) {
            this.userId = userId;
            return this;
        }

        public RunRequest withResolved(ResolvedTools resolved) {
            this.resolved = resolved;
            return this;
        }

        public RunRequest withSystemBlocks(List<TextBlockParam> systemBlocks) {
            this.systemBlocks = systemBlocks;
            return this;
        }

        public RunRequest withTools(List<ToolUnion> tools) {
            this.tools = tools;
            return this;
        }

        public RunRequest withThinking(@Nullable ThinkingConfigEnabled thinking) {
            this.thinking = thinking;
            return this;
        }

        public RunRequest withMessages(List<MessageParam> messages) {
            this.messages = messages;
            return this;
        }

        public RunRequest withSink(ChatEventSink sink) {
            this.sink = sink;
            return this;
        }

        public RunRequest withEmitter(SseEmitter emitter) {
            this.emitter = emitter;
            return this;
        }

        public RunRequest withCancellation(ChatCancellationToken cancellation) {
            this.cancellation = cancellation;
            return this;
        }

        private ResolvedTools resolved() {
            return resolved == null ? new ResolvedTools(List.of(), java.util.Map.of()) : resolved;
        }

        private List<TextBlockParam> systemBlocks() {
            return systemBlocks == null ? List.of() : systemBlocks;
        }

        private List<ToolUnion> tools() {
            return tools == null ? List.of() : tools;
        }

        private List<MessageParam> messages() {
            return messages == null ? new ArrayList<>() : messages;
        }

        ChatEventSink sink() {
            return sink == null ? event -> { } : sink;
        }

        SseEmitter emitter() {
            return emitter == null ? new SseEmitter() : emitter;
        }

        ChatCancellationToken cancellation() {
            return cancellation == null ? new ChatCancellationToken() : cancellation;
        }
    }

    private static final class AnthropicRunState {
        private ConfiguredProvider provider;
        private UUID sessionId;
        private UUID userId;
        private ResolvedTools resolved;
        private List<TextBlockParam> systemBlocks;
        private List<ToolUnion> tools;
        private ThinkingConfigEnabled thinking;
        private List<MessageParam> messages;
        private ChatEventSink sink;
        private SseEmitter emitter;
        private ChatCancellationToken cancellation;
        private StringBuilder textBuf;
        private Usage lastUsage;
        private AtomicReference<StreamResponse<RawMessageStreamEvent>> currentStream;
        private ToolCallBudget toolBudget;
    }

    /**
     * Log the Anthropic prompt-cache stats for this turn. Format chosen so a
     * docker-logs grep on "promptCache user=" picks up exactly one line per
     * request. {@code cache_read > 0} ⇒ ephemeral cache hit; {@code cache_create}
     * is non-zero only on first request (or after the 5-min TTL elapses).
     */
    public static void logCacheUsage(UUID userId, Usage usage, long durMs) {
        if (usage == null) {
            log.info("promptCache user={} usage=unavailable dur={}ms", userId, durMs);
            return;
        }
        long create = usage.cacheCreationInputTokens().orElse(0L);
        long read = usage.cacheReadInputTokens().orElse(0L);
        long input = usage.inputTokens();
        long output = usage.outputTokens();
        log.info("promptCache user={} cache_create={} cache_read={} input={} output={} dur={}ms",
                userId, create, read, input, output, durMs);
    }

    private ExecutionResult executeOneToolUse(ToolUseBlock tu, ResolvedTools resolved,
                                              UUID userId, UUID sessionId, ChatEventSink sink) {
        String name = tu.name();
        if (SkillLoadCallback.TOOL_NAME.equals(name)) {
            return skillLoadCallback.executeToolUse(tu, userId);
        }
        ServerToolCallback serverTool = serverToolRegistry.dispatchMap().get(name);
        if (serverTool != null) {
            JsonNode args = parseServerToolArgs(tu);
            if (sink != null) {
                sink.emit(SseEvent.toolCallStarted(mapper, null, name, args));
            }
            ExecutionResult result;
            try {
                result = serverTool.executeJsonToolUse(args, userId, sessionId, sink);
            } catch (Exception e) {
                if (sink != null) {
                    sink.emit(SseEvent.toolCallError(mapper, name, e.getMessage()));
                    sink.emit(SseEvent.error(mapper, "Tool '" + name + "' failed: " + e.getMessage()));
                }
                return ExecutionResult.error(e.getMessage());
            }
            if (sink != null) {
                sink.emit(SseEvent.toolCallResult(mapper, name, parseExecutionResult(result)));
            }
            return result;
        }
        RemoteToolCallback rt = resolved.dispatch().get(name);
        if (rt == null) {
            return ExecutionResult.error("unknown tool: " + name);
        }
        return rt.executeToolUse(tu, userId, sessionId, sink);
    }

    private JsonNode parseServerToolArgs(ToolUseBlock tu) {
        return ToolInputParser.parse(tu._input(), mapper, log, tu.name());
    }

    private JsonNode parseExecutionResult(ExecutionResult result) {
        if (result == null || result.jsonText() == null || result.jsonText().isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(result.jsonText());
        } catch (Exception ignored) {
            return mapper.createObjectNode().put("text", result.jsonText());
        }
    }

    /**
     * Build one tool_result block for the next user turn. Text + 0..N image
     * sub-blocks (Anthropic native multimodal tool_result, no more vision hack).
     */
    private static ToolResultBlockParam buildToolResultBlock(ToolUseBlock tu, ExecutionResult er) {
        List<ToolResultBlockParam.Content.Block> blocks = new ArrayList<>();
        blocks.add(ToolResultBlockParam.Content.Block.ofText(
                TextBlockParam.builder().text(er.jsonText()).build()));
        for (PendingImage img : er.images()) {
            blocks.add(ToolResultBlockParam.Content.Block.ofImage(
                    ImageBlockParam.builder()
                            .source(Base64ImageSource.builder()
                                    .mediaType(Base64ImageSource.MediaType.of(img.mimeType()))
                                    .data(img.b64())
                                    .build())
                            .build()));
        }
        return ToolResultBlockParam.builder()
                .toolUseId(tu.id())
                .content(ToolResultBlockParam.Content.ofBlocks(blocks))
                .build();
    }

}
