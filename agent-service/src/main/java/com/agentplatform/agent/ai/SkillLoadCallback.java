package com.agentplatform.agent.ai;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Meta-tool the LLM uses to pull a skill body on demand.
 *
 * <p>Prompt assembly only injects {@code name + description} for every
 * registered skill (cheap — keeps the static prefix small and cache-friendly).
 * When the LLM judges a skill applies to the current task, it issues
 * {@code skill_load(name=...)} and the agentic loop in {@code ChatService}
 * routes that {@link ToolUseBlock} here. The full markdown body is returned
 * as text, which the SDK feeds back as a {@code tool_result} block; the LLM
 * treats the returned text as additional context for the next turn.
 *
 * <p>Mirrors Claude Code's progressive-disclosure pattern: descriptions are
 * always visible, full bodies are loaded only when needed.
 *
 * <p>Tool name is plain {@code skill_load} (no dot) so it passes Anthropic's
 * {@code [a-zA-Z0-9_-]} name regex without sanitization.
 */
@Component
public class SkillLoadCallback {

    private static final Logger log = LoggerFactory.getLogger(SkillLoadCallback.class);

    public static final String TOOL_NAME = "skill_load";

    private static final String TOOL_DESCRIPTION =
            "Load the body of a skill by name. Use when you've identified a skill from the "
                    + "available-skills listing that matches the current task. Returns the full "
                    + "markdown playbook; treat the returned text as additional guidance.";

    private final SkillRegistry registry;
    private final ObjectMapper mapper;

    public SkillLoadCallback(SkillRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    /** Wire name as the LLM sees it. Stable {@value #TOOL_NAME}. */
    public String name() {
        return TOOL_NAME;
    }

    /**
     * SDK form of this meta-tool. Composed alongside the device-tool
     * {@link Tool}s in {@code ChatService} when building the request.
     */
    public Tool toAnthropicTool() {
        Map<String, Object> nameProp = Map.of(
                "type", "string",
                "description", "Skill name from the available skills listing"
        );
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(JsonValue.from(Map.of("name", nameProp)))
                .putAdditionalProperty("required", JsonValue.from(List.of("name")))
                .build();
        return Tool.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .inputSchema(schema)
                .build();
    }

    /**
     * Resolve a {@code skill_load} tool_use to its markdown body. Always
     * returns text-only — there are no images on the skill_load path, so the
     * {@link ExecutionResult#images()} list is empty.
     *
     * @param tu        tool_use block carrying {@code {"name": "<skill>"}}
     * @param userId    chat user — currently unused but threaded for consistency
     *                  with {@link RemoteToolCallback#executeToolUse}
     * @param sessionId chat session — same
     * @param sink      per-request SSE sink — currently unused (skill bodies
     *                  aren't broadcast to the web client)
     */
    public ExecutionResult executeToolUse(ToolUseBlock tu, UUID userId, UUID sessionId, ChatEventSink sink) {
        String name = parseName(tu);
        if (name == null || name.isBlank()) {
            return ExecutionResult.text("Error: 'name' is required. Available skills: " + availableNames());
        }
        Optional<SkillDef> hit = registry.get(name);
        if (hit.isEmpty()) {
            log.debug("skill_load miss: '{}'", name);
            return ExecutionResult.text("Skill '" + name + "' not found. Available: " + availableNames());
        }
        log.debug("skill_load hit: '{}' ({} chars)", name, hit.get().body().length());
        return ExecutionResult.text(hit.get().body());
    }

    private String parseName(ToolUseBlock tu) {
        try {
            Object raw = tu.input();
            if (raw == null) return null;
            JsonNode node = mapper.valueToTree(raw);
            if (node != null && node.isTextual()) {
                // Defensive: SDK occasionally hands back a JSON-encoded string
                // when the model emits malformed input — try to re-parse.
                try {
                    node = mapper.readTree(node.asText());
                } catch (Exception ignored) {
                    return null;
                }
            }
            if (node == null || !node.isObject()) return null;
            JsonNode n = node.get("name");
            return (n != null && n.isTextual()) ? n.asText() : null;
        } catch (Exception e) {
            log.warn("skill_load failed to parse ToolUseBlock input: {}", e.getMessage());
            return null;
        }
    }

    private String availableNames() {
        return registry.all().stream()
                .map(SkillDef::name)
                .collect(Collectors.joining(", "));
    }
}
