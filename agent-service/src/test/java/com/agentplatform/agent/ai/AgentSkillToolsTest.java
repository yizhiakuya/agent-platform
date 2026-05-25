package com.agentplatform.agent.ai;

import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.api.chat.RuntimeSkillDto;
import com.agentplatform.api.chat.UpsertRuntimeSkillRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentSkillToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void schemasPreserveRequiredFields() {
        InternalChatFeignClient chat = mock(InternalChatFeignClient.class);

        JsonNode upsertSchema = new AgentSkillTools.Upsert(chat, mapper).schema();
        JsonNode installSchema = new AgentSkillTools.Install(chat, mapper, WebClient.builder()).schema();
        JsonNode listSchema = new AgentSkillTools.ListSkills(chat, mapper).schema();
        JsonNode deleteSchema = new AgentSkillTools.Delete(chat, mapper).schema();

        assertThat(upsertSchema.path("required")).extracting(JsonNode::asText)
                .containsExactly("name", "description", "body");
        assertThat(installSchema.has("required")).isFalse();
        assertThat(listSchema.has("required")).isFalse();
        assertThat(deleteSchema.path("required")).extracting(JsonNode::asText)
                .containsExactly("name");
    }

    @Test
    void parseSkillMarkdownExtractsFrontmatterAndBody() {
        String raw = """
                ---
                name: deploy-flow
                description: "Deployment checklist"
                ---

                # Deploy

                Run tests, build image, push to GHCR.
                """;

        AgentSkillTools.ParsedSkill parsed = AgentSkillTools.Install.parseSkillMarkdown(raw);

        assertThat(parsed.name()).isEqualTo("deploy-flow");
        assertThat(parsed.description()).isEqualTo("Deployment checklist");
        assertThat(parsed.body()).contains("Run tests");
    }

    @Test
    void parseSkillMarkdownRejectsMissingFrontmatter() {
        assertThatThrownBy(() -> AgentSkillTools.Install.parseSkillMarkdown("# Missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frontmatter");
    }

    @Test
    void parseSkillMarkdownRejectsPayloadTooLargeForChatServiceContract() {
        String raw = """
                ---
                name: huge
                description: Too large
                ---
                """ + "x".repeat(20_001);

        assertThatThrownBy(() -> AgentSkillTools.Install.parseSkillMarkdown(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skill body too long")
                .hasMessageContaining("max 20000 chars");
    }

    @Test
    void parseSkillMarkdownRejectsInvalidNameBeforeChatServiceCall() {
        String raw = """
                ---
                name: bad name!
                description: Bad name
                ---
                # Body
                """;

        assertThatThrownBy(() -> AgentSkillTools.Install.parseSkillMarkdown(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[a-zA-Z0-9_-]");
    }

    @Test
    void parseSkillMarkdownRejectsOversizedDescriptionBeforeChatServiceCall() {
        String raw = """
                ---
                name: good_name
                description: %s
                ---
                # Body
                """.formatted("x".repeat(501));

        assertThatThrownBy(() -> AgentSkillTools.Install.parseSkillMarkdown(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description too long")
                .hasMessageContaining("max 500 chars");
    }

    @Test
    void installSkillMarkdownUpsertsRuntimeSkill() {
        InternalChatFeignClient chat = mock(InternalChatFeignClient.class);
        UUID userId = UUID.randomUUID();
        RuntimeSkillDto saved = new RuntimeSkillDto(
                UUID.randomUUID(),
                userId,
                "deploy-flow",
                "Deployment checklist",
                "# Deploy",
                true,
                OffsetDateTime.now(),
                OffsetDateTime.now());
        when(chat.upsertRuntimeSkill(any())).thenReturn(saved);
        AgentSkillTools.Install tool = new AgentSkillTools.Install(chat, mapper, WebClient.builder());
        ObjectNode args = mapper.createObjectNode();
        args.put("skillMarkdown", """
                ---
                name: deploy-flow
                description: Deployment checklist
                ---
                # Deploy
                """);

        ExecutionResult result = tool.executeJsonToolUse(args, userId, UUID.randomUUID(), null);

        assertThat(result.jsonText()).contains("\"installed\":true");
        ArgumentCaptor<UpsertRuntimeSkillRequest> captor = ArgumentCaptor.forClass(UpsertRuntimeSkillRequest.class);
        verify(chat).upsertRuntimeSkill(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(userId);
        assertThat(captor.getValue().name()).isEqualTo("deploy-flow");
        assertThat(captor.getValue().description()).isEqualTo("Deployment checklist");
        assertThat(captor.getValue().body()).contains("# Deploy");
    }

    @Test
    void upsertRejectsInvalidNameAndOversizedBodyBeforeCallingChatService() {
        InternalChatFeignClient chat = mock(InternalChatFeignClient.class);
        AgentSkillTools.Upsert tool = new AgentSkillTools.Upsert(chat, mapper);
        ObjectNode badName = mapper.createObjectNode()
                .put("name", "bad name!")
                .put("description", "desc")
                .put("body", "body");

        ExecutionResult invalidName = tool.executeJsonToolUse(
                badName,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null);

        assertThat(invalidName.jsonText()).contains("[a-zA-Z0-9_-]");

        ObjectNode tooLarge = mapper.createObjectNode()
                .put("name", "good_name")
                .put("description", "desc")
                .put("body", "x".repeat(20_001));

        ExecutionResult oversized = tool.executeJsonToolUse(
                tooLarge,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null);

        assertThat(oversized.jsonText()).contains("body too long");
        verifyNoInteractions(chat);
    }

    @Test
    void upsertRejectsOversizedDescriptionBeforeCallingChatService() {
        InternalChatFeignClient chat = mock(InternalChatFeignClient.class);
        AgentSkillTools.Upsert tool = new AgentSkillTools.Upsert(chat, mapper);
        ObjectNode args = mapper.createObjectNode()
                .put("name", "good_name")
                .put("description", "x".repeat(501))
                .put("body", "body");

        ExecutionResult result = tool.executeJsonToolUse(
                args,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null);

        assertThat(result.jsonText()).contains("description too long");
        verifyNoInteractions(chat);
    }

    @Test
    void deleteRejectsInvalidNameBeforeCallingChatService() {
        InternalChatFeignClient chat = mock(InternalChatFeignClient.class);
        AgentSkillTools.Delete tool = new AgentSkillTools.Delete(chat, mapper);
        ObjectNode args = mapper.createObjectNode().put("name", "bad name!");

        ExecutionResult result = tool.executeJsonToolUse(
                args,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null);

        assertThat(result.jsonText()).contains("[a-zA-Z0-9_-]");
        verifyNoInteractions(chat);
    }
}
