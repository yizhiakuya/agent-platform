package com.agentplatform.agent.ai;

import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.api.chat.SaveFactRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentMemoryToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();

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
