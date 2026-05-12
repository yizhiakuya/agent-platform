package com.agentplatform.agent.filter;

import com.agentplatform.security.JwtUtil;
import com.agentplatform.security.Principal;
import com.agentplatform.security.PrincipalContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class AgentAuthFilterTest {

    private static final String INTERNAL_TOKEN = "internal-token";

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void clearsPrincipalContextWhenInternalTokenRejected() throws Exception {
        AgentAuthFilter filter = new AgentAuthFilter(jwt(), INTERNAL_TOKEN, List.of("/api/"));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/internal/ping");
        MockHttpServletResponse res = new MockHttpServletResponse();
        PrincipalContext.set(new Principal(Principal.TYPE_USER, "stale", "stale", "stale-jti"));

        filter.doFilter(req, res, failingChain());

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(PrincipalContext.current()).isNull();
    }
    private static JwtUtil jwt() {
        String secret = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        return new JwtUtil(secret, "agent-platform");
    }

    private static FilterChain failingChain() {
        return (request, response) -> fail("request should have been rejected");
    }
}
