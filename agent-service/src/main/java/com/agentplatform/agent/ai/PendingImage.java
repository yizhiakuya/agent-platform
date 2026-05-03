package com.agentplatform.agent.ai;

/**
 * Vision tool_result image carrier.
 *
 * <p>Pulled out of the device tool's raw response by
 * {@link RemoteToolCallback#executeToolUse}; re-inflated into a SDK-native
 * {@code ImageBlockParam} when {@code ChatService} assembles the
 * {@code ToolResultBlockParam} that goes back to the LLM.
 *
 * <p>{@code mimeType} looks like {@code "image/jpeg"} (full MIME — Anthropic's
 * {@code source.media_type} field expects exactly that form). {@code b64} is
 * raw base64 with no {@code data:image/...;base64,} prefix.
 */
public record PendingImage(String mimeType, String b64) {}
