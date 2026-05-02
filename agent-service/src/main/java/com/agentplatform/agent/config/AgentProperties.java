package com.agentplatform.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Top-level config bound from {@code agent-platform.*}.
 * Nested records make Spring binding clean while keeping a single property file.
 */
@ConfigurationProperties(prefix = "agent-platform")
public record AgentProperties(
        Jwt jwt,
        Agent agent
) {
    public record Jwt(String secret, String issuer) {}

    /**
     * Agent runtime config.
     *
     * <p>{@code providers} is the LLM provider pool (try in order, fail over to next).
     * Empty/null means "fall back to single auto-configured ChatClient".
     *
     * <p>{@code memory} controls the long-term-memory recall + fact-extraction pipeline.
     */
    public record Agent(
            String mockToolName,
            String mockToolArgsJson,
            long toolCallTimeoutMs,
            String hubBaseUri,
            List<Provider> providers,
            Memory memory
    ) {
        public Agent {
            if (memory == null) {
                memory = new Memory(null, 0, 0, null);
            }
        }
    }

    /**
     * One LLM provider definition. {@code kind} chooses the wire format
     * (currently only {@code anthropic-messages} is wired in B1; more later).
     */
    public record Provider(
            String name,
            String baseUrl,
            String apiKey,
            String model,
            String kind
    ) {}

    /**
     * Memory pipeline config. Defaults applied here so unset properties are safe.
     */
    public record Memory(
            String embeddingModel,
            int embeddingDim,
            int topK,
            String factExtractorModel
    ) {
        public Memory {
            if (embeddingModel == null || embeddingModel.isBlank()) {
                embeddingModel = "text-embedding-3-small";
            }
            if (embeddingDim <= 0) {
                embeddingDim = 1536;
            }
            if (topK <= 0) {
                topK = 5;
            }
            if (factExtractorModel == null || factExtractorModel.isBlank()) {
                factExtractorModel = "claude-haiku-4-5";
            }
        }
    }
}
