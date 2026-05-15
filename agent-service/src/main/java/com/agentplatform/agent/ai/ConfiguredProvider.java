package com.agentplatform.agent.ai;

import com.anthropic.client.AnthropicClient;
import dev.langchain4j.model.chat.ChatModel;

/** Configured LLM provider: wire kind + client details + model ID. */
public record ConfiguredProvider(
        String name,
        String kind,
        AnthropicClient client,
        ChatModel backgroundChatModel,
        String baseUrl,
        String apiKey,
        String model
) {
    public ConfiguredProvider(String name,
                              String kind,
                              AnthropicClient client,
                              String baseUrl,
                              String apiKey,
                              String model) {
        this(name, kind, client, null, baseUrl, apiKey, model);
    }

    public boolean isAnthropicMessages() {
        return "anthropic-messages".equals(kind);
    }

    public boolean isCodexResponses() {
        return "codex-responses".equals(kind);
    }
}
