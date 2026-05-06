package com.agentplatform.agent.chat;

import com.agentplatform.agent.ai.ChatEventSink;
import com.agentplatform.agent.ai.ConfiguredProvider;
import com.agentplatform.agent.ai.ExecutionResult;
import com.agentplatform.agent.ai.PendingImage;
import com.agentplatform.agent.ai.RemoteToolCallback;
import com.agentplatform.agent.ai.ResolvedTools;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal OpenAI-compatible Responses API agent loop for Codex models.
 *
 * <p>The existing Anthropic path stays SDK-native. This runner translates the
 * same platform prompt, tools, and callback dispatch map into Responses API
 * function calls so Codex can use the Android device tools through sub2api.
 * sub2api's HTTP Responses endpoint currently rejects previous_response_id,
 * so each tool iteration replays the in-request transcript instead of linking
 * server-side state.
 */
@Service
public class CodexResponsesLoopRunner {

    private static final Logger log = LoggerFactory.getLogger(CodexResponsesLoopRunner.class);
    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final SkillLoadCallback skillLoadCallback;
    private final WebClient.Builder webClientBuilder;

    public CodexResponsesLoopRunner(ObjectMapper mapper,
                                    AgentProperties props,
                                    SkillLoadCallback skillLoadCallback,
                                    WebClient.Builder defaultWebClientBuilder) {
        this.mapper = mapper;
        this.props = props;
        this.skillLoadCallback = skillLoadCallback;
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
            if (systemText != null && !systemText.isBlank()) {
                request.put("instructions", systemText);
            }
            request.set("input", mapper.valueToTree(transcript));
            if (tools.size() > 0) {
                request.set("tools", tools);
                request.put("parallel_tool_calls", false);
            }

            JsonNode resp = client.post()
                    .uri("/v1/responses")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMinutes(10));
            if (resp == null) {
                throw new IllegalStateException("empty Codex Responses API response");
            }
            JsonNode usage = resp.get("usage");
            if (usage != null && !usage.isMissingNode()) {
                lastUsage = usage;
            }

            String text = extractOutputText(resp);
            if (!text.isEmpty()) {
                textBuf.append(text);
                safeSend(emitter, SseEvent.assistantMessage(mapper, text));
            }

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
                ExecutionResult er = executeFunctionCall(call, resolved, userId, sessionId, sink);
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
        out.add(functionTool(skillLoadCallback.name(),
                "Load the body of a skill by name. Returns the full markdown playbook.",
                skillLoadSchema()));
        return out;
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
        RemoteToolCallback cb = resolved.dispatch().get(call.name());
        if (cb == null) {
            return ExecutionResult.error("unknown tool: " + call.name());
        }
        return cb.executeJsonToolUse(args, userId, sessionId, sink);
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
