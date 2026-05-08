package com.agentplatform.agent.ai;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

/**
 * Small best-effort LLM client for background jobs that need one text reply
 * without streaming, tools, or SSE state.
 */
@Service
public class BackgroundLlmClient {

    private final ObjectMapper mapper;
    private final WebClient.Builder webClientBuilder;

    public BackgroundLlmClient(ObjectMapper mapper, WebClient.Builder defaultWebClientBuilder) {
        this.mapper = mapper;
        this.webClientBuilder = defaultWebClientBuilder;
    }

    @Nullable
    public CompletionPlan choosePlan(List<ConfiguredProvider> providers, @Nullable String requestedModel) {
        if (providers == null || providers.isEmpty()) {
            return null;
        }
        String model = trimToNull(requestedModel);
        for (ConfiguredProvider provider : providers) {
            if (isCompatible(provider, model)) {
                return new CompletionPlan(provider, model);
            }
        }
        for (ConfiguredProvider provider : providers) {
            if (provider != null && provider.isAnthropicMessages()) {
                return new CompletionPlan(provider, defaultModel(model, provider.model()));
            }
        }
        for (ConfiguredProvider provider : providers) {
            if (provider != null && provider.isCodexResponses()) {
                return new CompletionPlan(provider, codexModel(model, provider.model()));
            }
        }
        return null;
    }

    public String complete(CompletionPlan plan, String prompt, long maxTokens) {
        ConfiguredProvider provider = plan.provider();
        if (provider.isAnthropicMessages()) {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(plan.model())
                    .maxTokens(maxTokens)
                    .addUserMessage(prompt)
                    .build();
            Message msg = provider.client().messages().create(params);
            StringBuilder sb = new StringBuilder();
            for (ContentBlock cb : msg.content()) {
                cb.text().ifPresent(t -> sb.append(t.text()));
            }
            return sb.toString();
        }
        if (provider.isCodexResponses()) {
            return completeResponses(provider, plan.model(), prompt, maxTokens);
        }
        throw new IllegalArgumentException("unsupported background LLM provider kind: " + provider.kind());
    }

    private String completeResponses(ConfiguredProvider provider, String model, String prompt, long maxTokens) {
        WebClient client = webClientBuilder.clone()
                .baseUrl(stripTrailingSlash(provider.baseUrl()))
                .build();

        ObjectNode request = mapper.createObjectNode();
        request.put("model", model);
        request.put("max_output_tokens", maxTokens);
        ArrayNode input = mapper.createArrayNode();
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        msg.put("content", prompt == null ? "" : prompt);
        input.add(msg);
        request.set("input", input);

        JsonNode resp = client.post()
                .uri("/v1/responses")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofMinutes(5));
        if (resp == null) {
            throw new IllegalStateException("empty Codex Responses API response");
        }
        return extractOutputText(resp);
    }

    private String extractOutputText(JsonNode resp) {
        String direct = resp.path("output_text").asText("");
        if (!direct.isBlank()) {
            return direct;
        }
        StringBuilder out = new StringBuilder();
        JsonNode output = resp.path("output");
        if (output.isArray()) {
            for (JsonNode item : output) {
                JsonNode content = item.path("content");
                if (!content.isArray()) continue;
                for (JsonNode block : content) {
                    String type = block.path("type").asText("");
                    if ("output_text".equals(type) || "text".equals(type)) {
                        out.append(block.path("text").asText(""));
                    }
                }
            }
        }
        return out.toString();
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

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) return "https://api.openai.com";
        String out = url.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    public record CompletionPlan(ConfiguredProvider provider, String model) {}
}
