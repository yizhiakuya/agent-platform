package com.agentplatform.chat.filter;

import com.agentplatform.security.HybridAuthFilter;
import com.agentplatform.security.JwtUtil;

import java.util.List;

/**
 * Same hybrid auth as agent-service: gateway-injected trust headers first,
 * Bearer JWT fallback for direct dev access. {@code /api/} paths require
 * authentication; {@code /internal/} paths are gateway-only and trusted as-is.
 */
public class ChatAuthFilter extends HybridAuthFilter {

    public ChatAuthFilter(JwtUtil jwt, String internalToken, List<String> protectedPrefixes) {
        super(jwt, internalToken, protectedPrefixes);
    }
}
