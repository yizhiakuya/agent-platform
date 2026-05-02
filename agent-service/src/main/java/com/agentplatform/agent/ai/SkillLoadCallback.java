package com.agentplatform.agent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Meta-tool the LLM uses to pull a skill body on demand.
 *
 * <p>Prompt assembly only injects {@code name + description} for every
 * registered skill (cheap — keeps the static prefix small and cache-friendly).
 * When the LLM judges a skill applies to the current task, it calls
 * {@code skill_load(name=...)} and we return the full markdown body, which
 * Spring AI feeds back as a {@code tool_result} message and the LLM treats
 * as additional context for the next turn.
 *
 * <p>Mirrors Claude Code's progressive-disclosure pattern: descriptions are
 * always visible, full bodies are loaded only when needed. Keeps idle context
 * small and lets us add more skills without ballooning every request.
 *
 * <p>Tool name is plain {@code skill_load} (no dot) so it passes Anthropic's
 * {@code [a-zA-Z0-9_-]} name regex without sanitization.
 */
@Component
public class SkillLoadCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(SkillLoadCallback.class);

    private static final String TOOL_NAME = "skill_load";
    private static final String TOOL_DESCRIPTION =
            "Load the body of a skill by name. Use when you've identified a skill from the "
                    + "available-skills listing that matches the current task. Returns the full "
                    + "markdown playbook; treat the returned text as additional guidance.";

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "Skill name from the available skills listing"
                }
              },
              "required": ["name"]
            }
            """;

    private final SkillRegistry registry;
    private final ObjectMapper mapper;

    public SkillLoadCallback(SkillRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = parseName(toolInput);
        if (name == null || name.isBlank()) {
            return "Error: 'name' is required. Available skills: " + availableNames();
        }
        Optional<SkillDef> hit = registry.get(name);
        if (hit.isEmpty()) {
            log.debug("skill_load miss: '{}'", name);
            return "Skill '" + name + "' not found. Available: " + availableNames();
        }
        log.debug("skill_load hit: '{}' ({} chars)", name, hit.get().body().length());
        return hit.get().body();
    }

    private String parseName(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) return null;
        try {
            JsonNode node = mapper.readTree(toolInput);
            JsonNode n = node.get("name");
            return (n != null && n.isTextual()) ? n.asText() : null;
        } catch (Exception e) {
            log.warn("skill_load failed to parse toolInput JSON: {}", e.getMessage());
            return null;
        }
    }

    private String availableNames() {
        return registry.all().stream()
                .map(SkillDef::name)
                .collect(Collectors.joining(", "));
    }
}
