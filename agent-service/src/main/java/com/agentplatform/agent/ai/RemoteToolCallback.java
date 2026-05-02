package com.agentplatform.agent.ai;

import com.agentplatform.agent.chat.SseEvent;
import com.agentplatform.agent.client.DeviceToolDispatcher;
import com.agentplatform.protocol.ToolResult;
import com.agentplatform.protocol.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Spring AI {@link ToolCallback} that maps a single device tool to a
 * {@link DeviceToolDispatcher} HTTP call against device-hub.
 *
 * <p>Closure-captures the target {@code deviceId} / {@code userId} / spec at
 * construction time. {@link RemoteDeviceToolCallbackProvider} re-creates these
 * on every chat request (so the LLM sees only the *currently* online tools).
 *
 * <p>Side-emits {@code tool_call_started} / {@code tool_call_result} SSE events
 * via the {@link ChatEventSink} stashed in Spring AI's {@link ToolContext} —
 * the controller path therefore sees all three of LLM text, tool calls, and
 * tool results interleaved correctly on the wire.
 *
 * <p>Hook chain (A2): {@link ToolPreInterceptor}s run synchronously before
 * dispatch (may rewrite args or throw {@link ToolBlockedException} to abort).
 * After dispatch a {@link ToolPostEvent} is published asynchronously for
 * audit / metrics consumers.
 */
public class RemoteToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RemoteToolCallback.class);

    private final UUID deviceId;
    private final UUID userId;
    private final ToolSpec spec;
    private final DeviceToolDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final List<ToolPreInterceptor> preInterceptors;
    private final ApplicationEventPublisher events;

    public RemoteToolCallback(UUID deviceId, UUID userId, ToolSpec spec,
                              DeviceToolDispatcher dispatcher, ObjectMapper mapper,
                              List<ToolPreInterceptor> preInterceptors,
                              ApplicationEventPublisher events) {
        this.deviceId = deviceId;
        this.userId = userId;
        this.spec = spec;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
        this.preInterceptors = preInterceptors == null ? List.of() : preInterceptors;
        this.events = events;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        String schemaJson = spec.schema() == null ? "{}" : spec.schema().toString();
        return ToolDefinition.builder()
                // Anthropic restricts tool names to [a-zA-Z0-9_-]{1,128}; our
                // protocol-layer names (e.g. "photos.list_recent") use dots
                // for namespacing, so sanitize for the LLM but keep the
                // canonical name when dispatching to the device.
                .name(sanitizeForLlm(spec.name()))
                .description(spec.description())
                .inputSchema(schemaJson)
                .build();
    }

    /** Replace characters Anthropic's API rejects (only "." in our scheme so far). */
    private static String sanitizeForLlm(String toolName) {
        return toolName.replace('.', '_');
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        ChatEventSink sink = sinkFrom(toolContext);
        JsonNode args = parseArgs(toolInput);

        // PRE: chain — each interceptor may return rewritten args, or throw
        // ToolBlockedException to abort with a structured error.
        try {
            for (ToolPreInterceptor pre : preInterceptors) {
                args = pre.before(userId, deviceId, spec, args, toolContext);
            }
        } catch (ToolBlockedException blocked) {
            if (sink != null) {
                sink.emit(SseEvent.error(mapper, "Tool blocked: " + blocked.getReason()));
            }
            return "{\"error\":{\"code\":-32099,\"message\":"
                    + mapper.valueToTree("blocked: " + blocked.getReason()).toString() + "}}";
        }

        if (sink != null) {
            sink.emit(SseEvent.toolCallStarted(mapper, deviceId, spec.name(), args));
        }

        long t0 = System.currentTimeMillis();
        ToolResult result;
        try {
            result = dispatcher.dispatch(deviceId, userId, spec.name(), args);
        } catch (RuntimeException e) {
            long dur = System.currentTimeMillis() - t0;
            if (events != null) {
                events.publishEvent(new ToolPostEvent(userId, deviceId, spec.name(), args,
                        null, e.getMessage(), dur, Instant.now()));
            }
            throw e;
        }
        long dur = System.currentTimeMillis() - t0;

        // POST: emit event regardless of success/error so audit/metrics
        // listeners observe both outcomes uniformly.
        if (events != null) {
            events.publishEvent(new ToolPostEvent(userId, deviceId, spec.name(), args,
                    result.hasError() ? null : result.value(),
                    result.hasError() ? result.error().message() : null,
                    dur, Instant.now()));
        }

        if (sink != null) {
            if (result.hasError()) {
                sink.emit(SseEvent.error(mapper,
                        "Tool '" + spec.name() + "' failed: " + result.error().message()));
            } else {
                sink.emit(SseEvent.toolCallResult(mapper, spec.name(), result.value()));
            }
        }

        // Spring AI expects the tool's textual return value (will be fed back
        // into the LLM as a tool_result message). Both success and error are
        // surfaced as JSON so the LLM can react to either.
        //
        // Strip *_b64 fields before handing to the LLM — base64 thumbnails
        // would otherwise dump a 50-100KB string into the model context that
        // a non-vision-aware Claude routinely echoes back into its reply
        // markdown. The web client still gets the full payload via the
        // tool_call_result SSE event above.
        return result.hasError()
                ? wireError(result)
                : (result.value() == null ? "null" : stripB64ForLlm(result.value()).toString());
    }

    /** Recursively replace any "*_b64" string field with a short placeholder. */
    private static JsonNode stripB64ForLlm(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            ObjectNode copy = obj.objectNode();
            obj.fields().forEachRemaining(e -> {
                String k = e.getKey();
                if (k.endsWith("_b64") && e.getValue().isTextual()) {
                    int len = e.getValue().asText().length();
                    copy.put(k, "<binary " + len + "B omitted; rendered to user>");
                } else {
                    copy.set(k, stripB64ForLlm(e.getValue()));
                }
            });
            return copy;
        } else if (node instanceof ArrayNode arr) {
            ArrayNode copy = arr.arrayNode();
            arr.forEach(child -> copy.add(stripB64ForLlm(child)));
            return copy;
        }
        return node;
    }

    private JsonNode parseArgs(String toolInput) {
        try {
            return (toolInput == null || toolInput.isBlank())
                    ? mapper.createObjectNode()
                    : mapper.readTree(toolInput);
        } catch (Exception e) {
            log.warn("Failed to parse toolInput JSON for {}: {}", spec.name(), e.getMessage());
            return mapper.createObjectNode();
        }
    }

    private String wireError(ToolResult result) {
        return "{\"error\":{\"code\":" + result.error().code()
                + ",\"message\":" + mapper.valueToTree(result.error().message()).toString() + "}}";
    }

    private static ChatEventSink sinkFrom(ToolContext ctx) {
        if (ctx == null) return null;
        Object o = ctx.getContext().get(ChatEventSink.KEY);
        return (o instanceof ChatEventSink s) ? s : null;
    }
}
