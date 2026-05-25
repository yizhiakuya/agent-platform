package com.agentplatform.agent.ai;

import com.agentplatform.agent.chat.SseEvent;
import com.agentplatform.agent.client.DeviceToolDispatcher;
import com.agentplatform.protocol.ToolResult;
import com.agentplatform.protocol.ToolSpec;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

    private final UUID deviceId;
    private final UUID userId;
    private final ToolSpec spec;
    private final DeviceToolDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final List<ToolPreInterceptor> preInterceptors;
    private final ApplicationEventPublisher events;
    private final boolean visionEnabled;

    public RemoteToolCallback(UUID deviceId, UUID userId, ToolSpec spec,
                              DeviceToolDispatcher dispatcher, ObjectMapper mapper,
                              List<ToolPreInterceptor> preInterceptors,
                              ApplicationEventPublisher events) {
        this(deviceId, userId, spec, dispatcher, mapper, preInterceptors, events, false);
    }

    public RemoteToolCallback(UUID deviceId, UUID userId, ToolSpec spec,
                              DeviceToolDispatcher dispatcher, ObjectMapper mapper,
                              List<ToolPreInterceptor> preInterceptors,
                              ApplicationEventPublisher events,
                              boolean visionEnabled) {
        this.deviceId = deviceId;
        this.userId = userId;
        this.spec = spec;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
        this.preInterceptors = preInterceptors == null ? List.of() : preInterceptors;
        this.events = events;
        this.visionEnabled = visionEnabled;
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
        if (args == null || args.isNull()) {
            args = mapper.createObjectNode();
        }
        Map<String, Object> requestCtx = new HashMap<>();
        requestCtx.put("userId", userId);
        if (sessionId != null) requestCtx.put("sessionId", sessionId);

        // PRE: chain — each interceptor may return rewritten args, or throw
        // ToolBlockedException to abort with a structured error.
        try {
            for (ToolPreInterceptor pre : preInterceptors) {
                args = pre.before(this.userId, deviceId, spec, args, requestCtx);
            }
        } catch (ToolBlockedException blocked) {
            if (sink != null) {
                sink.emit(SseEvent.error(mapper, "Tool blocked: " + blocked.getReason()));
            }
            return ExecutionResult.text("{\"error\":{\"code\":-32099,\"message\":"
                    + mapper.valueToTree("blocked: " + blocked.getReason()).toString() + "}}");
        }

        if (sink != null) {
            sink.emit(SseEvent.toolCallStarted(mapper, deviceId, spec.name(), args));
        }

        long t0 = System.currentTimeMillis();
        ToolResult result;
        try {
            result = dispatcher.dispatch(deviceId, this.userId, spec.name(), args);
        } catch (RuntimeException e) {
            long dur = System.currentTimeMillis() - t0;
            if (events != null) {
                events.publishEvent(new ToolPostEvent(this.userId, deviceId, spec.name(), args,
                        null, e.getMessage(), dur, Instant.now()));
            }
            if (sink != null) {
                sink.emit(SseEvent.toolCallError(mapper, spec.name(), e.getMessage()));
                sink.emit(SseEvent.error(mapper,
                        "Tool '" + spec.name() + "' failed: " + e.getMessage()));
            }
            return ExecutionResult.error(e.getMessage());
        }
        long dur = System.currentTimeMillis() - t0;

        // POST: emit event regardless of success/error so audit/metrics
        // listeners observe both outcomes uniformly.
        if (events != null) {
            events.publishEvent(new ToolPostEvent(this.userId, deviceId, spec.name(), args,
                    result.hasError() ? null : result.value(),
                    result.hasError() ? result.error().message() : null,
                    dur, Instant.now()));
        }

        if (sink != null) {
            if (result.hasError()) {
                sink.emit(SseEvent.toolCallError(mapper, spec.name(), result.error().message()));
                sink.emit(SseEvent.error(mapper,
                        "Tool '" + spec.name() + "' failed: " + result.error().message()));
            } else {
                sink.emit(SseEvent.toolCallResult(mapper, spec.name(), result.value()));
            }
        }

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
        // Anthropic requires input_schema.type = "object". The SDK's
        // properties() field is typed Tool.InputSchema.Properties — passing a
        // raw JsonValue compiles via the JsonField<Properties> overload but
        // serializes as an empty/missing field, so the LLM sees the tool with
        // no params and never calls it.
        Tool.InputSchema.Builder b = Tool.InputSchema.builder()
                .type(JsonValue.from("object"));
        JsonNode schema = spec.schema();
        if (schema == null || schema.isNull() || !schema.isObject()) {
            return b.build();
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
            properties.fields().forEachRemaining(entry -> {
                Object val = mapper.convertValue(entry.getValue(), Object.class);
                pb.putAdditionalProperty(entry.getKey(), JsonValue.from(val));
            });
            b.properties(pb.build());
        }
        JsonNode requiredNode = schema.get("required");
        if (requiredNode != null && requiredNode.isArray()) {
            List<String> required = new ArrayList<>();
            requiredNode.forEach(n -> required.add(n.asText()));
            if (!required.isEmpty()) {
                b.required(required);
            }
        }
        schema.fields().forEachRemaining(e -> {
            String k = e.getKey();
            if (k.equals("type") || k.equals("properties") || k.equals("required")) return;
            if (ANTHROPIC_UNSUPPORTED_ROOT_SCHEMA_KEYS.contains(k)) return;
            Object val = mapper.convertValue(e.getValue(), Object.class);
            b.putAdditionalProperty(k, JsonValue.from(val));
        });
        return b.build();
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
            ObjectNode copy = obj.objectNode();
            // Layered b64 priority within ONE object: when a tool returns BOTH
            // `vision_b64` (for the LLM) and `image_b64` (for the web bubble),
            // collect only the vision copy. The other b64 fields are still
            // placeholdered so raw bytes never leak into text context.
            boolean hasVisionSibling = false;
            for (java.util.Iterator<String> it = obj.fieldNames(); it.hasNext(); ) {
                String fn = it.next();
                JsonNode fv = obj.get(fn);
                if (fn.endsWith("_b64") && fv != null && fv.isTextual()
                        && fn.startsWith("vision") && !fv.asText().isEmpty()) {
                    hasVisionSibling = true;
                    break;
                }
            }
            final boolean hasVision = hasVisionSibling;
            obj.fields().forEachRemaining(e -> {
                String k = e.getKey();
                String childPath = pathPrefix.isEmpty() ? k : pathPrefix + "." + k;
                JsonNode v = e.getValue();
                if (k.endsWith("_b64") && v.isTextual()) {
                    String b64 = v.asText();
                    int len = b64.length();
                    boolean isVision = k.startsWith("vision");
                    boolean shouldCollect = len > 0 && len < 7_000_000   // Anthropic per-image cap ~5MB binary
                            && (isVision || !hasVision);                  // skip duplicate UI image when vision sibling present
                    if (shouldCollect) {
                        copy.put(k, "<binary " + len + "B attached as image; rendered to user>");
                        out.add(new PendingImage(sniffMime(k, b64), b64));
                    } else {
                        copy.put(k, "<binary " + len + "B omitted; vision sibling preferred>");
                    }
                } else {
                    copy.set(k, stripB64ForLlmAndCollect(v, childPath, out));
                }
            });
            return copy;
        } else if (node instanceof ArrayNode arr) {
            ArrayNode copy = arr.arrayNode();
            int i = 0;
            for (JsonNode child : arr) {
                copy.add(stripB64ForLlmAndCollect(child, pathPrefix + "[" + i + "]", out));
                i++;
            }
            return copy;
        }
        return node;
    }

    /**
     * Best-effort MIME-type guess. Field names like {@code thumb_b64} /
     * {@code image_b64} come from Android device tools that always emit JPEG;
     * for safety we also peek at the base64 prefix — JPEG starts with "/9j/",
     * PNG with "iVBORw0KGgo". Default to JPEG since Anthropic accepts both
     * and our existing photos.list_recent path emits JPEG.
     */
    private static String sniffMime(String fieldName, String b64) {
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

    /**
     * Pull args out of the SDK {@link ToolUseBlock}. {@code tu.input()} is a
     * {@link JsonValue} (the SDK's JSON wrapper); we round-trip it through
     * Jackson so the rest of this class — and the tool hook chain — keeps
     * working off plain {@link JsonNode}.
     */
    private JsonNode parseArgs(ToolUseBlock tu) {
        try {
            Object raw = tu._input();
            if (raw == null) return mapper.createObjectNode();
            // SDK JsonValue serializes cleanly via the standard Jackson
            // configuration the SDK ships; convertValue handles both cases
            // where input() returns a JsonValue or already a Map.
            JsonNode node = mapper.valueToTree(raw);
            // valueToTree on a JsonValue that wraps a map produces an object
            // node directly. If the SDK ever returns a string-encoded JSON,
            // try to re-parse.
            if (node != null && node.isTextual()) {
                try {
                    return mapper.readTree(node.asText());
                } catch (Exception ignored) {
                    return node;
                }
            }
            return node == null ? mapper.createObjectNode() : node;
        } catch (Exception e) {
            log.warn("Failed to parse ToolUseBlock input for {}: {}", spec.name(), e.getMessage());
            return mapper.createObjectNode();
        }
    }

    private String wireError(ToolResult result) {
        return "{\"error\":{\"code\":" + result.error().code()
                + ",\"message\":" + mapper.valueToTree(result.error().message()).toString() + "}}";
    }
}
