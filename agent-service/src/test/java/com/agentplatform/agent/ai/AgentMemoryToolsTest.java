package com.agentplatform.agent.ai;

import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.api.chat.MemoryFactDto;
import com.agentplatform.api.chat.SaveFactRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentMemoryToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void schemasPreserveRequiredFields() {
        InternalChatFeignClient chat = mock(InternalChatFeignClient.class);
        EmbeddingService embedding = mock(EmbeddingService.class);

        JsonNode addSchema = new AgentMemoryTools.Add(chat, embedding, mapper).schema();
        JsonNode listSchema = new AgentMemoryTools.ListMemories(chat, mapper).schema();
        JsonNode forgetSchema = new AgentMemoryTools.Forget(chat, mapper).schema();

        assertThat(addSchema.path("required")).extracting(JsonNode::asText)
                .containsExactly("kind", "content");
        assertThat(listSchema.has("required")).isFalse();
        assertThat(forgetSchema.path("required")).extracting(JsonNode::asText)
                .containsExactly("id");
    }

    @Test
    void addTrimsAndPersistsValidMemory() {
        InternalChatFeignClient chat = mock(InternalChatFeignClient.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        UUID id = UUID.randomUUID();
        when(embedding.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        when(chat.saveFact(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of("id", id));
        AgentMemoryTools.Add tool = new AgentMemoryTools.Add(chat, embedding, mapper);
        UUID userId = UUID.randomUUID();
        ObjectNode args = mapper.createObjectNode()
                .put("kind", " preference ")
                .put("content", "  likes concise updates  ");

        ExecutionResult result = tool.executeJsonToolUse(args, userId, UUID.randomUUID(), null);

        assertThat(result.jsonText()).contains("\"ok\":true");
        ArgumentCaptor<SaveFactRequest> captor = ArgumentCaptor.forClass(SaveFactRequest.class);
        verify(chat).saveFact(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(userId);
        assertThat(captor.getValue().kind()).isEqualTo("preference");
        assertThat(captor.getValue().content()).isEqualTo("likes concise updates");
        assertThat(captor.getValue().curated()).isTrue();
    }

    @Test
    void addSkipsDuplicateMemoryBeforeEmbedding() {
        InternalChatFeignClient chat = mock(InternalChatFeignClient.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        UUID userId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        when(chat.listFacts(org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of(new MemoryFactDto(
                existingId,
                userId,
                "preference",
                "处理相册清理时必须先展示候选图片给用户核对。",
                null,
                OffsetDateTime.now(),
                true,
                0,
                OffsetDateTime.now()
        )));
        AgentMemoryTools.Add tool = new AgentMemoryTools.Add(chat, embedding, mapper);
        ObjectNode args = mapper.createObjectNode()
                .put("kind", "preference")
                .put("content", "相册清理时，必须先展示候选图片。");

        ExecutionResult result = tool.executeJsonToolUse(args, userId, UUID.randomUUID(), null);

        assertThat(result.jsonText()).contains("\"duplicate\":true");
        assertThat(result.jsonText()).contains(existingId.toString());
        verify(embedding, never()).embed(org.mockito.ArgumentMatchers.anyString());
        verify(chat, never()).saveFact(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void addRejectsInvalidKindAndOversizedContentBeforeEmbedding() {
        InternalChatFeignClient chat = mock(InternalChatFeignClient.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        AgentMemoryTools.Add tool = new AgentMemoryTools.Add(chat, embedding, mapper);
        ObjectNode badKind = mapper.createObjectNode()
                .put("kind", "secret")
                .put("content", "remember this");

        ExecutionResult invalidKind = tool.executeJsonToolUse(
                badKind,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null);

        assertThat(invalidKind.jsonText()).contains("kind must be one of");

        ObjectNode tooLarge = mapper.createObjectNode()
                .put("kind", "fact")
                .put("content", "x".repeat(4_001));

        ExecutionResult oversized = tool.executeJsonToolUse(
                tooLarge,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null);

        assertThat(oversized.jsonText()).contains("content too long");
        verifyNoInteractions(chat, embedding);
    }

    @Test
    void forgetRejectsInvalidUuidWithoutCallingChatService() {
        InternalChatFeignClient chat = mock(InternalChatFeignClient.class);
        AgentMemoryTools.Forget tool = new AgentMemoryTools.Forget(chat, mapper);
        ObjectNode args = mapper.createObjectNode().put("id", "not-a-uuid");

        ExecutionResult result = tool.executeJsonToolUse(args, UUID.randomUUID(), UUID.randomUUID(), null);

        assertThat(result.jsonText()).contains("\"error\":\"id must be a valid UUID\"");
        verifyNoInteractions(chat);
    }
}
