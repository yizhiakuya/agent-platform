package com.agentplatform.agent.ai;

import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.api.chat.MemoryFactDto;
import com.agentplatform.api.chat.SaveFactRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AgentMemoryTools {

    private static final int MAX_MEMORY_CONTENT_CHARS = 4_000;
    private static final int DEDUPE_SCAN_LIMIT = 100;
    private static final String CONTENT_FIELD = "content";
    private static final String INCLUDE_RAW_FIELD = "includeRaw";
    private static final String LIMIT_FIELD = "limit";
    private static final String STRING_TYPE = "string";

    private AgentMemoryTools() {}

    @Component
    public static class Add extends JsonToolSupport implements ServerToolCallback {
        private final EmbeddingService embeddingService;

        public Add(InternalChatFeignClient chatClient, EmbeddingService embeddingService, ObjectMapper mapper) {
            super(chatClient, mapper);
            this.embeddingService = embeddingService;
        }

        @Override
        public String name() {
            return "agent_memory_add";
        }

        @Override
        public String description() {
            return "Persist one durable memory about the user or project. Use only when the user explicitly asks you "
                    + "to remember a stable preference/rule/fact/lesson, or when a correction clearly changes future "
                    + "behavior. Do not save ordinary confirmations, transient task state, duplicate memories, "
                    + "conversation summaries, or secrets.";
        }

        @Override
        public JsonNode schema() {
            return schema(props -> {
                props.set("kind", prop(STRING_TYPE, "One of fact, preference, rule, lesson."));
                props.set(CONTENT_FIELD, prop(STRING_TYPE, "Concise durable memory text. Avoid secrets and transient details."));
            }, "kind", CONTENT_FIELD);
        }

        @Override
        public ExecutionResult executeJsonToolUse(JsonNode args, UUID userId, UUID sessionId, ChatEventSink sink) {
            String kind = normalizeKind(text(args, "kind", "fact"));
            if (kind == null) return ExecutionResult.error("kind must be one of fact, preference, rule, lesson");
            String content = text(args, CONTENT_FIELD, "").trim();
            if (content.isBlank()) return ExecutionResult.error("content is required");
            if (content.length() > MAX_MEMORY_CONTENT_CHARS) {
                return ExecutionResult.error("content too long; max " + MAX_MEMORY_CONTENT_CHARS + " chars");
            }
            MemoryFactDto duplicate = findDuplicate(userId, content);
            if (duplicate != null) {
                ObjectNode out = mapper.createObjectNode();
                out.put("ok", true);
                out.put("duplicate", true);
                out.put("id", String.valueOf(duplicate.id()));
                out.put("kind", duplicate.kind());
                out.put(CONTENT_FIELD, duplicate.content());
                out.put("message", "A matching memory already exists; no new memory was saved.");
                return ExecutionResult.text(out.toString());
            }
            float[] embedding = embeddingService.embed(content);
            Map<String, UUID> saved = chatClient.saveFact(
                    new SaveFactRequest(userId, kind, content, null, embedding, true));
            ObjectNode out = mapper.createObjectNode();
            out.put("ok", true);
            out.put("id", String.valueOf(saved.get("id")));
            out.put("kind", kind);
            out.put(CONTENT_FIELD, content);
            return ExecutionResult.text(out.toString());
        }

        private MemoryFactDto findDuplicate(UUID userId, String content) {
            List<MemoryFactDto> rows = chatClient.listFacts(Map.of(
                    "userId", userId.toString(),
                    LIMIT_FIELD, DEDUPE_SCAN_LIMIT,
                    INCLUDE_RAW_FIELD, true));
            if (rows == null || rows.isEmpty()) return null;
            String normalizedContent = normalizeForDuplicate(content);
            for (MemoryFactDto row : rows) {
                if (row == null || row.content() == null || row.content().isBlank()) continue;
                String existing = normalizeForDuplicate(row.content());
                if (existing.equals(normalizedContent)
                        || isLongContainmentDuplicate(existing, normalizedContent)) {
                    return row;
                }
            }
            return null;
        }

        private static String normalizeKind(String kind) {
            if (kind == null || kind.isBlank()) return "fact";
            return switch (kind.trim().toLowerCase()) {
                case "fact", "preference", "rule", "lesson" -> kind.trim().toLowerCase();
                default -> null;
            };
        }

        private static String normalizeForDuplicate(String value) {
            if (value == null) return "";
            StringBuilder sb = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = Character.toLowerCase(value.charAt(i));
                if (Character.isLetterOrDigit(c)) {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private static boolean isLongContainmentDuplicate(String existing, String incoming) {
            int shorter = Math.min(existing.length(), incoming.length());
            if (shorter < 12) return false;
            return existing.contains(incoming) || incoming.contains(existing);
        }
    }

    @Component
    public static class ListMemories extends JsonToolSupport implements ServerToolCallback {

        public ListMemories(InternalChatFeignClient chatClient, ObjectMapper mapper) {
            super(chatClient, mapper);
        }

        @Override
        public String name() {
            return "agent_memory_list";
        }

        @Override
        public String description() {
            return "List stored durable memories for review before updating or deleting them.";
        }

        @Override
        public JsonNode schema() {
            return schema(props -> {
                props.set(LIMIT_FIELD, prop("integer", "Maximum rows to return, default 20, max 100."));
                props.set(INCLUDE_RAW_FIELD, prop("boolean", "When true, include automatically extracted raw memories too."));
            });
        }

        @Override
        public ExecutionResult executeJsonToolUse(JsonNode args, UUID userId, UUID sessionId, ChatEventSink sink) {
            int limit = args == null ? 20 : Math.max(1, Math.min(args.path(LIMIT_FIELD).asInt(20), 100));
            boolean includeRaw = args != null && args.path(INCLUDE_RAW_FIELD).asBoolean(false);
            List<MemoryFactDto> rows = chatClient.listFacts(Map.of(
                    "userId", userId.toString(),
                    LIMIT_FIELD, limit,
                    INCLUDE_RAW_FIELD, includeRaw));
            return ExecutionResult.text(mapper.valueToTree(rows == null ? List.of() : rows).toString());
        }
    }

    @Component
    public static class Forget extends JsonToolSupport implements ServerToolCallback {

        public Forget(InternalChatFeignClient chatClient, ObjectMapper mapper) {
            super(chatClient, mapper);
        }

        @Override
        public String name() {
            return "agent_memory_forget";
        }

        @Override
        public String description() {
            return "Delete one stored memory by id after it is obsolete, wrong, or explicitly no longer wanted.";
        }

        @Override
        public JsonNode schema() {
            return schema(props -> props.set("id", prop(STRING_TYPE, "Memory fact UUID from agent_memory_list.")),
                    "id");
        }

        @Override
        public ExecutionResult executeJsonToolUse(JsonNode args, UUID userId, UUID sessionId, ChatEventSink sink) {
            String id = text(args, "id", "");
            if (id.isBlank()) return ExecutionResult.error("id is required");
            UUID factId;
            try {
                factId = UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                return ExecutionResult.error("id must be a valid UUID");
            }
            Map<String, Boolean> deleted = chatClient.deleteFact(userId, factId);
            ObjectNode out = mapper.createObjectNode();
            out.put("deleted", Boolean.TRUE.equals(deleted.get("deleted")));
            out.put("id", id);
            return ExecutionResult.text(out.toString());
        }
    }
}
