package com.agentplatform.agent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPropertiesTest {

    @Test
    void memoryDefaultsMatchConfiguredEmbeddingProvider() {
        AgentProperties.Memory memory = new AgentProperties.Memory(
                null,
                0,
                0,
                null,
                null,
                0,
                null,
                0,
                null,
                0,
                null,
                null,
                null,
                0,
                0,
                0,
                0,
                null);

        assertThat(memory.embeddingModel()).isEqualTo("jina-embeddings-v3");
        assertThat(memory.embeddingDim()).isEqualTo(1024);
    }
}
