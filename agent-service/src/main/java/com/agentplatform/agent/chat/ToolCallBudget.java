package com.agentplatform.agent.chat;

import com.agentplatform.agent.ai.ChatEventSink;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Per-turn tool execution budget. Counts actual tool attempts, and adds
 * optional progress metadata to the matching tool_call_started SSE event.
 */
final class ToolCallBudget {

    private final int maxToolCalls;
    private final int maxConsecutiveUiToolCalls;
    private int used;
    private int consecutiveUi;

    ToolCallBudget(int maxToolCalls, int maxConsecutiveUiToolCalls) {
        this.maxToolCalls = Math.max(1, maxToolCalls);
        this.maxConsecutiveUiToolCalls = Math.max(1, maxConsecutiveUiToolCalls);
    }

    int used() {
        return used;
    }

    int maxToolCalls() {
        return maxToolCalls;
    }

    int consecutiveUi() {
        return consecutiveUi;
    }

    int maxConsecutiveUiToolCalls() {
        return maxConsecutiveUiToolCalls;
    }

    Decision before(String toolName) {
        boolean uiTool = isUiTool(toolName);
        if (used >= maxToolCalls) {
            return Decision.totalLimit(used, maxToolCalls);
        }
        if (uiTool && consecutiveUi >= maxConsecutiveUiToolCalls) {
            return Decision.consecutiveUiLimit(consecutiveUi, maxConsecutiveUiToolCalls);
        }
        used += 1;
        consecutiveUi = uiTool ? consecutiveUi + 1 : 0;
        return Decision.allowed(used, maxToolCalls, consecutiveUi, maxConsecutiveUiToolCalls, uiTool);
    }

    private static boolean isUiTool(String toolName) {
        if (toolName == null) return false;
        return toolName.startsWith("ui.") || toolName.startsWith("ui_");
    }

    record Decision(
            boolean allowed,
            String exhaustionReason,
            int toolIndex,
            int maxToolCalls,
            int consecutiveUiToolCalls,
            int maxConsecutiveUiToolCalls,
            boolean uiTool
    ) {
        static Decision allowed(int toolIndex, int maxToolCalls,
                                int consecutiveUiToolCalls, int maxConsecutiveUiToolCalls,
                                boolean uiTool) {
            return new Decision(true, null, toolIndex, maxToolCalls,
                    consecutiveUiToolCalls, maxConsecutiveUiToolCalls, uiTool);
        }

        static Decision totalLimit(int used, int maxToolCalls) {
            return new Decision(false,
                    "本轮已经调用了 " + used + "/" + maxToolCalls
                            + " 个工具，我先停在当前状态。请发送“继续”，我会接着当前页面往下做。",
                    used, maxToolCalls, 0, 0, false);
        }

        static Decision consecutiveUiLimit(int consecutiveUiToolCalls, int maxConsecutiveUiToolCalls) {
            return new Decision(false,
                    "我已经连续调用了 " + consecutiveUiToolCalls + "/"
                            + maxConsecutiveUiToolCalls
                            + " 个界面工具，可能需要换观察方式。我先停一下；请发送“继续”，我会从当前页面继续。",
                    0, 0, consecutiveUiToolCalls, maxConsecutiveUiToolCalls, true);
        }

        ChatEventSink decorate(ChatEventSink sink) {
            if (sink == null) return null;
            return event -> {
                if (event != null && "tool_call_started".equals(event.type()) && event.data() instanceof ObjectNode data) {
                    data.put("toolIndex", toolIndex);
                    data.put("maxToolCalls", maxToolCalls);
                    if (uiTool) {
                        data.put("consecutiveUiToolCalls", consecutiveUiToolCalls);
                        data.put("maxConsecutiveUiToolCalls", maxConsecutiveUiToolCalls);
                    }
                }
                sink.emit(event);
            };
        }
    }
}
