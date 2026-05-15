package com.agentplatform.agent.ai;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * LangChain4j ChatModel facade over the project's ordered provider pool.
 *
 * <p>This lets Spring AI Services use the documented LangChain4j bean wiring
 * model while preserving the existing deploy-time provider routing and fallback.
 */
public class RoutingBackgroundChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RoutingBackgroundChatModel.class);

    private final List<ConfiguredProvider> providers;
    private final BackgroundLlmClient planner;
    private final Supplier<String> preferredModel;
    private final int defaultMaxOutputTokens;

    public RoutingBackgroundChatModel(List<ConfiguredProvider> providers,
                                      BackgroundLlmClient planner,
                                      Supplier<String> preferredModel,
                                      int defaultMaxOutputTokens) {
        this.providers = providers == null ? List.of() : providers;
        this.planner = planner;
        this.preferredModel = preferredModel;
        this.defaultMaxOutputTokens = defaultMaxOutputTokens;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        List<BackgroundLlmClient.CompletionPlan> plans =
                planner.candidatePlans(providers, preferredModel == null ? null : preferredModel.get());
        RuntimeException last = null;
        for (BackgroundLlmClient.CompletionPlan plan : plans) {
            ChatModel model = plan.provider().backgroundChatModel();
            if (model == null) {
                continue;
            }
            try {
                return model.chat(forProvider(request, plan.model()));
            } catch (RuntimeException e) {
                last = e;
                log.warn("[background-llm] provider '{}' model '{}' failed: {}",
                        plan.provider().name(), plan.model(), e.getMessage());
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("no LangChain4j background ChatModel provider configured");
    }

    private ChatRequest forProvider(ChatRequest request, String model) {
        List<ChatMessage> messages = request == null || request.messages() == null || request.messages().isEmpty()
                ? List.of(UserMessage.from(""))
                : request.messages();
        Integer maxOutputTokens = request == null ? null : request.maxOutputTokens();
        ChatRequest.Builder builder = ChatRequest.builder()
                .messages(messages)
                .modelName(model);
        if (request != null) {
            builder.temperature(request.temperature())
                    .topP(request.topP())
                    .topK(request.topK())
                    .frequencyPenalty(request.frequencyPenalty())
                    .presencePenalty(request.presencePenalty())
                    .stopSequences(request.stopSequences())
                    .toolSpecifications(request.toolSpecifications())
                    .toolChoice(request.toolChoice())
                    .responseFormat(request.responseFormat());
        }
        if (maxOutputTokens == null && defaultMaxOutputTokens > 0) {
            builder.maxOutputTokens(defaultMaxOutputTokens);
        }
        if (maxOutputTokens != null) {
            builder.maxOutputTokens(maxOutputTokens);
        }
        return builder.build();
    }
}
