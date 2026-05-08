package com.agentplatform.hub.config;

import com.agentplatform.hub.filter.InternalTokenFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<InternalTokenFilter> internalTokenFilter(
            @Value("${agent-platform.internal.token:${agent-platform.jwt.secret}}") String internalToken) {
        FilterRegistrationBean<InternalTokenFilter> bean =
                new FilterRegistrationBean<>(new InternalTokenFilter(internalToken));
        bean.addUrlPatterns("/internal/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
        return bean;
    }
}
