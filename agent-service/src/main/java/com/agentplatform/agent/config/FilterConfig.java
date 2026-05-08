package com.agentplatform.agent.config;

import com.agentplatform.agent.filter.AgentAuthFilter;
import com.agentplatform.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.List;

@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<AgentAuthFilter> agentAuthFilter(
            JwtUtil jwt,
            @Value("${agent-platform.internal.token:${agent-platform.jwt.secret}}") String internalToken) {
        FilterRegistrationBean<AgentAuthFilter> bean = new FilterRegistrationBean<>(
                new AgentAuthFilter(jwt, internalToken, List.of("/api/")));
        bean.addUrlPatterns("/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return bean;
    }
}
