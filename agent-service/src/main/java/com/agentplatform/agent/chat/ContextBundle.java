package com.agentplatform.agent.chat;

import com.agentplatform.api.chat.MessageDto;
import com.agentplatform.api.chat.SessionArtifactDto;
import com.agentplatform.api.chat.SessionContextSummaryDto;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;

import java.util.List;

/**
 * Per-request context assembled for one model call.
 */
public record ContextBundle(
        String stableSystemText,
        List<TextBlockParam> systemBlocks,
        String userText,
        List<MessageParam> anthropicMessages,
        List<MessageDto> historyRows,
        List<SessionArtifactDto> artifacts,
        SessionContextSummaryDto summary,
        ContextStats stats
) {}
