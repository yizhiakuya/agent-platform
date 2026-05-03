package com.agentplatform.agent.ai;

import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

/**
 * Per-request bundle returned by {@link RemoteDeviceToolCallbackProvider#getForUser}.
 *
 * <p>{@code definitions} is the SDK-native {@link Tool} list — caller drops it
 * straight onto {@code MessageCreateParams} (wrapped in {@code ToolUnion}s along
 * with skill_load and any server-side tools).
 *
 * <p>{@code dispatch} is keyed by the tool's wire name (the same name the LLM
 * will echo back inside {@link com.anthropic.models.messages.ToolUseBlock#name()}).
 * The agentic loop in {@code ChatService} looks each {@code tool_use} block up
 * here to drive the matching {@link RemoteToolCallback}; the {@code skill_load}
 * meta-tool is handled separately because it lives on a singleton bean rather
 * than per-user.
 */
public record ResolvedTools(List<Tool> definitions, Map<String, RemoteToolCallback> dispatch) {}
