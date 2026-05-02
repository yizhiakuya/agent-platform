package com.agentplatform.gateway.config;

import com.agentplatform.security.JwtUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayBeans {

    @Bean
    public JwtUtil jwtUtil(GatewayProperties props) {
        return new JwtUtil(props.jwt().secret(), props.jwt().issuer());
    }
}
