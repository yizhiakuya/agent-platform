package com.agentplatform.auth.dto;

/**
 * Body for {@code PUT /api/me/preferences}. {@code content} may be empty
 * (clears the document) but not null.
 */
public record UpdatePreferenceRequest(String content) {}
