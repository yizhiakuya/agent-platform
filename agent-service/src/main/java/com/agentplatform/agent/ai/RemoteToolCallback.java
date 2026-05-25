package com.agentplatform.agent.ai;

import com.agentplatform.agent.chat.SseEvent;
import com.agentplatform.agent.client.DeviceToolDispatcher;
import com.agentplatform.protocol.ToolResult;
import com.agentplatform.protocol.ToolSpec;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Adapter that maps a single device tool to a {@link DeviceToolDispatcher}
 * HTTP call against device-hub, exposed to the Anthropic Java SDK as a native
 * {@link Tool} definition + {@link #executeToolUse} callback.
 *
 * <p>Closure-captures the target {@code deviceId} / {@code userId} / spec at
 * construction time. {@link RemoteDeviceToolCallbackProvider} re-creates these
 * on every chat request (so the LLM sees only the *currently* online tools).
 *
 * <p>{@link #executeToolUse} is invoked from the agentic loop in
 * {@code ChatService} once per LLM-issued {@link ToolUseBlock}. It runs the
 * pre-interceptor chain, dispatches to device-hub, emits SSE
 * {@code tool_call_started} / {@code tool_call_result} for the web client,
 * publishes a {@link ToolPostEvent} for audit listeners, and returns the
 * {@link ExecutionResult} (text + any vision images) the caller folds back
 * into the next turn's {@code tool_result} message.
 */
public class RemoteToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RemoteToolCallback.class);
    private static final int MAX_UPLOADED_VISION_ATTACHMENTS = 8;
    private static final Set<String> ANTHROPIC_UNSUPPORTED_ROOT_SCHEMA_KEYS = Set.of("oneOf", "anyOf", "allOf");
    private static final String BINARY_PLACEHOLDER_PREFIX = "<binary ";

    private final UUID deviceId;
    private final UUID userId;
    private final ToolSpec spec;
    private final DeviceToolDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final List<ToolPreInterceptor> preInterceptors;
    private final ApplicationEventPublisher events;
    private final boolean visionEnabled;

    public RemoteToolCallback(RemoteToolContext context) {
        this.deviceId = context.deviceId();
        this.userId = context.userId();
        this.spec = context.spec();
        this.dispatcher = context.dispatcher();
        this.mapper = context.mapper();
        this.preInterceptors = context.preInterceptors() == null ? List.of() : context.preInterceptors();
        this.events = context.events();
        this.visionEnabled = context.visionEnabled();
    }

    public RemoteToolCallback(UUID deviceId, UUID userId, ToolSpec spec,
                              DeviceToolDispatcher dispatcher, ObjectMapper mapper,
                              List<ToolPreInterceptor> preInterceptors,
                              ApplicationEventPublisher events) {
        this(new RemoteToolContext()
                .withDeviceId(deviceId)
                .withUserId(userId)
                .withSpec(spec)
                .withDispatcher(dispatcher)
                .withMapper(mapper)
                .withPreInterceptors(preInterceptors)
                .withEvents(events));
    }

    /**
     * Wire-level tool name as the LLM sees it. Anthropic restricts tool names
     * to {@code [a-zA-Z0-9_-]{1,128}}; our protocol-layer names (e.g.
     * {@code photos.list_recent}) use dots for namespacing, so the {@code .}
     * is rewritten to {@code _} for the LLM. Used both as the {@link Tool#name}
     * and the dispatch-map key in {@link ResolvedTools#dispatch}.
     */
    public String name() {
        return sanitizeForLlm(spec.name());
    }

    /**
     * SDK form of this tool. Drop straight into {@code MessageCreateParams.tools}
     * (typically wrapped in a {@code ToolUnion}).
     */
    public Tool toAnthropicTool() {
        return Tool.builder()
                .name(name())
                .description(spec.description())
                .inputSchema(buildInputSchema())
                .build();
    }

    public ToolSpec spec() {
        return spec;
    }

    /**
     * Execute the device tool in response to one LLM-issued
     * {@link ToolUseBlock}:
     *
     * <ol>
     *   <li>Parse {@code tu._input()} into a {@link JsonNode} of args.</li>
     *   <li>Run {@link ToolPreInterceptor}s — may rewrite args or throw
     *       {@link ToolBlockedException} to abort with a structured error.</li>
     *   <li>Emit {@code tool_call_started} SSE.</li>
     *   <li>POST to device-hub via {@link DeviceToolDispatcher}.</li>
     *   <li>Publish {@link ToolPostEvent} for audit/metrics.</li>
     *   <li>Emit {@code tool_call_result} (or {@code error}) SSE.</li>
     *   <li>Strip {@code *_b64} fields from the JSON for the LLM, optionally
     *       collecting them as {@link PendingImage}s for native multimodal
     *       {@code tool_result}.</li>
     * </ol>
     *
     * @param tu        tool_use block from the LLM (carries id / name / args)
     * @param userId    chat user (must match the constructor-bound user)
     * @param sessionId chat session — fed to listeners only
     * @param sink      per-request SSE sink (may be null in tests)
     */
    public ExecutionResult executeToolUse(ToolUseBlock tu, UUID userId, UUID sessionId, ChatEventSink sink) {
        return executeJsonToolUse(parseArgs(tu), userId, sessionId, sink);
    }

    public ExecutionResult executeJsonToolUse(JsonNode args, UUID userId, UUID sessionId, ChatEventSink sink) {
        Map<String, Object> requestCtx = new HashMap<>();
        requestCtx.put("userId", userId);
        if (sessionId != null) requestCtx.put("sessionId", sessionId);

        JsonNode preparedArgs = prepareArgs(args, requestCtx, sink);
        if (preparedArgs == null) {
            return blockedResult(requestCtx);
        }

        if (sink != null) {
            sink.emit(SseEvent.toolCallStarted(mapper, deviceId, spec.name(), preparedArgs));
        }

        long t0 = System.currentTimeMillis();
        DispatchOutcome outcome = dispatch(preparedArgs, t0, sink);
        if (outcome.failure() != null) {
            return outcome.failure();
        }

        // POST: emit event regardless of success/error so audit/metrics
        // listeners observe both outcomes uniformly.
        publishResultEvent(preparedArgs, outcome.result(), outcome.durationMs());
        emitResult(outcome.result(), sink);

        return executionResult(outcome.result());
    }

    private JsonNode prepareArgs(JsonNode args, Map<String, Object> requestCtx, ChatEventSink sink) {
        JsonNode prepared = args == null || args.isNull() ? mapper.createObjectNode() : args;
        try {
            for (ToolPreInterceptor pre : preInterceptors) {
                prepared = pre.before(this.userId, deviceId, spec, prepared, requestCtx);
            }
            return prepared;
        } catch (ToolBlockedException blocked) {
            requestCtx.put("blockedReason", blocked.getReason());
            if (sink != null) {
                sink.emit(SseEvent.error(mapper, "Tool blocked: " + blocked.getReason()));
            }
            return null;
        }
    }

    private ExecutionResult blockedResult(Map<String, Object> requestCtx) {
        String reason = String.valueOf(requestCtx.get("blockedReason"));
        return ExecutionResult.text("{\"error\":{\"code\":-32099,\"message\":"
                + mapper.valueToTree("blocked: " + reason).toString() + "}}");
    }

    private DispatchOutcome dispatch(JsonNode args, long startMs, ChatEventSink sink) {
        try {
            ToolResult result = dispatcher.dispatch(deviceId, this.userId, spec.name(), args);
            return new DispatchOutcome(result, System.currentTimeMillis() - startMs, null);
        } catch (RuntimeException e) {
            long dur = System.currentTimeMillis() - startMs;
            publishFailureEvent(args, e.getMessage(), dur);
            emitFailure(e.getMessage(), sink);
            return new DispatchOutcome(null, dur, ExecutionResult.error(e.getMessage()));
        }
    }

    private void publishFailureEvent(JsonNode args, String message, long durationMs) {
        if (events != null) {
            events.publishEvent(new ToolPostEvent(this.userId, deviceId, spec.name(), args,
                    null, message, durationMs, Instant.now()));
        }
    }

    private void publishResultEvent(JsonNode args, ToolResult result, long durationMs) {
        if (events != null) {
            events.publishEvent(new ToolPostEvent(this.userId, deviceId, spec.name(), args,
                    result.hasError() ? null : result.value(),
                    result.hasError() ? result.error().message() : null,
                    durationMs, Instant.now()));
        }
    }

    private void emitFailure(String message, ChatEventSink sink) {
        if (sink != null) {
            sink.emit(SseEvent.toolCallError(mapper, spec.name(), message));
            sink.emit(SseEvent.error(mapper, "Tool '" + spec.name() + "' failed: " + message));
        }
    }

    private void emitResult(ToolResult result, ChatEventSink sink) {
        if (sink != null) {
            if (result.hasError()) {
                sink.emit(SseEvent.toolCallError(mapper, spec.name(), result.error().message()));
                sink.emit(SseEvent.error(mapper,
                        "Tool '" + spec.name() + "' failed: " + result.error().message()));
            } else {
                sink.emit(SseEvent.toolCallResult(mapper, spec.name(), result.value()));
            }
        }
    }

    private ExecutionResult executionResult(ToolResult result) {
        // Tool result text/JSON for the LLM. Two paths for *_b64 base64 fields:
        //   - vision DISABLED (legacy): replace each *_b64 with a
        //     "<binary NB omitted>" placeholder — keeps a non-vision LLM from
        //     echoing 50-100KB strings into its reply.
        //   - vision ENABLED: same placeholder in the JSON BUT we also collect
        //     the raw bytes into PendingImage records so ChatService can fold
        //     them into the SDK ToolResultBlockParam content as native
        //     ImageBlockParams. Either way the web client still gets the full
        //     payload via the tool_call_result SSE event above.
        if (result.hasError()) {
            return ExecutionResult.text(wireError(result));
        }
        JsonNode raw = result.value();
        if (raw == null) {
            return ExecutionResult.text("null");
        }
        if (visionEnabled) {
            List<PendingImage> imgs = new ArrayList<>();
            JsonNode stripped = stripB64ForLlmAndCollect(raw, "", imgs);
            collectUploadedImages(stripped, imgs);
            if (!imgs.isEmpty() && stripped instanceof ObjectNode obj) {
                obj.put("_vision_attached_count", imgs.size());
            }
            return new ExecutionResult(stripped.toString(), imgs);
        }
        return ExecutionResult.text(stripB64ForLlm(raw).toString());
    }

    private void collectUploadedImages(JsonNode node, List<PendingImage> out) {
        if (node == null || out == null) {
            return;
        }
        if (out.size() >= MAX_UPLOADED_VISION_ATTACHMENTS) {
            return;
        }
        if (node.isObject()) {
            String url = firstText(node,
                    "image_url", "asset_url", "url",
                    "imageUrl", "assetUrl");
            if (url != null) {
                dispatcher.fetchUploadAsset(userId, url)
                        .filter(asset -> asset.bytes().length < 5_250_000)
                        .ifPresent(asset -> out.add(new PendingImage(
                                asset.contentType(),
                                Base64.getEncoder().encodeToString(asset.bytes()))));
            }
            node.fields().forEachRemaining(entry -> collectUploadedImages(entry.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(child -> collectUploadedImages(child, out));
        }
    }

    private static String firstText(JsonNode node, String... keys) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    /** Replace characters Anthropic's API rejects (only "." in our scheme so far). */
    private static String sanitizeForLlm(String toolName) {
        return toolName.replace('.', '_');
    }

    /**
     * Convert this tool's JSON Schema (a {@link JsonNode}) into the SDK's
     * {@link Tool.InputSchema}. The schema is always an object schema in our
     * protocol; we forward {@code properties} as-is (verbatim object map) and
     * {@code required} as a list. Most other schema fields (e.g. {@code $defs},
     * {@code additionalProperties: false}) are preserved via
     * {@link Tool.InputSchema.Builder#putAdditionalProperty}.
     */
    private Tool.InputSchema buildInputSchema() {
        return AnthropicToolSchema.inputSchema(spec.schema(), mapper, ANTHROPIC_UNSUPPORTED_ROOT_SCHEMA_KEYS);
    }

    /**
     * Vision-aware variant of {@link #stripB64ForLlm}. Walks the tree just
     * like the legacy path (replacing every {@code *_b64} string with a stub),
     * but also collects each base64 string into {@code out} as a
     * {@link PendingImage}. The MIME type is inferred from the field name and
     * the raw bytes — JPEG and PNG cover all device tools we ship today.
     *
     * @param pathPrefix dotted JSON pointer accumulated so far, used only for
     *                   diagnostic logging.
     */
    protected static JsonNode stripB64ForLlmAndCollect(JsonNode node, String pathPrefix, List<PendingImage> out) {
        if (node instanceof ObjectNode obj) {
            return stripObjectB64ForLlmAndCollect(obj, pathPrefix, out);
        } else if (node instanceof ArrayNode arr) {
            return stripArrayB64ForLlmAndCollect(arr, pathPrefix, out);
        }
        return node;
    }

    private static JsonNode stripObjectB64ForLlmAndCollect(ObjectNode obj, String pathPrefix, List<PendingImage> out) {
        ObjectNode copy = obj.objectNode();
        boolean hasVision = hasVisionB64Sibling(obj);
        obj.fields().forEachRemaining(e -> copy.set(
                e.getKey(),
                strippedObjectField(e.getKey(), e.getValue(), pathPrefix, hasVision, out)));
        return copy;
    }

    private static boolean hasVisionB64Sibling(ObjectNode obj) {
        for (java.util.Iterator<String> it = obj.fieldNames(); it.hasNext(); ) {
            String fieldName = it.next();
            JsonNode value = obj.get(fieldName);
            if (isVisionB64Field(fieldName, value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVisionB64Field(String fieldName, JsonNode value) {
        return fieldName.endsWith("_b64")
                && fieldName.startsWith("vision")
                && value != null
                && value.isTextual()
                && !value.asText().isEmpty();
    }

    private static JsonNode strippedObjectField(String key,
                                                JsonNode value,
                                                String pathPrefix,
                                                boolean hasVisionSibling,
                                                List<PendingImage> out) {
        if (!key.endsWith("_b64") || !value.isTextual()) {
            return stripB64ForLlmAndCollect(value, childPath(pathPrefix, key), out);
        }
        return strippedB64Field(key, value.asText(), hasVisionSibling, out);
    }

    private static JsonNode strippedB64Field(String key,
                                             String b64,
                                             boolean hasVisionSibling,
                                             List<PendingImage> out) {
        int len = b64.length();
        boolean isVision = key.startsWith("vision");
        boolean shouldCollect = len > 0 && len < 7_000_000 && (isVision || !hasVisionSibling);
        ObjectNode holder = JsonNodeFactory.instance.objectNode();
        if (shouldCollect) {
            holder.put(key, binaryPlaceholder(len, "attached as image; rendered to user"));
            out.add(new PendingImage(sniffMime(b64), b64));
            return holder.get(key);
        }
        holder.put(key, binaryPlaceholder(len, "omitted; vision sibling preferred"));
        return holder.get(key);
    }

    private static String childPath(String pathPrefix, String key) {
        return pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
    }

    private static JsonNode stripArrayB64ForLlmAndCollect(ArrayNode arr, String pathPrefix, List<PendingImage> out) {
        ArrayNode copy = arr.arrayNode();
        int i = 0;
        for (JsonNode child : arr) {
            copy.add(stripB64ForLlmAndCollect(child, pathPrefix + "[" + i + "]", out));
            i++;
        }
        return copy;
    }

    /**
     * Best-effort MIME-type guess from the base64 prefix — JPEG starts with "/9j/",
     * PNG with "iVBORw0KGgo". Default to JPEG since Anthropic accepts both
     * and our existing photos.list_recent path emits JPEG.
     */
    private static String sniffMime(String b64) {
        if (b64 == null || b64.length() < 8) return "image/jpeg";
        if (b64.startsWith("iVBORw0KGgo")) return "image/png";
        if (b64.startsWith("R0lGOD")) return "image/gif";
        if (b64.startsWith("UklGR")) return "image/webp";
        return "image/jpeg";
    }

    /** Recursively replace any "*_b64" string field with a short placeholder. */
    protected static JsonNode stripB64ForLlm(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            ObjectNode copy = obj.objectNode();
            obj.fields().forEachRemaining(e -> {
                String k = e.getKey();
                if (k.endsWith("_b64") && e.getValue().isTextual()) {
                    int len = e.getValue().asText().length();
                    copy.put(k, binaryPlaceholder(len, "omitted; rendered to user"));
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

    private static String binaryPlaceholder(int length, String reason) {
        return BINARY_PLACEHOLDER_PREFIX + length + "B " + reason + ">";
    }

    /**
     * Pull args out of the SDK {@link ToolUseBlock}. {@code tu.input()} is a
     * {@link JsonValue} (the SDK's JSON wrapper); we round-trip it through
     * Jackson so the rest of this class — and the tool hook chain — keeps
     * working off plain {@link JsonNode}.
     */
    private JsonNode parseArgs(ToolUseBlock tu) {
        return ToolInputParser.parse(tu._input(), mapper, log, spec.name());
    }

    private String wireError(ToolResult result) {
        return "{\"error\":{\"code\":" + result.error().code()
                + ",\"message\":" + mapper.valueToTree(result.error().message()).toString() + "}}";
    }

    public static final class RemoteToolContext {
        private UUID deviceId;
        private UUID userId;
        private ToolSpec spec;
        private DeviceToolDispatcher dispatcher;
        private ObjectMapper mapper;
        private List<ToolPreInterceptor> preInterceptors;
        private ApplicationEventPublisher events;
        private boolean visionEnabled;

        public RemoteToolContext withDeviceId(UUID deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        public RemoteToolContext withUserId(UUID userId) {
            this.userId = userId;
            return this;
        }

        public RemoteToolContext withSpec(ToolSpec spec) {
            this.spec = spec;
            return this;
        }

        public RemoteToolContext withDispatcher(DeviceToolDispatcher dispatcher) {
            this.dispatcher = dispatcher;
            return this;
        }

        public RemoteToolContext withMapper(ObjectMapper mapper) {
            this.mapper = mapper;
            return this;
        }

        public RemoteToolContext withPreInterceptors(List<ToolPreInterceptor> preInterceptors) {
            this.preInterceptors = preInterceptors;
            return this;
        }

        public RemoteToolContext withEvents(ApplicationEventPublisher events) {
            this.events = events;
            return this;
        }

        public RemoteToolContext withVisionEnabled(boolean visionEnabled) {
            this.visionEnabled = visionEnabled;
            return this;
        }

        private UUID deviceId() {
            return deviceId;
        }

        private UUID userId() {
            return userId;
        }

        private ToolSpec spec() {
            return spec;
        }

        private DeviceToolDispatcher dispatcher() {
            return dispatcher;
        }

        private ObjectMapper mapper() {
            return mapper;
        }

        private List<ToolPreInterceptor> preInterceptors() {
            return preInterceptors;
        }

        private ApplicationEventPublisher events() {
            return events;
        }

        private boolean visionEnabled() {
            return visionEnabled;
        }
    }

    private record DispatchOutcome(ToolResult result, long durationMs, ExecutionResult failure) {}
}
