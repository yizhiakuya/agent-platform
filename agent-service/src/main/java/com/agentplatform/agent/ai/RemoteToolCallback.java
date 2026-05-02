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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /**
     * ToolContext key under which RemoteToolCallback stashes any base64 images
     * extracted from a device tool's response. {@code VisionAwareToolCallingManager}
     * drains this list after each batch of tool calls and appends a sibling
     * UserMessage with {@code Media} attachments — so a vision-capable LLM
     * actually sees the bytes, not just a {@code <binary omitted>} stub.
     *
     * <p>Value type: {@code List<PendingImage>} (declared in this class).
     * The map itself must be mutable — see {@code ChatService} for the
     * {@code ConcurrentHashMap} wiring.
     */
    public static final String PENDING_IMAGES_KEY = "agent.pending.vision.images";

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
     * Carrier struct for one base64-encoded image extracted from a tool result.
     * The {@code path} is only used for diagnostics — Anthropic's tool_result
     * doesn't take a "field name" hint, but we keep it to log exactly which
     * field on which tool produced each image.
     */
    public record PendingImage(String toolName, String path, String mimeType, String b64) {}

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
        // Two paths for *_b64 base64 fields:
        //   - vision DISABLED (legacy): replace each *_b64 with a
        //     "<binary NB omitted>" placeholder — keeps a non-vision LLM from
        //     echoing 50-100KB strings into its reply.
        //   - vision ENABLED: same placeholder in the JSON BUT we also stash
        //     the raw bytes into {@link #PENDING_IMAGES_KEY} on the shared
        //     ToolContext, so {@code VisionAwareToolCallingManager} can
        //     append a sibling user message carrying real Media attachments
        //     for the LLM's next turn. Either way the web client still gets
        //     the full payload via the tool_call_result SSE event above.
        if (result.hasError()) {
            return wireError(result);
        }
        JsonNode raw = result.value();
        if (raw == null) {
            return "null";
        }
        if (visionEnabled) {
            List<PendingImage> imgs = new ArrayList<>();
            JsonNode stripped = stripB64ForLlmAndCollect(raw, "", imgs);
            if (!imgs.isEmpty() && toolContext != null) {
                stashPendingImages(toolContext, imgs);
                if (stripped instanceof ObjectNode obj) {
                    obj.put("_vision_attached_count", imgs.size());
                }
            }
            return stripped.toString();
        }
        return stripB64ForLlm(raw).toString();
    }

    @SuppressWarnings("unchecked")
    private void stashPendingImages(ToolContext ctx, List<PendingImage> imgs) {
        Map<String, Object> shared = ctx.getContext();
        if (shared == null) return;
        Object existing = shared.get(PENDING_IMAGES_KEY);
        List<PendingImage> bucket;
        if (existing instanceof List<?> l) {
            bucket = (List<PendingImage>) l;
        } else {
            bucket = new ArrayList<>();
            try {
                shared.put(PENDING_IMAGES_KEY, bucket);
            } catch (UnsupportedOperationException ex) {
                // Caller wired an immutable Map.of(...) — log once and bail.
                // VisionAwareToolCallingManager will see no images and skip.
                log.warn("ToolContext map is immutable — vision tool_result feature disabled for this call. "
                        + "ChatService should pass a mutable Map (e.g. ConcurrentHashMap).");
                return;
            }
        }
        bucket.addAll(imgs);
    }

    /**
     * Vision-aware variant of {@link #stripB64ForLlm}. Walks the tree just
     * like the legacy path (replacing every {@code *_b64} string with a stub),
     * but also collects each base64 string into {@code out} so the caller can
     * forward them to the LLM as Media attachments. The MIME type is inferred
     * from the field name and the raw bytes — JPEG and PNG cover all device
     * tools we ship today.
     *
     * @param pathPrefix dotted JSON pointer accumulated so far, used only for
     *                   diagnostic logging in {@code VisionAwareToolCallingManager}.
     */
    private JsonNode stripB64ForLlmAndCollect(JsonNode node, String pathPrefix, List<PendingImage> out) {
        if (node instanceof ObjectNode obj) {
            ObjectNode copy = obj.objectNode();
            // Layered b64 priority within ONE object: when a tool returns BOTH
            // a high-resolution `vision_b64` (for the LLM to see clearly) and
            // a small `thumb_b64` (for the web bubble), the LLM only needs the
            // vision copy. Skip thumb_b64 collection in that case so we don't
            // waste tokens / context on a duplicate at lower fidelity.
            // The thumb_b64 is still placeholdered (so its bytes don't leak
            // into the LLM as text) but not emitted as an image.
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
                            && (isVision || !hasVision);                  // skip thumb when vision sibling present
                    if (shouldCollect) {
                        copy.put(k, "<binary " + len + "B attached as image; rendered to user>");
                        out.add(new PendingImage(spec.name(), childPath, sniffMime(k, b64), b64));
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
     * and our existing photos.list_recent path emits JPEG. (PDFs are not
     * supported via this path; if a tool ever returns one it would still
     * be stripped but not attached.)
     */
    private static String sniffMime(String fieldName, String b64) {
        if (b64 == null || b64.length() < 8) return "image/jpeg";
        if (b64.startsWith("iVBORw0KGgo")) return "image/png";
        if (b64.startsWith("R0lGOD")) return "image/gif";
        if (b64.startsWith("UklGR")) return "image/webp";
        return "image/jpeg";
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
