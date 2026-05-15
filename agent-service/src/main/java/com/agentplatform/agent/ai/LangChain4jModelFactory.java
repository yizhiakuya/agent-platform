package com.agentplatform.agent.ai;

import com.agentplatform.agent.config.AgentProperties;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Central LangChain4j model builder for framework-managed, non-agent-loop LLM calls.
 *
 * <p>See https://langchain4j.cn/tutorials/spring-boot-integration.html for the
 * Spring Boot integration style. The main mobile agent loop still keeps its
 * custom Responses SSE runner because it needs provider-specific event replay,
 * Android tool budgets, and frontend SSE mapping.
 */
@Component
public class LangChain4jModelFactory {

    public ChatModel backgroundChatModel(AgentProperties.Provider provider,
                                         String kind,
                                         String baseUrl,
                                         String model) {
        if ("anthropic-messages".equals(kind)) {
            return AnthropicChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(provider.apiKey())
                    .modelName(model)
                    .maxTokens(1024)
                    .timeout(Duration.ofMinutes(5))
                    .maxRetries(0)
                    .build();
        }
        if ("codex-responses".equals(kind)) {
            return OpenAiResponsesChatModel.builder()
                    .baseUrl(ensureOpenAiV1BaseUrl(baseUrl))
                    .apiKey(provider.apiKey())
                    .modelName(model)
                    .maxOutputTokens(1024)
                    .store(false)
                    .parallelToolCalls(false)
                    .build();
        }
        throw new IllegalArgumentException("unsupported LangChain4j provider kind: " + kind);
    }

    public static String ensureOpenAiV1BaseUrl(@Nullable String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.openai.com/v1";
        }
        String out = stripTrailingSlash(baseUrl);
        return out.endsWith("/v1") ? out : out + "/v1";
    }

    public static String stripTrailingSlash(String url) {
        String out = url == null ? "" : url.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
