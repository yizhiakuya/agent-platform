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
                memory = new Memory(null, 0, 0, null, null, 0);
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
     *
     * <p>{@code enablePromptCache} toggles the Anthropic prompt-cache optimization.
     * When true, the stable head of the system prompt (persona + user prefs +
     * skill index) gets a {@code cache_control: ephemeral} breakpoint so repeat
     * requests inside the 5-minute TTL pay the cache-read price (10% of input)
     * instead of full input tokens. Default true; flip off via env if a provider
     * proxy doesn't pass cache_control fields through cleanly.
     */
    public record Memory(
            String embeddingModel,
            int embeddingDim,
            int topK,
            String factExtractorModel,
            Boolean enablePromptCache,
            int factBatchSize
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
            if (enablePromptCache == null) {
                enablePromptCache = Boolean.TRUE;
            }
            // 1 = no batching (legacy behavior). 3 = sweet spot for typical
            // multi-turn chats — most sessions hit it, sub2api request count
            // drops ~60%, with at most 2 dropped turns per session if the
            // user walks away mid-batch (memory is best-effort anyway).
            if (factBatchSize <= 0) {
                factBatchSize = 3;
            }
        }
    }
}
