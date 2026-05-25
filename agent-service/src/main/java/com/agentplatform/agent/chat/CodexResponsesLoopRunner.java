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
import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.chat.MessageDto;
import com.agentplatform.api.chat.MessageRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Minimal OpenAI-compatible Responses API agent loop for Codex models.
 *
 * <p>The existing Anthropic path stays SDK-native. This runner translates the
 * same platform prompt, local tools, and native Responses tools into the
 * Responses API so Codex can use Android device tools through sub2api and
 * provider-side tools such as web search.
 * sub2api's HTTP Responses endpoint currently rejects previous_response_id,
 * so each tool iteration replays the in-request transcript instead of linking
 * server-side state.
 */
@Service
public class CodexResponsesLoopRunner {

    private static final Logger log = LoggerFactory.getLogger(CodexResponsesLoopRunner.class);
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_EVENT_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final Set<String> RESPONSES_UNSUPPORTED_ROOT_SCHEMA_KEYS =
            Set.of("oneOf", "anyOf", "allOf", "enum", "not");
    private static final String ERROR_FIELD = "error";
    private static final String CONTENT_FIELD = "content";
    private static final String MESSAGE_FIELD = "message";
    private static final String OBJECT_TYPE = "object";
    private static final String OUTPUT_FIELD = "output";
    private static final String OUTPUT_TEXT_FIELD = "output_text";
    private static final String PROPERTIES_FIELD = "properties";
    private static final String STATUS_FIELD = "status";

    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final SkillLoadCallback skillLoadCallback;
    private final ServerToolRegistry serverToolRegistry;
    private final WebClient.Builder webClientBuilder;

    public CodexResponsesLoopRunner(ObjectMapper mapper,
                                    AgentProperties props,
                                    SkillLoadCallback skillLoadCallback,
                                    ServerToolRegistry serverToolRegistry,
                                    WebClient.Builder defaultWebClientBuilder) {
        this.mapper = mapper;
        this.props = props;
        this.skillLoadCallback = skillLoadCallback;
        this.serverToolRegistry = serverToolRegistry;
        this.webClientBuilder = defaultWebClientBuilder;
    }

    public RunResult run(RunRequest request) {
        RunRequest runRequest = request == null ? new RunRequest() : request;
        ConfiguredProvider provider = runRequest.provider;
        if (!provider.isCodexResponses()) {
            throw new IllegalArgumentException("CodexResponsesLoopRunner requires codex-responses provider");
        }

        ChatCancellationToken cancellation = runRequest.cancellation();
        SseEmitter emitter = runRequest.emitter();
        emitter.onTimeout(cancellation::cancel);

        CodexRunState state = initialRunState(runRequest, provider, emitter, cancellation);

        int maxIterations = props.agent().maxAgentIterations();
        for (int iter = 0; iter < maxIterations; iter++) {
            RunResult terminal = runIteration(state);
            if (terminal != null) {
                return terminal;
            }
        }
        return exhaustedRunResult(state, maxIterations);
    }

    private CodexRunState initialRunState(RunRequest request,
                                          ConfiguredProvider provider,
                                          SseEmitter emitter,
                                          ChatCancellationToken cancellation) {
        CodexRunState state = new CodexRunState();
        state.provider = provider;
        state.sessionId = request.sessionId;
        state.userId = request.userId;
        state.resolved = request.resolved();
        state.systemText = request.systemText;
        state.sink = request.sink();
        state.emitter = emitter;
        state.cancellation = cancellation;
        state.client = webClientBuilder.baseUrl(stripTrailingSlash(provider.baseUrl())).build();
        state.textBuf = new StringBuilder();
        state.transcript = buildInitialInput(request.history(), request.userText, request.attachmentsOrDefault());
        state.tools = buildTools(state.resolved);
        state.toolBudget = new ToolCallBudget(
                props.agent().maxToolCallsPerTurn(),
                props.agent().maxConsecutiveUiToolCalls());
        return state;
    }

    private RunResult runIteration(CodexRunState state) {
        if (state.cancellation.isCancelled()) {
            return new RunResult(state.textBuf.toString(), state.lastUsage, true);
        }
        state.textBuf.setLength(0);

        int textLenBeforeRequest = state.textBuf.length();
        JsonNode resp = streamResponse(state.client, state.provider, requestBody(state),
                state.textBuf, state.sink, state.cancellation);
        if (resp == null) {
            return emptyResponseResult(state);
        }

        updateUsage(state, resp);
        emitTextFallbackIfNeeded(state, resp, textLenBeforeRequest);
        checkResponseStatus(resp);

        List<FunctionCall> calls = extractFunctionCalls(resp);
        if (calls.isEmpty()) {
            return new RunResult(state.textBuf.toString(), state.lastUsage, state.cancellation.isCancelled());
        }

        appendResponseOutput(state.transcript, resp);
        return executeFunctionCalls(state, calls);
    }

    private ObjectNode requestBody(CodexRunState state) {
        ObjectNode request = mapper.createObjectNode();
        request.put("model", state.provider.model());
        request.put("max_output_tokens", props.agent().maxTokens());
        request.put("stream", true);
        if (state.systemText != null && !state.systemText.isBlank()) {
            request.put("instructions", state.systemText);
        }
        request.set("input", mapper.valueToTree(state.transcript));
        if (state.tools.size() > 0) {
            request.set("tools", state.tools);
            request.put("parallel_tool_calls", false);
        }
        return request;
    }

    private RunResult emptyResponseResult(CodexRunState state) {
        if (state.cancellation.isCancelled()) {
            return new RunResult(state.textBuf.toString(), state.lastUsage, true);
        }
        throw new IllegalStateException("empty Codex Responses API response");
    }

    private void updateUsage(CodexRunState state, JsonNode resp) {
        JsonNode usage = resp.get("usage");
        if (usage != null && !usage.isMissingNode()) {
            state.lastUsage = usage;
        }
    }

    private void emitTextFallbackIfNeeded(CodexRunState state, JsonNode resp, int textLenBeforeRequest) {
        if (state.textBuf.length() != textLenBeforeRequest) {
            return;
        }
        String text = extractOutputText(resp);
        if (!text.isEmpty()) {
            state.textBuf.append(text);
            state.sink.emit(SseEvent.assistantMessage(mapper, text));
        }
    }

    private void appendResponseOutput(List<JsonNode> transcript, JsonNode resp) {
        JsonNode output = resp.path(OUTPUT_FIELD);
        if (output.isArray()) {
            for (JsonNode item : output) {
                transcript.add(item.deepCopy());
            }
        }
    }

    private RunResult executeFunctionCalls(CodexRunState state, List<FunctionCall> calls) {
        for (FunctionCall call : calls) {
            ToolCallBudget.Decision budgetDecision = state.toolBudget.before(call.name());
            if (!budgetDecision.allowed()) {
                log.info("Codex Responses loop paused by tool budget user={} used={}/{} consecutiveUi={}/{} tool={}",
                        state.userId, state.toolBudget.used(), state.toolBudget.maxToolCalls(),
                        state.toolBudget.consecutiveUi(), state.toolBudget.maxConsecutiveUiToolCalls(), call.name());
                return RunResult.exhausted(state.textBuf.toString(), state.lastUsage,
                        budgetDecision.exhaustionReason());
            }
            ExecutionResult er = executeFunctionCallSafely(state, call, budgetDecision);
            state.transcript.add(functionOutput(call, er));
            if (!er.images().isEmpty()) {
                state.transcript.add(imageMessage(call, er));
            }
        }
        return null;
    }

    private ExecutionResult executeFunctionCallSafely(CodexRunState state,
                                                      FunctionCall call,
                                                      ToolCallBudget.Decision budgetDecision) {
        try {
            return executeFunctionCall(call, state.resolved, state.userId, state.sessionId,
                    budgetDecision.decorate(state.sink));
        } catch (Exception t) {
            log.error("codex function_call threw name={} err={}", call.name(), t.toString(), t);
            return ExecutionResult.error("tool execution failed: " + t.getMessage());
        }
    }

    private RunResult exhaustedRunResult(CodexRunState state, int maxIterations) {
        log.warn("Codex Responses loop hit maxAgentIterations={} for user {}", maxIterations, state.userId);
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
        private String systemText;
        private List<MessageDto> history;
        private String userText;
        private ChatAttachmentContext attachments;
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

        public RunRequest withSystemText(String systemText) {
            this.systemText = systemText;
            return this;
        }

        public RunRequest withHistory(List<MessageDto> history) {
            this.history = history;
            return this;
        }

        public RunRequest withUserText(String userText) {
            this.userText = userText;
            return this;
        }

        public RunRequest withAttachments(ChatAttachmentContext attachments) {
            this.attachments = attachments;
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
            return resolved == null ? new ResolvedTools(List.of(), Map.of()) : resolved;
        }

        private List<MessageDto> history() {
            return history == null ? List.of() : history;
        }

        private ChatAttachmentContext attachmentsOrDefault() {
            return attachments == null
                    ? new ChatAttachmentContext(List.of(), List.of(), new ObjectMapper().createArrayNode(), "")
                    : attachments;
        }

        private ChatEventSink sink() {
            return sink == null ? event -> { } : sink;
        }

        private SseEmitter emitter() {
            return emitter == null ? new SseEmitter() : emitter;
        }

        private ChatCancellationToken cancellation() {
            return cancellation == null ? new ChatCancellationToken() : cancellation;
        }
    }

    private static final class CodexRunState {
        private ConfiguredProvider provider;
        private UUID sessionId;
        private UUID userId;
        private ResolvedTools resolved;
        private String systemText;
        private ChatEventSink sink;
        private SseEmitter emitter;
        private ChatCancellationToken cancellation;
        private WebClient client;
        private StringBuilder textBuf;
        private JsonNode lastUsage;
        private List<JsonNode> transcript;
        private ArrayNode tools;
        private ToolCallBudget toolBudget;
    }

    private JsonNode streamResponse(WebClient client,
                                    ConfiguredProvider provider,
                                    ObjectNode request,
                                    StringBuilder textBuf,
                                    ChatEventSink sink,
                                    ChatCancellationToken cancellation) {
        StreamState state = new StreamState(textBuf, sink, mapper.createArrayNode());
        for (ServerSentEvent<String> event : client.post()
                .uri("/v1/responses")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(SSE_EVENT_TYPE)
                .timeout(Duration.ofMinutes(10))
                .toIterable()) {
            if (cancellation.isCancelled()) {
                return null;
            }
            handleStreamEvent(event, state);
        }
        return finalStreamResponse(state);
    }

    private void handleStreamEvent(ServerSentEvent<String> event, StreamState state) {
        JsonNode node = parsedStreamNode(event.data());
        if (node == null) {
            return;
        }
        String type = node.path("type").asText(event.event() == null ? "" : event.event());
        if ("response.output_text.delta".equals(type)) {
            appendOutputTextDelta(node, state);
        } else if ("response.output_item.done".equals(type)) {
            appendOutputItem(node, state);
        } else if (ERROR_FIELD.equals(type) || "response.error".equals(type)) {
            throw new IllegalStateException(streamErrorMessage(node));
        } else if (isTerminalStreamEvent(type)) {
            captureTerminalResponse(node, state);
        } else {
            captureLastResponse(node, state);
        }
    }

    private JsonNode parsedStreamNode(String data) {
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
            return null;
        }
        return parseStreamData(data);
    }

    private void appendOutputTextDelta(JsonNode node, StreamState state) {
        String delta = node.path("delta").asText("");
        if (!delta.isEmpty()) {
            state.textBuf.append(delta);
            state.sink.emit(SseEvent.assistantMessage(mapper, delta));
        }
    }

    private void appendOutputItem(JsonNode node, StreamState state) {
        JsonNode item = node.path("item");
        if (!item.isMissingNode() && !item.isNull()) {
            state.outputItems.add(item.deepCopy());
        }
    }

    private boolean isTerminalStreamEvent(String type) {
        return "response.completed".equals(type)
                || "response.done".equals(type)
                || "response.failed".equals(type)
                || "response.incomplete".equals(type);
    }

    private void captureTerminalResponse(JsonNode node, StreamState state) {
        JsonNode response = node.path("response");
        if (!response.isMissingNode() && !response.isNull()) {
            state.completed = response.deepCopy();
            state.lastResponse = response;
        } else if (node.has(OUTPUT_FIELD) || node.has(OUTPUT_TEXT_FIELD) || node.has(STATUS_FIELD)) {
            state.completed = node.deepCopy();
            state.lastResponse = node;
        }
    }

    private void captureLastResponse(JsonNode node, StreamState state) {
        JsonNode response = node.path("response");
        if (!response.isMissingNode() && !response.isNull()) {
            state.lastResponse = response;
        } else if (node.has(OUTPUT_FIELD) || node.has(OUTPUT_TEXT_FIELD)) {
            state.lastResponse = node;
        }
    }

    private JsonNode finalStreamResponse(StreamState state) {
        if (state.completed != null) {
            return withStreamedOutput(state.completed, state.outputItems);
        }
        if (state.lastResponse != null) {
            return withStreamedOutput(state.lastResponse, state.outputItems);
        }
        if (!state.textBuf.isEmpty()) {
            ObjectNode fallback = mapper.createObjectNode();
            fallback.put(STATUS_FIELD, "completed");
            fallback.put(OUTPUT_TEXT_FIELD, "");
            return withStreamedOutput(fallback, state.outputItems);
        }
        return null;
    }

    private static final class StreamState {
        private final StringBuilder textBuf;
        private final ChatEventSink sink;
        private final ArrayNode outputItems;
        private JsonNode completed;
        private JsonNode lastResponse;

        private StreamState(StringBuilder textBuf, ChatEventSink sink, ArrayNode outputItems) {
            this.textBuf = textBuf;
            this.sink = sink;
            this.outputItems = outputItems;
        }
    }

    private JsonNode withStreamedOutput(JsonNode response, ArrayNode outputItems) {
        if (!response.isObject() || outputItems == null || outputItems.isEmpty()) {
            return response;
        }
        JsonNode output = response.path(OUTPUT_FIELD);
        if (output.isArray() && !output.isEmpty()) {
            return response;
        }
        ObjectNode copy = (ObjectNode) response.deepCopy();
        copy.set(OUTPUT_FIELD, outputItems.deepCopy());
        return copy;
    }

    private JsonNode parseStreamData(String data) {
        try {
            return mapper.readTree(data);
        } catch (Exception e) {
            log.debug("Ignoring malformed Responses stream event: {}", e.getMessage());
            return null;
        }
    }

    private String streamErrorMessage(JsonNode node) {
        JsonNode error = node.path(ERROR_FIELD);
        String message = error.path(MESSAGE_FIELD).asText("");
        if (!message.isBlank()) {
            return message;
        }
        message = node.path(MESSAGE_FIELD).asText("");
        return message.isBlank() ? "Codex Responses stream failed" : message;
    }

    private void checkResponseStatus(JsonNode resp) {
        String status = resp.path(STATUS_FIELD).asText("");
        if (!"failed".equals(status) && !"incomplete".equals(status) && !"cancelled".equals(status)) {
            return;
        }
        String message = resp.path(ERROR_FIELD).path(MESSAGE_FIELD).asText("");
        if (message.isBlank()) {
            message = resp.path("incomplete_details").path("reason").asText("");
        }
        if (message.isBlank()) {
            message = "Codex Responses ended with status=" + status;
        }
        throw new IllegalStateException(message);
    }

    private List<JsonNode> buildInitialInput(List<MessageDto> history, String userText,
                                             ChatAttachmentContext attachments) {
        List<JsonNode> input = new ArrayList<>();
        if (history != null) {
            for (MessageDto row : history) {
                String content = row.content() == null ? "" : row.content();
                if (content.isBlank()) continue;
                if (row.role() == MessageRole.USER) {
                    input.add(message("user", content));
                } else if (row.role() == MessageRole.ASSISTANT) {
                    input.add(message("assistant", content));
                }
            }
        }
        input.add(userMessage(userText, attachments));
        return input;
    }

    private ObjectNode message(String role, String text) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        node.put(CONTENT_FIELD, text);
        return node;
    }

    private ObjectNode userMessage(String text, ChatAttachmentContext attachments) {
        if (attachments == null || attachments.images().isEmpty()) {
            return message("user", userTextWithAttachments(text, attachments));
        }
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        ArrayNode content = mapper.createArrayNode();
        ObjectNode note = mapper.createObjectNode();
        note.put("type", "input_text");
        note.put("text", userTextWithAttachments(text, attachments));
        content.add(note);
        for (PendingImage img : attachments.images()) {
            ObjectNode image = mapper.createObjectNode();
            image.put("type", "input_image");
            image.put("image_url", "data:" + img.mimeType() + ";base64," + img.b64());
            image.put("detail", "high");
            content.add(image);
        }
        msg.set(CONTENT_FIELD, content);
        return msg;
    }

    private String userTextWithAttachments(String text, ChatAttachmentContext attachments) {
        String base = text == null ? "" : text;
        if (attachments == null || attachments.promptText() == null || attachments.promptText().isBlank()) {
            return base;
        }
        if (base.isBlank()) {
            return attachments.promptText();
        }
        return base + "\n\n" + attachments.promptText();
    }

    private ArrayNode buildTools(ResolvedTools resolved) {
        ArrayNode out = mapper.createArrayNode();
        for (Map.Entry<String, RemoteToolCallback> entry : resolved.dispatch().entrySet()) {
            RemoteToolCallback cb = entry.getValue();
            out.add(functionTool(entry.getKey(), cb.spec().description(), cb.spec().schema()));
        }
        for (Map.Entry<String, ServerToolCallback> entry : serverToolRegistry.dispatchMap().entrySet()) {
            ServerToolCallback cb = entry.getValue();
            out.add(functionTool(entry.getKey(), cb.description(), cb.schema()));
        }
        out.add(functionTool(skillLoadCallback.name(),
                "Load the body of a skill by name. Returns the full markdown playbook.",
                skillLoadSchema()));
        if (Boolean.TRUE.equals(props.agent().memory().enableWebSearch())) {
            out.add(webSearchTool());
        }
        return out;
    }

    private ObjectNode webSearchTool() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "web_search");
        return tool;
    }

    private ObjectNode functionTool(String name, String description, @Nullable JsonNode schema) {
        ObjectNode fn = mapper.createObjectNode();
        fn.put("type", "function");
        fn.put("name", name);
        fn.put("description", description == null ? "" : description);
        fn.set("parameters", normalizeSchema(schema));
        return fn;
    }

    private JsonNode skillLoadSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", OBJECT_TYPE);
        ObjectNode propsNode = mapper.createObjectNode();
        ObjectNode name = mapper.createObjectNode();
        name.put("type", "string");
        name.put("description", "Skill name from the available skills listing");
        propsNode.set("name", name);
        schema.set(PROPERTIES_FIELD, propsNode);
        ArrayNode required = mapper.createArrayNode();
        required.add("name");
        schema.set("required", required);
        return schema;
    }

    private JsonNode normalizeSchema(@Nullable JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            ObjectNode fallback = mapper.createObjectNode();
            fallback.put("type", OBJECT_TYPE);
            fallback.set(PROPERTIES_FIELD, mapper.createObjectNode());
            return fallback;
        }
        ObjectNode copy = schema.deepCopy();
        if (!copy.hasNonNull("type")) {
            copy.put("type", OBJECT_TYPE);
        }
        if (!copy.has(PROPERTIES_FIELD)) {
            copy.set(PROPERTIES_FIELD, mapper.createObjectNode());
        }
        for (String key : RESPONSES_UNSUPPORTED_ROOT_SCHEMA_KEYS) {
            copy.remove(key);
        }
        return copy;
    }

    private String extractOutputText(JsonNode resp) {
        String direct = resp.path(OUTPUT_TEXT_FIELD).asText("");
        if (!direct.isBlank()) {
            return direct;
        }
        StringBuilder out = new StringBuilder();
        JsonNode output = resp.path(OUTPUT_FIELD);
        if (output.isArray()) {
            for (JsonNode item : output) {
                JsonNode content = item.path(CONTENT_FIELD);
                if (!content.isArray()) continue;
                for (JsonNode block : content) {
                    String type = block.path("type").asText("");
                    if (OUTPUT_TEXT_FIELD.equals(type) || "text".equals(type)) {
                        out.append(block.path("text").asText(""));
                    }
                }
            }
        }
        return out.toString();
    }

    private List<FunctionCall> extractFunctionCalls(JsonNode resp) {
        List<FunctionCall> calls = new ArrayList<>();
        JsonNode output = resp.path(OUTPUT_FIELD);
        if (!output.isArray()) return calls;
        for (JsonNode item : output) {
            String type = item.path("type").asText("");
            if (!"function_call".equals(type)) continue;
            String name = item.path("name").asText("");
            String callId = item.path("call_id").asText("");
            String arguments = item.path("arguments").asText("{}");
            if (!name.isBlank() && !callId.isBlank()) {
                calls.add(new FunctionCall(name, callId, arguments));
            }
        }
        return calls;
    }

    private ExecutionResult executeFunctionCall(FunctionCall call, ResolvedTools resolved,
                                                UUID userId, UUID sessionId, ChatEventSink sink) {
        JsonNode args = parseArguments(call.arguments());
        if (SkillLoadCallback.TOOL_NAME.equals(call.name())) {
            return skillLoadCallback.executeJsonToolUse(args, userId);
        }
        ServerToolCallback serverTool = serverToolRegistry.dispatchMap().get(call.name());
        if (serverTool != null) {
            if (sink != null) {
                sink.emit(SseEvent.toolCallStarted(mapper, null, call.name(), args));
            }
            ExecutionResult result;
            try {
                result = serverTool.executeJsonToolUse(args, userId, sessionId, sink);
            } catch (RuntimeException e) {
                if (sink != null) {
                    sink.emit(SseEvent.toolCallError(mapper, call.name(), e.getMessage()));
                    sink.emit(SseEvent.error(mapper, "Tool '" + call.name() + "' failed: " + e.getMessage()));
                }
                return ExecutionResult.error(e.getMessage());
            }
            if (sink != null) {
                sink.emit(SseEvent.toolCallResult(mapper, call.name(), parseExecutionResult(result)));
            }
            return result;
        }
        RemoteToolCallback cb = resolved.dispatch().get(call.name());
        if (cb == null) {
            return ExecutionResult.error("unknown tool: " + call.name());
        }
        return cb.executeJsonToolUse(args, userId, sessionId, sink);
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

    private JsonNode parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode node = mapper.readTree(arguments);
            return node == null || node.isNull() ? mapper.createObjectNode() : node;
        } catch (Exception e) {
            ObjectNode fallback = mapper.createObjectNode();
            fallback.put("_raw_arguments", arguments);
            return fallback;
        }
    }

    private ObjectNode functionOutput(FunctionCall call, ExecutionResult er) {
        ObjectNode out = mapper.createObjectNode();
        out.put("type", "function_call_output");
        out.put("call_id", call.callId());
        out.put(OUTPUT_FIELD, er.jsonText());
        return out;
    }

    private ObjectNode imageMessage(FunctionCall call, ExecutionResult er) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        ArrayNode content = mapper.createArrayNode();

        ObjectNode note = mapper.createObjectNode();
        note.put("type", "input_text");
        note.put("text", "The tool result from " + call.name()
                + " included image attachment(s). Inspect the image pixels directly; do not rely only on OCR or metadata.");
        content.add(note);

        for (PendingImage img : er.images()) {
            ObjectNode image = mapper.createObjectNode();
            image.put("type", "input_image");
            image.put("image_url", "data:" + img.mimeType() + ";base64," + img.b64());
            image.put("detail", "high");
            content.add(image);
        }

        msg.set(CONTENT_FIELD, content);
        return msg;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) return "https://api.openai.com";
        String out = url.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private record FunctionCall(String name, String callId, String arguments) {}
}
