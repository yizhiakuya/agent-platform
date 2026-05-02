package com.agentplatform.agent.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.DefaultToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.MimeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wrapper around Spring AI's default {@link ToolCallingManager} that injects
 * binary image bytes pulled from device-tool responses into the conversation
 * as a sibling {@code UserMessage} with {@link Media} attachments — so a
 * vision-capable Claude can actually inspect them on the next turn.
 *
 * <h3>Why this layer?</h3>
 *
 * <p>Spring AI 1.1.5's {@link org.springframework.ai.tool.ToolCallback#call}
 * contract is hardcoded to return {@link String}. That string is funneled
 * straight into Anthropic's {@code tool_result} content block as a plain
 * text payload — there's no overload that accepts {@code List<ContentBlock>}
 * or {@code Media}, even though the underlying {@code AnthropicApi.ContentBlock}
 * supports {@code IMAGE} sources just fine. We bridge the gap here:
 * {@link RemoteToolCallback} stashes any {@code *_b64} fields it stripped
 * from the tool response into the shared {@link ToolCallingChatOptions}
 * tool-context map under {@link RemoteToolCallback#PENDING_IMAGES_KEY};
 * after the delegate manager has assembled its standard {@code ToolResponseMessage},
 * we drain that bucket and append a sibling {@code UserMessage} carrying the
 * actual image bytes via Spring AI's existing {@link Media} pathway. The
 * Anthropic chat-model code (lambda13 in {@code AnthropicChatModel.buildMessages})
 * then wires those Medias into proper {@code ContentBlock.IMAGE} blocks on
 * the next request.
 *
 * <h3>Caveats</h3>
 *
 * <ul>
 *   <li>The injected message has role=USER, which means the wire conversation
 *       ends up looking like {@code [..., assistant tool_use, user tool_result,
 *       user "[images...]" + media]}. Anthropic's API tolerates back-to-back
 *       user messages — they're concatenated server-side — so this is safe.</li>
 *   <li>The bucket is drained on every call; if a tool call doesn't surface
 *       any base64 fields, we fall through to the delegate's result unmodified.
 *       Non-vision LLMs continue to see only the stripped placeholder.</li>
 *   <li>Errors from the delegate aren't swallowed — we just hand them back
 *       to the chat model unchanged.</li>
 * </ul>
 */
public class VisionAwareToolCallingManager implements ToolCallingManager {

    private static final Logger log = LoggerFactory.getLogger(VisionAwareToolCallingManager.class);

    private final ToolCallingManager delegate;

    public VisionAwareToolCallingManager(ToolCallingManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        ToolExecutionResult result = delegate.executeToolCalls(prompt, chatResponse);

        // Pull pending images out of the shared tool-context. Both prompt
        // options and the delegate's options carry the same map (Spring AI
        // merges them in AnthropicChatModel.buildRequestPrompt before
        // dispatching), so we look at the prompt's options first since
        // that's the surface the user-facing ChatService set up.
        Map<String, Object> ctx = extractToolContext(prompt);
        if (ctx == null) return result;

        Object raw = ctx.remove(RemoteToolCallback.PENDING_IMAGES_KEY);
        if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) {
            return result;
        }

        List<RemoteToolCallback.PendingImage> imgs = new ArrayList<>(rawList.size());
        for (Object o : rawList) {
            if (o instanceof RemoteToolCallback.PendingImage pi) imgs.add(pi);
        }
        if (imgs.isEmpty()) return result;

        UserMessage visionMsg = buildVisionUserMessage(imgs);
        List<Message> history = new ArrayList<>(result.conversationHistory());
        history.add(visionMsg);

        long totalBytes = imgs.stream().mapToLong(p -> p.b64() == null ? 0 : p.b64().length()).sum();
        log.info("vision tool_result: tools=[{}] images={} total_b64_bytes={}",
                imgs.stream().map(RemoteToolCallback.PendingImage::toolName).distinct().toList(),
                imgs.size(), totalBytes);

        return DefaultToolExecutionResult.builder()
                .conversationHistory(history)
                .returnDirect(result.returnDirect())
                .build();
    }

    private static Map<String, Object> extractToolContext(Prompt prompt) {
        ChatOptions opts = prompt.getOptions();
        if (opts instanceof ToolCallingChatOptions tco) {
            return tco.getToolContext();
        }
        return null;
    }

    /**
     * Build the sibling user message Anthropic will see on the next turn.
     * Each image is one {@link Media}; the LLM gets enough textual context
     * to associate each image back to its originating tool call (the {@code path}
     * field carries the JSON pointer it was extracted from, e.g.
     * {@code photos[3].thumb_b64}).
     */
    private static UserMessage buildVisionUserMessage(List<RemoteToolCallback.PendingImage> imgs) {
        StringBuilder text = new StringBuilder();
        text.append("[Attached: ").append(imgs.size())
                .append(imgs.size() == 1 ? " image" : " images")
                .append(" extracted from the previous tool result");
        // Render the path mapping inline so the LLM can disambiguate which
        // image belongs to which tool / array index.
        text.append(" — order matches:\n");
        for (int i = 0; i < imgs.size(); i++) {
            RemoteToolCallback.PendingImage p = imgs.get(i);
            text.append("  ").append(i + 1).append(". ")
                    .append(p.toolName()).append(" :: ").append(p.path())
                    .append('\n');
        }
        text.append("]");

        List<Media> media = new ArrayList<>(imgs.size());
        for (RemoteToolCallback.PendingImage p : imgs) {
            media.add(Media.builder()
                    .mimeType(MimeType.valueOf(p.mimeType() == null ? "image/jpeg" : p.mimeType()))
                    .data(p.b64())  // String form — AnthropicChatModel.fromMediaData passes through unchanged
                    .build());
        }

        return UserMessage.builder()
                .text(text.toString())
                .media(media)
                .build();
    }
}
