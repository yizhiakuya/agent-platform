package com.agentplatform.auth.filter;

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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class PathBasedJwtFilterTest {

    private static final String INTERNAL_TOKEN = "internal-token";

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void stalePrincipalContextDoesNotBypassProtectedPath() throws Exception {
        PathBasedJwtFilter filter = new PathBasedJwtFilter(jwt(), INTERNAL_TOKEN, List.of("/api/me"));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/me/devices");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        FilterChain chain = (request, response) -> chainCalled.set(true);
        PrincipalContext.set(new Principal(Principal.TYPE_USER, "stale", "stale", "stale-jti"));

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(chainCalled).isFalse();
        assertThat(PrincipalContext.current()).isNull();
    }

    private static JwtUtil jwt() {
        String secret = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        return new JwtUtil(secret, "agent-platform");
    }
}
