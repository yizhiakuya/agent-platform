package com.agentplatform.agent.chat;

import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.chat.MessageDto;
import com.agentplatform.api.chat.MessageRole;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBudgetTest {

    @Test
    void selectRecentKeepsNewestMessagesInOriginalOrder() {
        List<MessageDto> rows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            rows.add(message(i));
        }

        List<MessageDto> recent = ContextBudget.selectRecent(rows, memory(6));

        assertThat(recent).extracting(MessageDto::content)
                .containsExactly("m15", "m16", "m17", "m18", "m19", "m20");
    }

    @Test
    void selectRecentKeepsAtLeastNewestMessageWhenBudgetIsTiny() {
        List<MessageDto> rows = List.of(
                message(1, "older"),
                message(2, "x".repeat(20_000)));

        List<MessageDto> recent = ContextBudget.selectTail(rows, 6, 8_000);

        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).content()).startsWith("x");
    }

    private static AgentProperties.Memory memory(int recentHistoryMessages) {
        return new AgentProperties.Memory(
                null,
                0,
                0,
                null,
                null,
                0,
                null,
                0,
                null,
                0,
                null,
                null,
                null,
                null,
                recentHistoryMessages,
                18,
                48_000,
                1_200,
                true);
    }

    private static MessageDto message(int n) {
        return message(n, "m" + n);
    }

    private static MessageDto message(int n, String content) {
        return new MessageDto(
                UUID.randomUUID(),
                UUID.randomUUID(),
                n % 2 == 0 ? MessageRole.ASSISTANT : MessageRole.USER,
                content,
                null,
                OffsetDateTime.now().plusSeconds(n));
    }
}
