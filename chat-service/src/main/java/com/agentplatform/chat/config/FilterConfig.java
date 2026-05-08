package com.agentplatform.chat.config;

import com.agentplatform.chat.filter.ChatAuthFilter;
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
    public FilterRegistrationBean<ChatAuthFilter> chatAuthFilter(
            JwtUtil jwt,
            @Value("${agent-platform.internal.token:${agent-platform.jwt.secret}}") String internalToken) {
        FilterRegistrationBean<ChatAuthFilter> bean = new FilterRegistrationBean<>(
                new ChatAuthFilter(jwt, internalToken, List.of("/api/")));
        bean.addUrlPatterns("/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return bean;
    }
}
