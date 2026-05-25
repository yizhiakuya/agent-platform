package com.agentplatform.agent.ai;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Small best-effort LLM client for background jobs that need one text reply
 * without streaming, tools, or SSE state.
 *
 * <p>Implemented on LangChain4j's {@link ChatModel} abstraction so memory
 * extraction and session summarization stay framework-level instead of
 * hand-rolling provider HTTP. The main mobile agent loop remains custom where
 * provider-specific streaming/tool control is required.
 */
@Service
public class BackgroundLlmClient {

    @Nullable
    public CompletionPlan choosePlan(List<ConfiguredProvider> providers, @Nullable String requestedModel) {
        List<CompletionPlan> plans = candidatePlans(providers, requestedModel);
        return plans.isEmpty() ? null : plans.getFirst();
    }

    public List<CompletionPlan> candidatePlans(List<ConfiguredProvider> providers, @Nullable String requestedModel) {
        if (providers == null || providers.isEmpty()) {
            return List.of();
        }
        List<CompletionPlan> plans = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String model = trimToNull(requestedModel);
        for (ConfiguredProvider provider : providers) {
            if (isCompatible(provider, model)) {
                addPlan(plans, seen, provider, model);
            }
        }
        for (ConfiguredProvider provider : providers) {
            if (provider != null && provider.isAnthropicMessages()) {
                addPlan(plans, seen, provider, defaultModel(model, provider.model()));
            }
        }
        for (ConfiguredProvider provider : providers) {
            if (provider != null && provider.isCodexResponses()) {
                addPlan(plans, seen, provider, codexModel(model, provider.model()));
            }
        }
        return List.copyOf(plans);
    }

    public String complete(CompletionPlan plan, String prompt, long maxTokens) {
        ConfiguredProvider provider = plan.provider();
        ChatModel model = provider.backgroundChatModel();
        if (model == null) {
            throw new IllegalStateException("provider '" + provider.name()
                    + "' has no LangChain4j background ChatModel");
        }
        ChatRequest request = ChatRequest.builder()
                .modelName(plan.model())
                .maxOutputTokens(safeMaxTokens(maxTokens))
                .messages(UserMessage.from(prompt == null ? "" : prompt))
                .build();
        ChatResponse response = model.chat(request);
        if (response == null || response.aiMessage() == null) {
            return "";
        }
        String text = response.aiMessage().text();
        return text == null ? "" : text;
    }

    private boolean isCompatible(ConfiguredProvider provider, @Nullable String model) {
        if (provider == null || model == null) {
            return false;
        }
        if (provider.isAnthropicMessages()) {
            return looksAnthropic(model);
        }
        if (provider.isCodexResponses()) {
            return looksCodex(model);
        }
        return false;
    }

    private static void addPlan(List<CompletionPlan> plans,
                                Set<String> seen,
                                ConfiguredProvider provider,
                                String model) {
        if (provider == null || model == null || model.isBlank()) return;
        String key = provider.name() + "\n" + provider.kind() + "\n" + model;
        if (seen.add(key)) {
            plans.add(new CompletionPlan(provider, model));
        }
    }

    private String codexModel(@Nullable String requestedModel, @Nullable String providerModel) {
        if (requestedModel != null && !looksAnthropic(requestedModel)) {
            return requestedModel;
        }
        return defaultModel(null, providerModel);
    }

    private String defaultModel(@Nullable String requestedModel, @Nullable String providerModel) {
        String requested = trimToNull(requestedModel);
        if (requested != null) {
            return requested;
        }
        String provider = trimToNull(providerModel);
        if (provider != null) {
            return provider;
        }
        throw new IllegalStateException("background LLM provider has no model");
    }

    private static boolean looksAnthropic(String model) {
        String m = model.toLowerCase();
        return m.startsWith("claude-") || m.contains("claude");
    }

    private static boolean looksCodex(String model) {
        String m = model.toLowerCase();
        return m.startsWith("gpt-") || m.startsWith("o") || m.contains("codex");
    }

    private static String trimToNull(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Integer safeMaxTokens(long value) {
        if (value <= 0) {
            return null;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    public record CompletionPlan(ConfiguredProvider provider, String model) {}
}
