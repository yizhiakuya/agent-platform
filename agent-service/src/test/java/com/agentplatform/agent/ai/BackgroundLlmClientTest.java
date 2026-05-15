package com.agentplatform.agent.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackgroundLlmClientTest {

    private final BackgroundLlmClient client = new BackgroundLlmClient();

    @Test
    void choosePlanKeepsClaudeExtractorOnAnthropicWhenAvailable() {
        ConfiguredProvider codex = provider("codex", "codex-responses", "gpt-5.5");
        ConfiguredProvider anthropic = provider("anthropic", "anthropic-messages", "claude-sonnet-4-6");

        BackgroundLlmClient.CompletionPlan plan = client.choosePlan(
                List.of(codex, anthropic),
                "claude-haiku-4-5");

        assertThat(plan).isNotNull();
        assertThat(plan.provider()).isSameAs(anthropic);
        assertThat(plan.model()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    void choosePlanFallsBackToCodexProviderModelForClaudeExtractorWhenCodexOnly() {
        ConfiguredProvider codex = provider("codex", "codex-responses", "gpt-5.5");

        BackgroundLlmClient.CompletionPlan plan = client.choosePlan(
                List.of(codex),
                "claude-haiku-4-5");

        assertThat(plan).isNotNull();
        assertThat(plan.provider()).isSameAs(codex);
        assertThat(plan.model()).isEqualTo("gpt-5.5");
    }

    @Test
    void candidatePlansIncludeFallbackProvidersInOrderWithoutDuplicates() {
        ConfiguredProvider anthropic = provider("anthropic", "anthropic-messages", "claude-sonnet-4-6");
        ConfiguredProvider codex = provider("codex", "codex-responses", "gpt-5.5");

        List<BackgroundLlmClient.CompletionPlan> plans = client.candidatePlans(
                List.of(anthropic, codex),
                "claude-haiku-4-5");

        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).provider()).isSameAs(anthropic);
        assertThat(plans.get(0).model()).isEqualTo("claude-haiku-4-5");
        assertThat(plans.get(1).provider()).isSameAs(codex);
        assertThat(plans.get(1).model()).isEqualTo("gpt-5.5");
    }

    @Test
    void completeDelegatesToLangChain4jChatModel() {
        RecordingChatModel model = new RecordingChatModel("[]");
        ConfiguredProvider codex = new ConfiguredProvider(
                "codex",
                "codex-responses",
                null,
                model,
                "http://127.0.0.1",
                "token",
                "gpt-5.5");

        String text = client.complete(new BackgroundLlmClient.CompletionPlan(codex, "gpt-5.5"),
                "extract facts", 128L);

        assertThat(text).isEqualTo("[]");
        assertThat(model.requests()).hasSize(1);
        ChatRequest request = model.requests().getFirst();
        assertThat(request.modelName()).isEqualTo("gpt-5.5");
        assertThat(request.maxOutputTokens()).isEqualTo(128);
        assertThat(request.messages()).hasSize(1);
        assertThat(request.messages().getFirst().toString()).contains("extract facts");
    }

    @Test
    void completeHandlesNullResponseAsBlank() {
        RecordingChatModel model = RecordingChatModel.nullResponse();
        ConfiguredProvider codex = new ConfiguredProvider(
                "codex",
                "codex-responses",
                null,
                model,
                "http://127.0.0.1",
                "token",
                "gpt-5.5");

        String text = client.complete(new BackgroundLlmClient.CompletionPlan(codex, "gpt-5.5"),
                "summarize", 64L);

        assertThat(text).isEqualTo("");
    }

    private static ConfiguredProvider provider(String name, String kind, String model) {
        return new ConfiguredProvider(
                name,
                kind,
                null,
                new RecordingChatModel("ok"),
                "http://127.0.0.1",
                "token",
                model);
    }

    private static final class RecordingChatModel implements ChatModel {
        private final String reply;
        private final boolean nullResponse;
        private final List<ChatRequest> requests = new ArrayList<>();

        private RecordingChatModel(String reply) {
            this.reply = reply;
            this.nullResponse = false;
        }

        private RecordingChatModel() {
            this.reply = "";
            this.nullResponse = true;
        }

        private static RecordingChatModel nullResponse() {
            return new RecordingChatModel();
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            if (nullResponse) {
                return null;
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(reply))
                    .build();
        }

        private List<ChatRequest> requests() {
            return requests;
        }
    }
}
