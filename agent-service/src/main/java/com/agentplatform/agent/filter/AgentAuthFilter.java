package com.agentplatform.agent.filter;

import com.agentplatform.security.HybridAuthFilter;
import com.agentplatform.security.JwtUtil;

import java.util.List;

/**
 * Hybrid auth filter for agent-service:
 * <ol>
 *   <li><b>Trusted gateway headers first.</b> If {@code X-Principal-Type} is set
 *       (gateway already verified the JWT), trust the {@code X-User-Id} /
 *       {@code X-Device-Id} / {@code X-Jti} headers.</li>
 *   <li><b>Fallback to direct JWT verification.</b> If headers are absent
 *       (developer hits port 8082 directly with curl, no gateway in front),
 *       parse {@code Authorization: Bearer <jwt>} the same way auth-service does.</li>
 *   <li><b>Protected paths require principal.</b> Any path under
 *       {@code protectedPrefixes} without a resolved principal is 401.</li>
 * </ol>
 */
public class AgentAuthFilter extends HybridAuthFilter {

    public AgentAuthFilter(JwtUtil jwt, String internalToken, List<String> protectedPrefixes) {
        super(jwt, internalToken, protectedPrefixes);
    }
}
