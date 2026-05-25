package com.agentplatform.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
            Photos photos,
            MediaGalleryCache mediaGalleryCache
    ) {
        @ConstructorBinding
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
                photos = new Photos(null, null, null, 0, null, null, null, true, true, false, 50, 0.20d, 8, 180);
            }
            if (mediaGalleryCache == null) {
                mediaGalleryCache = new MediaGalleryCache(null, null, 0, null, null, 0);
            }
        }

        public Agent(long toolCallTimeoutMs,
                     String hubBaseUri,
                     int maxTokens,
                     int maxAgentIterations,
                     int maxToolCallsPerTurn,
                     int maxConsecutiveUiToolCalls,
                     List<Provider> providers,
                     Memory memory,
                     Photos photos) {
            this(toolCallTimeoutMs, hubBaseUri, maxTokens, maxAgentIterations, maxToolCallsPerTurn,
                    maxConsecutiveUiToolCalls, providers, memory, photos, null);
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
            embeddingModel = defaultText(embeddingModel, "jina-embeddings-v3");
            embeddingDim = defaultPositive(embeddingDim, 1024);
            topK = defaultPositive(topK, 5);
            factExtractorModel = defaultText(factExtractorModel, "claude-haiku-4-5");
            enablePromptCache = defaultBoolean(enablePromptCache, true);
            // 1 = no batching (legacy behavior). 3 = sweet spot for typical
            // multi-turn chats — most sessions hit it, sub2api request count
            // drops ~60%, with at most 2 dropped turns per session if the
            // user walks away mid-batch (memory is best-effort anyway).
            factBatchSize = defaultPositive(factBatchSize, 3);
            // Multimodal tool_result: when a device tool returns base64
            // image bytes (e.g. photos.list_recent.thumb_b64), inject them
            // into the next LLM turn as a sibling user message with Media
            // attachments so a vision-aware Claude can actually "see" them
            // — instead of being fed the legacy <binary NB omitted> stub.
            // Default true; flip false to fall back to the strip path
            // (e.g. cost-conscious deploys or non-vision LLM endpoints).
            enableVisionToolResults = defaultBoolean(enableVisionToolResults, true);
            // Anthropic Extended Thinking budget. >= 1024 enables; 0 disables.
            // The model's reasoning is hidden from the user, which structurally
            // prevents the "is X… no wait, Y… actually Z" stream pattern —
            // not a prompt-level patch but an API-level mode change.
            thinkingBudgetTokens = Math.max(0, thinkingBudgetTokens);
            enableWebSearch = defaultBoolean(enableWebSearch, true);
            webSearchMaxUses = defaultPositive(webSearchMaxUses, 5);
            embeddingTask = blankToNull(embeddingTask);
            preferLangChain4jEmbeddings = defaultBoolean(preferLangChain4jEmbeddings, true);
            recentHistoryMessages = defaultPositive(recentHistoryMessages, 12);
            summaryTriggerMessages = defaultPositive(summaryTriggerMessages, 18);
            maxInputTokens = defaultPositive(maxInputTokens, 48_000);
            summaryMaxTokens = defaultPositive(summaryMaxTokens, 1_200);
            enableSessionSummary = defaultBoolean(enableSessionSummary, true);
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
            Boolean indexWorkerEnabled,
            int searchTopK,
            double minScore,
            int indexBatchSize,
            int requestTimeoutSeconds
    ) {
        public Photos {
            embeddingModel = defaultText(embeddingModel, "clip-ViT-B-32-multilingual-v1");
            embeddingDim = defaultPositive(embeddingDim, 1024);
            textTask = blankToNull(textTask);
            imageTask = blankToNull(imageTask);
            inputImageField = defaultText(inputImageField, "image");
            enabled = defaultBoolean(enabled, true);
            fallbackRealtime = defaultBoolean(fallbackRealtime, true);
            indexWorkerEnabled = defaultBoolean(indexWorkerEnabled, false);
            searchTopK = defaultPositive(searchTopK, 8);
            minScore = minScore < 0.0d || minScore >= 1.0d ? 0.20d : minScore;
            indexBatchSize = defaultPositive(indexBatchSize, 8);
            requestTimeoutSeconds = defaultPositive(requestTimeoutSeconds, 180);
        }
    }

    private static String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String blankToNull(String value) {
        return value != null && value.isBlank() ? null : value;
    }

    private static int defaultPositive(int value, int defaultValue) {
        return value <= 0 ? defaultValue : value;
    }

    private static Boolean defaultBoolean(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    public record MediaGalleryCache(
            Boolean enabled,
            String redisHost,
            int redisPort,
            String redisUsername,
            String redisPassword,
            int redisDatabase
    ) {
        public MediaGalleryCache {
            if (enabled == null) {
                enabled = Boolean.FALSE;
            }
            if (redisHost == null || redisHost.isBlank()) {
                redisHost = "localhost";
            }
            if (redisPort <= 0) {
                redisPort = 6379;
            }
            if (redisUsername != null && redisUsername.isBlank()) {
                redisUsername = null;
            }
            if (redisPassword != null && redisPassword.isBlank()) {
                redisPassword = null;
            }
            if (redisDatabase < 0) {
                redisDatabase = 0;
            }
        }
    }
}
