package com.agentplatform.agent.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingBackgroundChatModelTest {

    @Test
    void routesPreferredClaudeModelToAnthropicProvider() {
        RecordingChatModel codexModel = new RecordingChatModel("codex");
        RecordingChatModel anthropicModel = new RecordingChatModel("anthropic");
        ConfiguredProvider codex = provider("codex", "codex-responses", codexModel, "gpt-5.5");
        ConfiguredProvider anthropic = provider("anthropic", "anthropic-messages", anthropicModel, "claude-sonnet-4-6");
        RoutingBackgroundChatModel router = new RoutingBackgroundChatModel(
                List.of(codex, anthropic),
                new BackgroundLlmClient(),
                () -> "claude-haiku-4-5",
                1024);

        ChatResponse response = router.chat(ChatRequest.builder()
                .messages(UserMessage.from("summarize"))
                .build());

        assertThat(response.aiMessage().text()).isEqualTo("anthropic");
        assertThat(anthropicModel.requests()).hasSize(1);
        assertThat(anthropicModel.requests().getFirst().modelName()).isEqualTo("claude-haiku-4-5");
        assertThat(anthropicModel.requests().getFirst().maxOutputTokens()).isEqualTo(1024);
        assertThat(codexModel.requests()).isEmpty();
    }

    @Test
    void fallsBackToCodexWhenAnthropicProviderFails() {
        RecordingChatModel anthropicModel = RecordingChatModel.failing();
        RecordingChatModel codexModel = new RecordingChatModel("codex");
        ConfiguredProvider anthropic = provider("anthropic", "anthropic-messages", anthropicModel, "claude-sonnet-4-6");
        ConfiguredProvider codex = provider("codex", "codex-responses", codexModel, "gpt-5.5");
        RoutingBackgroundChatModel router = new RoutingBackgroundChatModel(
                List.of(anthropic, codex),
                new BackgroundLlmClient(),
                () -> "claude-haiku-4-5",
                512);

        ChatResponse response = router.chat(ChatRequest.builder()
                .messages(UserMessage.from("extract"))
                .maxOutputTokens(256)
                .build());

        assertThat(response.aiMessage().text()).isEqualTo("codex");
        assertThat(anthropicModel.requests()).hasSize(1);
        assertThat(codexModel.requests()).hasSize(1);
        assertThat(codexModel.requests().getFirst().modelName()).isEqualTo("gpt-5.5");
        assertThat(codexModel.requests().getFirst().maxOutputTokens()).isEqualTo(256);
    }

    private static ConfiguredProvider provider(String name,
                                               String kind,
                                               ChatModel model,
                                               String providerModel) {
        return new ConfiguredProvider(
                name,
                kind,
                null,
                model,
                "http://127.0.0.1",
                "token",
                providerModel);
    }

    private static final class RecordingChatModel implements ChatModel {
        private final String reply;
        private final boolean fail;
        private final List<ChatRequest> requests = new ArrayList<>();

        private RecordingChatModel(String reply) {
            this.reply = reply;
            this.fail = false;
        }

        private RecordingChatModel() {
            this.reply = "";
            this.fail = true;
        }

        private static RecordingChatModel failing() {
            return new RecordingChatModel();
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            if (fail) {
                throw new IllegalStateException("provider down");
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
