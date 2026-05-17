package com.agentplatform.api.auth;

import java.time.OffsetDateTime;

/**
 * Per-user preference document. {@code content} is free-form markdown injected
 * into the LLM system prompt. {@code autoMemoryEnabled} controls automatic
 * long-term memory recall and background fact extraction.
 */
public record UserPreferenceDto(
        String content,
        OffsetDateTime updatedAt,
        Boolean autoMemoryEnabled
) {
    public boolean autoMemoryEnabledOrDefault() {
        return autoMemoryEnabled == null || autoMemoryEnabled;
    }
}
