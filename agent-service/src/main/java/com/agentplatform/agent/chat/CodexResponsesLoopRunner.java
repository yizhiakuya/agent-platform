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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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

    public RunResult run(ConfiguredProvider provider,
                         UUID sessionId,
                         UUID userId,
                         ResolvedTools resolved,
                         String systemText,
                         List<MessageDto> history,
                         String userText,
                         ChatEventSink sink,
                         SseEmitter emitter) {
        if (!provider.isCodexResponses()) {
            throw new IllegalArgumentException("CodexResponsesLoopRunner requires codex-responses provider");
        }

        AtomicBoolean cancelled = new AtomicBoolean(false);
        Runnable cancel = () -> cancelled.set(true);
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(t -> cancel.run());

        WebClient client = webClientBuilder
                .baseUrl(stripTrailingSlash(provider.baseUrl()))
                .build();
        StringBuilder textBuf = new StringBuilder();
        JsonNode lastUsage = null;
        List<JsonNode> transcript = buildInitialInput(history, userText);
        ArrayNode tools = buildTools(resolved);

        int maxIterations = props.agent().maxAgentIterations();
        for (int iter = 0; iter < maxIterations; iter++) {
            if (cancelled.get()) {
                return new RunResult(textBuf.toString(), lastUsage, true);
            }

            ObjectNode request = mapper.createObjectNode();
            request.put("model", provider.model());
            request.put("max_output_tokens", props.agent().maxTokens());
            request.put("stream", true);
            if (systemText != null && !systemText.isBlank()) {
                request.put("instructions", systemText);
            }
            request.set("input", mapper.valueToTree(transcript));
            if (tools.size() > 0) {
                request.set("tools", tools);
                request.put("parallel_tool_calls", false);
            }

            int textLenBeforeRequest = textBuf.length();
            JsonNode resp = streamResponse(client, provider, request, textBuf, sink, cancelled);
            if (resp == null) {
                if (cancelled.get()) {
                    return new RunResult(textBuf.toString(), lastUsage, true);
                }
                throw new IllegalStateException("empty Codex Responses API response");
            }
            JsonNode usage = resp.get("usage");
            if (usage != null && !usage.isMissingNode()) {
                lastUsage = usage;
            }

            if (textBuf.length() == textLenBeforeRequest) {
                String text = extractOutputText(resp);
                if (!text.isEmpty()) {
                    textBuf.append(text);
                    sink.emit(SseEvent.assistantMessage(mapper, text));
                }
            }
            checkResponseStatus(resp);

            List<FunctionCall> calls = extractFunctionCalls(resp);
            if (calls.isEmpty()) {
                return new RunResult(textBuf.toString(), lastUsage, cancelled.get());
            }

            JsonNode output = resp.path("output");
            if (output.isArray()) {
                for (JsonNode item : output) {
                    transcript.add(item.deepCopy());
                }
            }
            for (FunctionCall call : calls) {
                ExecutionResult er;
                try {
                    er = executeFunctionCall(call, resolved, userId, sessionId, sink);
                } catch (Throwable t) {
                    log.error("codex function_call threw name={} err={}", call.name(), t.toString(), t);
                    er = ExecutionResult.error("tool execution failed: " + t.getMessage());
                }
                transcript.add(functionOutput(call, er));
                if (!er.images().isEmpty()) {
                    transcript.add(imageMessage(call, er));
                }
            }
        }

        log.warn("Codex Responses loop hit maxAgentIterations={} for user {}", maxIterations, userId);
        return RunResult.exhausted(
                textBuf.toString(),
                lastUsage,
                "任务还没完成，但本轮工具调用次数已达到上限（" + maxIterations + " 轮）。请继续发送“继续”，我会接着当前页面状态往下做。"
        );
    }

    private JsonNode streamResponse(WebClient client,
                                    ConfiguredProvider provider,
                                    ObjectNode request,
                                    StringBuilder textBuf,
                                    ChatEventSink sink,
                                    AtomicBoolean cancelled) {
        JsonNode completed = null;
        JsonNode lastResponse = null;
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
            if (cancelled.get()) {
                return null;
            }
            String data = event.data();
            if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
                continue;
            }
            JsonNode node = parseStreamData(data);
            if (node == null) {
                continue;
            }
            String type = node.path("type").asText(event.event() == null ? "" : event.event());
            if ("response.output_text.delta".equals(type)) {
                String delta = node.path("delta").asText("");
                if (!delta.isEmpty()) {
                    textBuf.append(delta);
                    sink.emit(SseEvent.assistantMessage(mapper, delta));
                }
            } else if ("error".equals(type) || "response.error".equals(type)) {
                throw new RuntimeException(streamErrorMessage(node));
            } else if ("response.completed".equals(type)
                    || "response.done".equals(type)
                    || "response.failed".equals(type)
                    || "response.incomplete".equals(type)) {
                JsonNode response = node.path("response");
                if (!response.isMissingNode() && !response.isNull()) {
                    completed = response;
                    lastResponse = response;
                } else if (node.has("output") || node.has("output_text") || node.has("status")) {
                    completed = node;
                    lastResponse = node;
                }
            } else {
                JsonNode response = node.path("response");
                if (!response.isMissingNode() && !response.isNull()) {
                    lastResponse = response;
                } else if (node.has("output") || node.has("output_text")) {
                    lastResponse = node;
                }
            }
        }
        if (completed != null) {
            return completed;
        }
        if (lastResponse != null) {
            return lastResponse;
        }
        if (!textBuf.isEmpty()) {
            ObjectNode fallback = mapper.createObjectNode();
            fallback.put("status", "completed");
            fallback.put("output_text", "");
            return fallback;
        }
        return null;
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
        JsonNode error = node.path("error");
        String message = error.path("message").asText("");
        if (!message.isBlank()) {
            return message;
        }
        message = node.path("message").asText("");
        return message.isBlank() ? "Codex Responses stream failed" : message;
    }

    private void checkResponseStatus(JsonNode resp) {
        String status = resp.path("status").asText("");
        if (!"failed".equals(status) && !"incomplete".equals(status) && !"cancelled".equals(status)) {
            return;
        }
        String message = resp.path("error").path("message").asText("");
        if (message.isBlank()) {
            message = resp.path("incomplete_details").path("reason").asText("");
        }
        if (message.isBlank()) {
            message = "Codex Responses ended with status=" + status;
        }
        throw new RuntimeException(message);
    }

    private List<JsonNode> buildInitialInput(List<MessageDto> history, String userText) {
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
        input.add(message("user", userText == null ? "" : userText));
        return input;
    }

    private ObjectNode message(String role, String text) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        node.put("content", text);
        return node;
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
        schema.put("type", "object");
        ObjectNode propsNode = mapper.createObjectNode();
        ObjectNode name = mapper.createObjectNode();
        name.put("type", "string");
        name.put("description", "Skill name from the available skills listing");
        propsNode.set("name", name);
        schema.set("properties", propsNode);
        ArrayNode required = mapper.createArrayNode();
        required.add("name");
        schema.set("required", required);
        return schema;
    }

    private JsonNode normalizeSchema(@Nullable JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            ObjectNode fallback = mapper.createObjectNode();
            fallback.put("type", "object");
            fallback.set("properties", mapper.createObjectNode());
            return fallback;
        }
        ObjectNode copy = schema.deepCopy();
        if (!copy.hasNonNull("type")) {
            copy.put("type", "object");
        }
        if (!copy.has("properties")) {
            copy.set("properties", mapper.createObjectNode());
        }
        return copy;
    }

    private String extractOutputText(JsonNode resp) {
        String direct = resp.path("output_text").asText("");
        if (!direct.isBlank()) {
            return direct;
        }
        StringBuilder out = new StringBuilder();
        JsonNode output = resp.path("output");
        if (output.isArray()) {
            for (JsonNode item : output) {
                JsonNode content = item.path("content");
                if (!content.isArray()) continue;
                for (JsonNode block : content) {
                    String type = block.path("type").asText("");
                    if ("output_text".equals(type) || "text".equals(type)) {
                        out.append(block.path("text").asText(""));
                    }
                }
            }
        }
        return out.toString();
    }

    private List<FunctionCall> extractFunctionCalls(JsonNode resp) {
        List<FunctionCall> calls = new ArrayList<>();
        JsonNode output = resp.path("output");
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
            return skillLoadCallback.executeJsonToolUse(args, userId, sessionId, sink);
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
        out.put("output", er.jsonText());
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

        msg.set("content", content);
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

    private void safeSend(SseEmitter emitter, SseEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event.data()));
        } catch (Exception e) {
            // emitter likely closed by client cancel
        }
    }

    private record FunctionCall(String name, String callId, String arguments) {}
}
