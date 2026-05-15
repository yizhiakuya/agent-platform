package com.agentplatform.agent.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallBudgetTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void stopsAfterTotalToolLimit() {
        ToolCallBudget budget = new ToolCallBudget(2, 10);

        assertThat(budget.before("photos_list_recent").allowed()).isTrue();
        assertThat(budget.before("photos_get_full").allowed()).isTrue();

        ToolCallBudget.Decision decision = budget.before("photos_save_to_gallery");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.exhaustionReason()).contains("2/2 个工具");
    }

    @Test
    void stopsAfterConsecutiveUiLimitAndResetsOnNonUiTool() {
        ToolCallBudget budget = new ToolCallBudget(10, 2);

        assertThat(budget.before("ui_dump_tree").allowed()).isTrue();
        assertThat(budget.before("photos_list_recent").allowed()).isTrue();
        assertThat(budget.before("ui.open_app").allowed()).isTrue();
        assertThat(budget.before("ui_tap").allowed()).isTrue();

        ToolCallBudget.Decision decision = budget.before("ui_dump_tree");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.exhaustionReason()).contains("2/2 个界面工具");
    }

    @Test
    void decoratesStartedEventWithProgress() {
        ToolCallBudget.Decision decision = new ToolCallBudget(3, 2).before("ui_dump_tree");

        SseEvent[] emitted = new SseEvent[1];
        decision.decorate(event -> emitted[0] = event)
                .emit(SseEvent.toolCallStarted(mapper, null, "ui.dump_tree", mapper.createObjectNode()));

        assertThat(emitted[0].data().path("toolIndex").asInt()).isEqualTo(1);
        assertThat(emitted[0].data().path("maxToolCalls").asInt()).isEqualTo(3);
        assertThat(emitted[0].data().path("consecutiveUiToolCalls").asInt()).isEqualTo(1);
        assertThat(emitted[0].data().path("maxConsecutiveUiToolCalls").asInt()).isEqualTo(2);
    }
}
