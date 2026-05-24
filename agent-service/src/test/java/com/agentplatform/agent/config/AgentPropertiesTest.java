package com.agentplatform.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

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
                null,
                0,
                0,
                0,
                0,
                null);

        assertThat(memory.embeddingModel()).isEqualTo("jina-embeddings-v3");
        assertThat(memory.embeddingDim()).isEqualTo(1024);
        assertThat(memory.preferLangChain4jEmbeddings()).isTrue();
    }

    @Test
    void binderUsesCanonicalAgentConstructorWhenOptionalMediaGalleryCacheIsUnset() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "agent-platform.jwt.secret", "secret",
                "agent-platform.jwt.issuer", "agent-platform",
                "agent-platform.agent.tool-call-timeout-ms", "35000",
                "agent-platform.agent.hub-base-uri", "lb://device-hub-service",
                "agent-platform.agent.providers[0].name", "primary",
                "agent-platform.agent.providers[0].base-url", "https://example.test",
                "agent-platform.agent.providers[0].api-key", "key",
                "agent-platform.agent.providers[0].model", "model",
                "agent-platform.agent.providers[0].kind", "anthropic-messages"
        )));

        AgentProperties properties = Binder.get(environment)
                .bind("agent-platform", AgentProperties.class)
                .orElseThrow(() -> new AssertionError("agent-platform properties should bind"));

        assertThat(properties.agent()).isNotNull();
        assertThat(properties.agent().memory()).isNotNull();
        assertThat(properties.agent().mediaGalleryCache()).isNotNull();
        assertThat(properties.agent().mediaGalleryCache().enabled()).isFalse();
    }
}
