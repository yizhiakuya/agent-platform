package com.agentplatform.agent.config;

import com.agentplatform.agent.ai.ConfiguredProvider;
import com.agentplatform.security.JwtUtil;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AgentBeans {

    private static final Logger log = LoggerFactory.getLogger(AgentBeans.class);

    @Bean
    public JwtUtil jwtUtil(AgentProperties props) {
        return new JwtUtil(props.jwt().secret(), props.jwt().issuer());
    }

    /**
     * Provider pool. ChatService iterates this in order, falling over to the
     * next on synchronous setup failure. Providers with blank/placeholder
     * apiKey are skipped with a warn — better to ship a degraded list than
     * crash startup. v0 only honours kind=anthropic-messages.
     */
    @Bean
    public List<ConfiguredProvider> chatClients(AgentProperties props) {
        List<AgentProperties.Provider> defs = props.agent().providers();
        if (defs == null || defs.isEmpty()) {
            throw new IllegalStateException("agent-platform.agent.providers must not be empty");
        }
        List<ConfiguredProvider> out = new ArrayList<>(defs.size());
        for (AgentProperties.Provider p : defs) {
            if (p == null) continue;
            if (!"anthropic-messages".equals(p.kind())) {
                log.warn("[agent] skipping provider '{}' kind={} (only anthropic-messages supported in v0)",
                        p.name(), p.kind());
                continue;
            }
            if (p.apiKey() == null || p.apiKey().isBlank() ||
                    "PLACEHOLDER".equalsIgnoreCase(p.apiKey().trim())) {
                log.warn("[agent] skipping provider '{}': empty/placeholder apiKey", p.name());
                continue;
            }
            // SDK-level retries disabled: ChatService.handleWithLlm wraps the
            // whole agent loop in an outer try/catch and falls over to the
            // next ConfiguredProvider. Stacking SDK retries on top of that
            // multiplies billed requests on every transient 5xx / 429 (worst
            // case 6× billed for one user-visible failure).
            AnthropicClient client = AnthropicOkHttpClient.builder()
                    .apiKey(p.apiKey())
                    .baseUrl(p.baseUrl() == null || p.baseUrl().isBlank()
                            ? "https://api.anthropic.com" : p.baseUrl())
                    .maxRetries(0)
                    .build();
            out.add(new ConfiguredProvider(p.name(), client, p.model()));
            log.info("[agent] provider '{}' ready: model={} baseUrl={}",
                    p.name(), p.model(), p.baseUrl());
        }
        if (out.isEmpty()) {
            throw new IllegalStateException(
                    "no usable LLM provider in agent-platform.agent.providers (all blank/placeholder)");
        }
        return out;
    }

    /**
     * {@code @LoadBalanced} marks this builder so {@code WebClient.build()}
     * resolves {@code lb://service-name} URIs against Spring Cloud LoadBalancer
     * (which talks to Nacos discovery). Used only by {@code DeviceToolDispatcher}
     * and {@code DeviceHubClient} via explicit {@code @LoadBalanced} qualifier.
     */
    @Bean
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    @Primary
    public WebClient.Builder defaultWebClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    @Primary
    public RestClient.Builder defaultRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public WebClient hubWebClient(@LoadBalanced WebClient.Builder builder) {
        // Default Spring WebClient caps in-memory response decode at 256KB,
        // which trips the moment a tool returns a high-res image (vision_b64
        // alone is ~300-500KB at 2048px). Bump to 16MB — covers a single
        // 5MB-base64 image plus headroom; anything bigger should already be
        // going through the side-channel upload endpoint.
        return builder
                .exchangeStrategies(org.springframework.web.reactive.function.client.ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build())
                .build();
    }

    /**
     * Dedicated pool for SSE streaming work — each chat request blocks on a
     * tool call, so we don't want to tie up Tomcat workers. Daemon threads so
     * shutdown is clean.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService chatExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "chat-exec");
            t.setDaemon(true);
            return t;
        });
    }
}
