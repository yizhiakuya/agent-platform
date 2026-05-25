package com.agentplatform.hub.filter;

import com.agentplatform.security.HybridAuthFilter;
import com.agentplatform.security.JwtUtil;

import java.util.List;

public class HubApiAuthFilter extends HybridAuthFilter {

    public HubApiAuthFilter(JwtUtil jwt, String internalToken, List<String> protectedPrefixes) {
        super(jwt, internalToken, protectedPrefixes);
    }
}
