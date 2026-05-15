package com.agentplatform.agent.chat;

import com.agentplatform.agent.ai.PendingImage;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;

public record ChatAttachmentContext(
        List<ChatImageAttachment> attachments,
        List<PendingImage> images,
        ArrayNode metadata,
        String promptText
) {
    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }
}
