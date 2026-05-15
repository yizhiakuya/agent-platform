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
            long toolCallTimeoutMs,
            String hubBaseUri,
            int maxTokens,
            int maxAgentIterations,
            int maxToolCallsPerTurn,
            int maxConsecutiveUiToolCalls,
            List<Provider> providers,
            Memory memory,
            Photos photos
    ) {
        public Agent {
            if (maxTokens <= 0) {
                maxTokens = 4096;
            }
            if (maxAgentIterations <= 0) {
                maxAgentIterations = 24;
            }
            if (maxToolCallsPerTurn <= 0) {
                maxToolCallsPerTurn = 24;
            }
            if (maxConsecutiveUiToolCalls <= 0) {
                maxConsecutiveUiToolCalls = 10;
            }
            if (memory == null) {
                memory = new Memory(null, 0, 0, null, null, 0, null, 0, null, 0, null, null, null,
                        null, 0, 0, 0, 0, null);
            }
            if (photos == null) {
                photos = new Photos(null, null, null, 0, null, null, null, true, true, 50, 0.20d, 8, 180);
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
            int factBatchSize,
            Boolean enableVisionToolResults,
            int thinkingBudgetTokens,
            Boolean enableWebSearch,
            int webSearchMaxUses,
            String embeddingBaseUrl,
            String embeddingApiKey,
            String embeddingTask,
            Boolean preferLangChain4jEmbeddings,
            int recentHistoryMessages,
            int summaryTriggerMessages,
            int maxInputTokens,
            int summaryMaxTokens,
            Boolean enableSessionSummary
    ) {
        public Memory {
            if (embeddingModel == null || embeddingModel.isBlank()) {
                embeddingModel = "jina-embeddings-v3";
            }
            if (embeddingDim <= 0) {
                embeddingDim = 1024;
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
            // Multimodal tool_result: when a device tool returns base64
            // image bytes (e.g. photos.list_recent.thumb_b64), inject them
            // into the next LLM turn as a sibling user message with Media
            // attachments so a vision-aware Claude can actually "see" them
            // — instead of being fed the legacy <binary NB omitted> stub.
            // Default true; flip false to fall back to the strip path
            // (e.g. cost-conscious deploys or non-vision LLM endpoints).
            if (enableVisionToolResults == null) {
                enableVisionToolResults = Boolean.TRUE;
            }
            // Anthropic Extended Thinking budget. >= 1024 enables; 0 disables.
            // The model's reasoning is hidden from the user, which structurally
            // prevents the "is X… no wait, Y… actually Z" stream pattern —
            // not a prompt-level patch but an API-level mode change.
            if (thinkingBudgetTokens < 0) {
                thinkingBudgetTokens = 0;
            }
            if (enableWebSearch == null) {
                enableWebSearch = Boolean.TRUE;
            }
            if (webSearchMaxUses <= 0) {
                webSearchMaxUses = 5;
            }
            if (embeddingTask != null && embeddingTask.isBlank()) {
                embeddingTask = null;
            }
            if (preferLangChain4jEmbeddings == null) {
                preferLangChain4jEmbeddings = Boolean.TRUE;
            }
            if (recentHistoryMessages <= 0) {
                recentHistoryMessages = 12;
            }
            if (summaryTriggerMessages <= 0) {
                summaryTriggerMessages = 18;
            }
            if (maxInputTokens <= 0) {
                maxInputTokens = 48_000;
            }
            if (summaryMaxTokens <= 0) {
                summaryMaxTokens = 1_200;
            }
            if (enableSessionSummary == null) {
                enableSessionSummary = Boolean.TRUE;
            }
        }
    }

    /**
     * Server-side photo index config. The default provider fields intentionally
     * reuse the memory embedding endpoint, but production should point this at
     * a multimodal image/text embedding endpoint (for example a jina-clip-v2
     * sidecar) so text queries and image thumbnails land in the same vector
     * space.
     */
    public record Photos(
            String embeddingModel,
            String embeddingBaseUrl,
            String embeddingApiKey,
            int embeddingDim,
            String textTask,
            String imageTask,
            String inputImageField,
            Boolean enabled,
            Boolean fallbackRealtime,
            int searchTopK,
            double minScore,
            int indexBatchSize,
            int requestTimeoutSeconds
    ) {
        public Photos {
            if (embeddingModel == null || embeddingModel.isBlank()) {
                embeddingModel = "clip-ViT-B-32-multilingual-v1";
            }
            if (embeddingDim <= 0) {
                embeddingDim = 1024;
            }
            if (textTask != null && textTask.isBlank()) {
                textTask = null;
            }
            if (imageTask != null && imageTask.isBlank()) {
                imageTask = null;
            }
            if (inputImageField == null || inputImageField.isBlank()) {
                inputImageField = "image";
            }
            if (enabled == null) {
                enabled = Boolean.TRUE;
            }
            if (fallbackRealtime == null) {
                fallbackRealtime = Boolean.TRUE;
            }
            if (searchTopK <= 0) {
                searchTopK = 8;
            }
            if (minScore < 0.0d || minScore >= 1.0d) {
                minScore = 0.20d;
            }
            if (indexBatchSize <= 0) {
                indexBatchSize = 8;
            }
            if (requestTimeoutSeconds <= 0) {
                requestTimeoutSeconds = 180;
            }
        }
    }
}
