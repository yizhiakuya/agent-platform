package com.agentplatform.auth.config;

import com.agentplatform.auth.filter.PathBasedJwtFilter;
import com.agentplatform.security.JwtUtil;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.List;

@Configuration
public class FilterConfig {

    /**
     * Register {@link PathBasedJwtFilter} as a top-priority servlet filter that
     * gates {@code /api/me/**} on a valid user JWT. Public endpoints
     * ({@code /api/auth/**}, {@code /internal/**}, {@code /actuator/**}) pass
     * through; if they happen to carry a Bearer token we still parse it
     * best-effort so controllers can opportunistically know the caller.
     */
    @Bean
    public FilterRegistrationBean<PathBasedJwtFilter> jwtAuthFilter(JwtUtil jwt) {
        FilterRegistrationBean<PathBasedJwtFilter> bean =
                new FilterRegistrationBean<>(new PathBasedJwtFilter(jwt, List.of("/api/me")));
        bean.addUrlPatterns("/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return bean;
    }
}
