package com.agentplatform.chat.config;

import com.agentplatform.security.JwtUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatBeans {

    @Bean
    public JwtUtil jwtUtil(ChatProperties props) {
        return new JwtUtil(props.jwt().secret(), props.jwt().issuer());
    }
}
