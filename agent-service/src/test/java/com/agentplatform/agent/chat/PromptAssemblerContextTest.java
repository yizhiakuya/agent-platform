package com.agentplatform.agent.chat;

import com.agentplatform.api.chat.SessionArtifactDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PromptAssemblerContextTest {

    @Test
    void composeUserTextIncludesWorkingSetBeforeCurrentMessage() {
        SessionArtifactDto artifact = new SessionArtifactDto(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "photo",
                "asset-123",
                "cat.jpg",
                "Photo result: cat.jpg",
                new ObjectMapper().createObjectNode().put("photoId", "asset-123").put("resultRank", 1),
                OffsetDateTime.now(),
                OffsetDateTime.now());

        String text = PromptAssembler.composeUserText(
                "# RELEVANT MEMORIES\n- likes cats",
                PromptAssembler.formatArtifactBlock(List.of(artifact)),
                "再看看这张图");

        assertThat(text).contains("# RELEVANT MEMORIES");
        assertThat(text).contains("# SESSION WORKING SET");
        assertThat(text).contains("photo key=asset-123");
        assertThat(text).contains("result_rank=1");
        assertThat(text).endsWith("再看看这张图");
    }

    @Test
    void composeUserTextIncludesSessionSummaryBeforeCurrentMessage() {
        String text = PromptAssembler.composeUserText(
                "",
                "",
                PromptAssembler.formatSessionSummaryBlock("User asked to keep deployment fast.", 24),
                "继续");

        assertThat(text).contains("# SESSION SUMMARY");
        assertThat(text).contains("Covered messages: 24");
        assertThat(text).contains("User asked to keep deployment fast.");
        assertThat(text).endsWith("继续");
    }
}
