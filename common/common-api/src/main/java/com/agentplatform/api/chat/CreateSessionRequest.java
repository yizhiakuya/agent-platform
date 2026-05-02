package com.agentplatform.api.chat;

import java.util.UUID;

/**
 * @param userId  Owner of the session. Required.
 * @param title   Optional initial title (generally null at creation; the agent
 *                may set it later from the first user message).
 */
public record CreateSessionRequest(UUID userId, String title) {}
