package com.agentplatform.agent.chat;

import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.chat.MessageDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ContextBudget {

    private static final int CHARS_PER_TOKEN = 4;
    private static final int MESSAGE_OVERHEAD_TOKENS = 8;
    private static final int RESERVED_OUTPUT_AND_TOOLS_TOKENS = 6_000;

    private ContextBudget() {}

    public static List<MessageDto> selectRecent(List<MessageDto> rows, AgentProperties.Memory mem) {
        if (rows == null || rows.isEmpty()) return List.of();
        return selectTail(rows, Math.max(1, mem.recentHistoryMessages()), mem.maxInputTokens());
    }

    public static List<MessageDto> selectTail(List<MessageDto> rows, int messageLimit, int maxInputTokens) {
        if (rows == null || rows.isEmpty()) return List.of();
        int recentLimit = Math.max(1, messageLimit);
        int inputCap = Math.max(8_000, maxInputTokens);
        int historyBudget = Math.max(1_000, inputCap - RESERVED_OUTPUT_AND_TOOLS_TOKENS);
        List<MessageDto> reversed = new ArrayList<>(rows);
        Collections.reverse(reversed);
        List<MessageDto> out = new ArrayList<>();
        int tokens = 0;
        for (MessageDto row : reversed) {
            if (out.size() >= recentLimit) break;
            int next = estimateMessageTokens(row);
            if (!out.isEmpty() && tokens + next > historyBudget) break;
            out.add(row);
            tokens += next;
        }
        Collections.reverse(out);
        return out;
    }

    public static int estimateTextTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN);
    }

    public static int estimateMessageTokens(MessageDto row) {
        return MESSAGE_OVERHEAD_TOKENS + estimateTextTokens(row == null ? null : row.content());
    }

    public static int estimateMessagesTokens(List<MessageDto> rows) {
        if (rows == null || rows.isEmpty()) return 0;
        int total = 0;
        for (MessageDto row : rows) {
            total += estimateMessageTokens(row);
        }
        return total;
    }
}
