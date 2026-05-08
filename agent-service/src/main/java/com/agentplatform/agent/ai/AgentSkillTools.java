package com.agentplatform.agent.ai;

import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.api.chat.RuntimeSkillDto;
import com.agentplatform.api.chat.UpsertRuntimeSkillRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AgentSkillTools {

    private static final int MAX_SKILL_DESCRIPTION_CHARS = 500;
    private static final int MAX_SKILL_BODY_CHARS = 20_000;
    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private AgentSkillTools() {}

    @Component
    public static class Upsert implements ServerToolCallback {
        private final InternalChatFeignClient chatClient;
        private final ObjectMapper mapper;

        public Upsert(InternalChatFeignClient chatClient, ObjectMapper mapper) {
            this.chatClient = chatClient;
            this.mapper = mapper;
        }

        @Override
        public String name() {
            return "agent_skill_upsert";
        }

        @Override
        public String description() {
            return "Create or update a user runtime skill. Use for reusable workflows, project habits, "
                    + "tool usage standards, and repeated troubleshooting lessons that should be available via skill_load.";
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = AgentMemoryTools.schema(mapper);
            ObjectNode props = (ObjectNode) schema.get("properties");
            props.set("name", AgentMemoryTools.prop(mapper, "string", "Skill name, [a-zA-Z0-9_-], max 64 chars."));
            props.set("description", AgentMemoryTools.prop(mapper, "string", "One-line description shown in the skill index."));
            props.set("body", AgentMemoryTools.prop(mapper, "string", "Markdown playbook body. Keep it concise and operational."));
            props.set("enabled", AgentMemoryTools.prop(mapper, "boolean", "Default true. Set false to keep a disabled draft."));
            ArrayNode required = mapper.createArrayNode();
            required.add("name");
            required.add("description");
            required.add("body");
            schema.set("required", required);
            return schema;
        }

        @Override
        public ExecutionResult executeJsonToolUse(JsonNode args, UUID userId, UUID sessionId, ChatEventSink sink) {
            String name = text(args, "name");
            String description = text(args, "description");
            String body = text(args, "body");
            if (name.isBlank() || description.isBlank() || body.isBlank()) {
                return ExecutionResult.error("name, description, and body are required");
            }
            if (!SKILL_NAME_PATTERN.matcher(name).matches()) {
                return ExecutionResult.error("name must match [a-zA-Z0-9_-]{1,64}");
            }
            if (description.length() > MAX_SKILL_DESCRIPTION_CHARS) {
                return ExecutionResult.error("description too long; max " + MAX_SKILL_DESCRIPTION_CHARS + " chars");
            }
            if (body.length() > MAX_SKILL_BODY_CHARS) {
                return ExecutionResult.error("body too long; max " + MAX_SKILL_BODY_CHARS + " chars");
            }
            Boolean enabled = args != null && args.has("enabled") ? args.path("enabled").asBoolean() : null;
            RuntimeSkillDto saved = chatClient.upsertRuntimeSkill(
                    new UpsertRuntimeSkillRequest(userId, name, description, body, enabled));
            return ExecutionResult.text(mapper.valueToTree(saved).toString());
        }
    }

    @Component
    public static class Install implements ServerToolCallback {
        private static final int MAX_SKILL_MD_CHARS = 24_000;

        private final InternalChatFeignClient chatClient;
        private final ObjectMapper mapper;
        private final WebClient webClient;

        public Install(InternalChatFeignClient chatClient, ObjectMapper mapper, WebClient.Builder webClientBuilder) {
            this.chatClient = chatClient;
            this.mapper = mapper;
            this.webClient = webClientBuilder.build();
        }

        @Override
        public String name() {
            return "agent_skill_install";
        }

        @Override
        public String description() {
            return "Install a standard SKILL.md into this user's runtime skills. Provide either skillMarkdown "
                    + "or an HTTPS url to a raw SKILL.md. The file must contain YAML frontmatter with name and description.";
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = AgentMemoryTools.schema(mapper);
            ObjectNode props = (ObjectNode) schema.get("properties");
            props.set("skillMarkdown", AgentMemoryTools.prop(mapper, "string",
                    "Full SKILL.md content, including YAML frontmatter."));
            props.set("url", AgentMemoryTools.prop(mapper, "string",
                    "HTTPS URL to a raw SKILL.md. Use only trusted sources."));
            props.set("enabled", AgentMemoryTools.prop(mapper, "boolean",
                    "Default true. Set false to install as a disabled draft."));
            return schema;
        }

        @Override
        public ExecutionResult executeJsonToolUse(JsonNode args, UUID userId, UUID sessionId, ChatEventSink sink) {
            String markdown = text(args, "skillMarkdown");
            String url = text(args, "url");
            if (markdown.isBlank() == url.isBlank()) {
                return ExecutionResult.error("provide exactly one of skillMarkdown or url");
            }
            if (!url.isBlank()) {
                markdown = fetchSkillMarkdown(url);
            }
            ParsedSkill parsed = parseSkillMarkdown(markdown);
            Boolean enabled = args != null && args.has("enabled") ? args.path("enabled").asBoolean() : null;
            RuntimeSkillDto saved = chatClient.upsertRuntimeSkill(
                    new UpsertRuntimeSkillRequest(userId, parsed.name(), parsed.description(), parsed.body(), enabled));

            ObjectNode out = mapper.createObjectNode();
            out.put("installed", true);
            out.put("name", saved.name());
            out.put("description", saved.description());
            out.put("enabled", saved.enabled());
            out.put("bodyChars", saved.body() == null ? 0 : saved.body().length());
            return ExecutionResult.text(out.toString());
        }

        private String fetchSkillMarkdown(String url) {
            URI uri;
            try {
                uri = URI.create(url.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid url");
            }
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("url must use https");
            }
            String body = webClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(20));
            if (body == null || body.isBlank()) {
                throw new IllegalArgumentException("skill url returned empty content");
            }
            if (body.length() > MAX_SKILL_MD_CHARS) {
                throw new IllegalArgumentException("skill markdown too long; max " + MAX_SKILL_MD_CHARS + " chars");
            }
            return body;
        }

        static ParsedSkill parseSkillMarkdown(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("skillMarkdown is required");
            }
            String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
            if (normalized.length() > MAX_SKILL_MD_CHARS) {
                throw new IllegalArgumentException("skill markdown too long; max " + MAX_SKILL_MD_CHARS + " chars");
            }
            if (!normalized.startsWith("---\n")) {
                throw new IllegalArgumentException("SKILL.md must start with YAML frontmatter");
            }
            int closing = normalized.indexOf("\n---\n", 4);
            if (closing < 0) {
                throw new IllegalArgumentException("SKILL.md missing closing frontmatter delimiter");
            }
            Map<String, String> meta = parseSimpleFrontmatter(normalized.substring(4, closing));
            String name = meta.getOrDefault("name", "").trim();
            String description = meta.getOrDefault("description", "").trim();
            String body = normalized.substring(closing + "\n---\n".length()).stripLeading();
            if (name.isBlank() || description.isBlank() || body.isBlank()) {
                throw new IllegalArgumentException("SKILL.md requires name, description, and body");
            }
            if (!SKILL_NAME_PATTERN.matcher(name).matches()) {
                throw new IllegalArgumentException("skill name must match [a-zA-Z0-9_-]{1,64}");
            }
            if (description.length() > MAX_SKILL_DESCRIPTION_CHARS) {
                throw new IllegalArgumentException("skill description too long; max "
                        + MAX_SKILL_DESCRIPTION_CHARS + " chars");
            }
            if (body.length() > MAX_SKILL_BODY_CHARS) {
                throw new IllegalArgumentException("skill body too long; max " + MAX_SKILL_BODY_CHARS + " chars");
            }
            return new ParsedSkill(name, description, body);
        }

        private static Map<String, String> parseSimpleFrontmatter(String yaml) {
            Map<String, String> out = new LinkedHashMap<>();
            for (String line : yaml.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isBlank() || trimmed.startsWith("#")) continue;
                int colon = trimmed.indexOf(':');
                if (colon <= 0) continue;
                String key = trimmed.substring(0, colon).trim();
                String value = trimmed.substring(colon + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                out.put(key, value);
            }
            return out;
        }
    }

    @Component
    public static class ListSkills implements ServerToolCallback {
        private final InternalChatFeignClient chatClient;
        private final ObjectMapper mapper;

        public ListSkills(InternalChatFeignClient chatClient, ObjectMapper mapper) {
            this.chatClient = chatClient;
            this.mapper = mapper;
        }

        @Override
        public String name() {
            return "agent_skill_list";
        }

        @Override
        public String description() {
            return "List user runtime skills, including ids, names, descriptions, enabled state, and bodies.";
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = AgentMemoryTools.schema(mapper);
            ObjectNode props = (ObjectNode) schema.get("properties");
            props.set("includeDisabled", AgentMemoryTools.prop(mapper, "boolean", "When true, include disabled drafts."));
            return schema;
        }

        @Override
        public ExecutionResult executeJsonToolUse(JsonNode args, UUID userId, UUID sessionId, ChatEventSink sink) {
            boolean includeDisabled = args != null && args.path("includeDisabled").asBoolean(false);
            List<RuntimeSkillDto> rows = chatClient.listRuntimeSkills(userId, includeDisabled);
            return ExecutionResult.text(mapper.valueToTree(rows == null ? List.of() : rows).toString());
        }
    }

    @Component
    public static class Delete implements ServerToolCallback {
        private final InternalChatFeignClient chatClient;
        private final ObjectMapper mapper;

        public Delete(InternalChatFeignClient chatClient, ObjectMapper mapper) {
            this.chatClient = chatClient;
            this.mapper = mapper;
        }

        @Override
        public String name() {
            return "agent_skill_delete";
        }

        @Override
        public String description() {
            return "Delete one user runtime skill by name when it is obsolete or wrong.";
        }

        @Override
        public JsonNode schema() {
            ObjectNode schema = AgentMemoryTools.schema(mapper);
            ObjectNode props = (ObjectNode) schema.get("properties");
            props.set("name", AgentMemoryTools.prop(mapper, "string", "Runtime skill name."));
            ArrayNode required = mapper.createArrayNode();
            required.add("name");
            schema.set("required", required);
            return schema;
        }

        @Override
        public ExecutionResult executeJsonToolUse(JsonNode args, UUID userId, UUID sessionId, ChatEventSink sink) {
            String name = text(args, "name");
            if (name.isBlank()) return ExecutionResult.error("name is required");
            if (!SKILL_NAME_PATTERN.matcher(name).matches()) {
                return ExecutionResult.error("name must match [a-zA-Z0-9_-]{1,64}");
            }
            chatClient.deleteRuntimeSkill(name, userId);
            ObjectNode out = mapper.createObjectNode();
            out.put("deleted", true);
            out.put("name", name);
            return ExecutionResult.text(out.toString());
        }
    }

    private static String text(JsonNode args, String field) {
        if (args == null || !args.has(field) || args.get(field).isNull()) return "";
        return args.get(field).asText("").trim();
    }

    record ParsedSkill(String name, String description, String body) {}
}
