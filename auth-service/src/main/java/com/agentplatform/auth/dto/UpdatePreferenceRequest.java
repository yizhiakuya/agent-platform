package com.agentplatform.auth.dto;

/**
 * Body for {@code PUT /api/me/preferences}. Missing fields preserve their
 * current value. {@code content} may be empty to clear the document.
 */
public record UpdatePreferenceRequest(String content, Boolean autoMemoryEnabled) {}
