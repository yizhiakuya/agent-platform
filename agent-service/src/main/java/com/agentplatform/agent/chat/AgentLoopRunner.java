package com.agentplatform.agent.chat;

import com.agentplatform.agent.ai.ChatEventSink;
import com.agentplatform.agent.ai.ConfiguredProvider;
import com.agentplatform.agent.ai.ExecutionResult;
import com.agentplatform.agent.ai.PendingImage;
import com.agentplatform.agent.ai.RemoteToolCallback;
import com.agentplatform.agent.ai.ResolvedTools;
import com.agentplatform.agent.ai.SkillLoadCallback;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
 * mid-stream failover — see {@code ChatService} TODO P2).
 */
@Service
public class AgentLoopRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopRunner.class);

    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final SkillLoadCallback skillLoadCallback;

    public AgentLoopRunner(ObjectMapper mapper,
                           AgentProperties props,
                           SkillLoadCallback skillLoadCallback) {
        this.mapper = mapper;
        this.props = props;
        this.skillLoadCallback = skillLoadCallback;
    }

    /**
     * Run the loop. Mutates {@code messages} (appending assistant + tool_result
     * turns) in place; collects the streamed assistant text + last-seen
     * {@link Usage} + cancellation flag into the returned {@link RunResult}.
     *
     * <p>Returns {@code cancelled=true} when the SSE emitter ended mid-stream
     * (esc / tab close / async timeout). The caller must NOT persist the
     * partial assistant text in that case — replaying truncated text on the
     * next turn pollutes LLM context.
     */
    public RunResult run(ConfiguredProvider provider,
                         UUID sessionId,
                         UUID userId,
                         ResolvedTools resolved,
                         List<TextBlockParam> systemBlocks,
                         List<ToolUnion> tools,
                         @Nullable ThinkingConfigEnabled thinking,
                         List<MessageParam> messages,
                         ChatEventSink sink,
                         SseEmitter emitter) {
        if (!provider.isAnthropicMessages() || provider.client() == null) {
            throw new IllegalArgumentException("AgentLoopRunner requires anthropic-messages provider");
        }
        // Hold the active stream so emitter cancellation can close it from
        // another thread — SDK forEach is blocking on the worker thread, the
        // close() interrupts the iterator and forEach unwinds with an IO error
        // which we catch and treat as a clean cancel.
        AtomicReference<StreamResponse<RawMessageStreamEvent>> currentStream = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Runnable cancel = () -> {
            cancelled.set(true);
            StreamResponse<RawMessageStreamEvent> s = currentStream.getAndSet(null);
            if (s != null) {
                log.info("emitter ended — closing SDK stream for user {}", userId);
                try {
                    s.close();
                } catch (Exception ignore) {
                    // close-on-already-closed is fine
                }
            }
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(t -> cancel.run());

        StringBuilder textBuf = new StringBuilder();
        Usage lastUsage = null;

        int maxIterations = props.agent().maxAgentIterations();
        for (int iter = 0; iter < maxIterations; iter++) {
            // If a previous iteration drained without erroring but cancel
            // already fired, bail before opening another (billed) stream.
            if (cancelled.get()) {
                return new RunResult(textBuf.toString(), lastUsage, true);
            }

            MessageCreateParams.Builder pb = MessageCreateParams.builder()
                    .model(provider.model())
                    .maxTokens(props.agent().maxTokens())
                    .systemOfTextBlockParams(systemBlocks)
                    .messages(messages)
                    .tools(tools);
            if (thinking != null) {
                pb.thinking(thinking);
            }
            MessageCreateParams params = pb.build();

            Message finalMsg;
            MessageAccumulator acc = MessageAccumulator.create();
            try (StreamResponse<RawMessageStreamEvent> stream =
                         provider.client().messages().createStreaming(params)) {
                currentStream.set(stream);
                stream.stream().forEach(event -> {
                    acc.accumulate(event);
                    event.contentBlockDelta().ifPresent(d -> {
                        d.delta().text().ifPresent(t -> {
                            String chunk = t.text();
                            if (chunk != null && !chunk.isEmpty()) {
                                textBuf.append(chunk);
                                safeSend(emitter, SseEvent.assistantMessage(mapper, chunk));
                            }
                        });
                        // input_json_delta: SDK accumulator handles tool_use args.
                        // thinking delta: hidden from the web client by design.
                    });
                });
                finalMsg = acc.message();
            } catch (RuntimeException e) {
                if (cancelled.get()) {
                    // Emitter cancelled the stream — normal interruption,
                    // not a setup/transport failure. Don't propagate (would
                    // trigger provider failover for nothing) and don't let
                    // the partial text get persisted downstream.
                    return new RunResult(textBuf.toString(), lastUsage, true);
                }
                throw e;
            } finally {
                currentStream.set(null);
            }
            if (finalMsg.usage() != null) {
                lastUsage = finalMsg.usage();
            }

            // Append assistant turn — Message.toParam() converts the SDK Message
            // (List<ContentBlock>) into the MessageParam shape ready for replay.
            messages.add(finalMsg.toParam());

            Optional<StopReason> stop = finalMsg.stopReason();
            // StopReason is an SDK value object, not an enum — must use equals(),
            // reference compare with `!=` was never matching and aborted the
            // agent loop on every tool_use turn, so device tools never fired.
            if (stop.isEmpty() || !StopReason.TOOL_USE.equals(stop.get())) {
                // Clean finish (end_turn / max_tokens / stop_sequence / refusal).
                // If cancel fired AFTER the stream drained naturally, the text
                // is still complete — flag it cancelled so caller skips persist
                // anyway (user may have walked away; saving the reply they
                // never read pollutes future history with stale Q/A).
                return new RunResult(textBuf.toString(), lastUsage, cancelled.get());
            }

            // Run every tool_use block produced by the assistant; pack results
            // into one user-role message of tool_result blocks. Server-side
            // tools (web_search) won't appear here — SDK already resolves them
            // server-side and surfaces only the results in finalMsg.content().
            List<ContentBlockParam> toolResults = new ArrayList<>();
            for (ContentBlock cb : finalMsg.content()) {
                Optional<ToolUseBlock> tuOpt = cb.toolUse();
                if (tuOpt.isEmpty()) continue;
                ToolUseBlock tu = tuOpt.get();
                ExecutionResult er;
                try {
                    er = executeOneToolUse(tu, resolved, userId, sessionId, sink);
                } catch (Throwable t) {
                    log.error("tool_use threw name={} err={}", tu.name(), t.toString(), t);
                    er = ExecutionResult.error("tool execution failed: " + t.getMessage());
                }
                toolResults.add(ContentBlockParam.ofToolResult(buildToolResultBlock(tu, er)));
            }
            if (toolResults.isEmpty()) {
                // Assistant said tool_use but emitted no tool_use blocks (rare).
                // Bail rather than loop forever on identical request.
                log.warn("stop_reason=tool_use but no tool_use blocks present — aborting loop");
                return new RunResult(textBuf.toString(), lastUsage, cancelled.get());
            }
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(toolResults)
                    .build());
        }
        log.warn("agentic loop hit maxAgentIterations={} for user {}", maxIterations, userId);
        return RunResult.exhausted(
                textBuf.toString(),
                lastUsage,
                "任务还没完成，但本轮工具调用次数已达到上限（" + maxIterations + " 轮）。请继续发送“继续”，我会接着当前页面状态往下做。"
        );
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
            return skillLoadCallback.executeToolUse(tu, userId, sessionId, sink);
        }
        RemoteToolCallback rt = resolved.dispatch().get(name);
        if (rt == null) {
            return ExecutionResult.error("unknown tool: " + name);
        }
        return rt.executeToolUse(tu, userId, sessionId, sink);
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

    private void safeSend(SseEmitter emitter, SseEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event.data()));
        } catch (Exception e) {
            // emitter likely closed by client cancel — swallow
        }
    }
}
