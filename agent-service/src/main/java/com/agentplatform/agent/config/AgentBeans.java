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
     * next provider on synchronous setup failure. Providers with blank or
     * placeholder apiKey are skipped with a warning.
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
            String kind = p.kind() == null || p.kind().isBlank()
                    ? "anthropic-messages" : p.kind().trim();
            if (!"anthropic-messages".equals(kind) && !"codex-responses".equals(kind)) {
                log.warn("[agent] skipping provider '{}' kind={} (unsupported)",
                        p.name(), kind);
                continue;
            }
            if (p.apiKey() == null || p.apiKey().isBlank() ||
                    "PLACEHOLDER".equalsIgnoreCase(p.apiKey().trim())) {
                log.warn("[agent] skipping provider '{}': empty/placeholder apiKey", p.name());
                continue;
            }
            String baseUrl = p.baseUrl() == null || p.baseUrl().isBlank()
                    ? defaultBaseUrl(kind) : p.baseUrl();
            AnthropicClient client = null;
            if ("anthropic-messages".equals(kind)) {
                // SDK-level retries disabled: ChatService wraps the whole
                // provider call in an outer failover loop.
                client = AnthropicOkHttpClient.builder()
                        .apiKey(p.apiKey())
                        .baseUrl(baseUrl)
                        .maxRetries(0)
                        .build();
            }
            out.add(new ConfiguredProvider(p.name(), kind, client, baseUrl, p.apiKey(), p.model()));
            log.info("[agent] provider '{}' ready: kind={} model={} baseUrl={}",
                    p.name(), kind, p.model(), baseUrl);
        }
        if (out.isEmpty()) {
            throw new IllegalStateException(
                    "no usable LLM provider in agent-platform.agent.providers (all blank/placeholder)");
        }
        return out;
    }

    private static String defaultBaseUrl(String kind) {
        return "codex-responses".equals(kind)
                ? "https://api.openai.com"
                : "https://api.anthropic.com";
    }

    /**
     * {@code @LoadBalanced} marks this builder so {@code WebClient.build()}
     * resolves {@code lb://service-name} URIs against Spring Cloud LoadBalancer
     * (which talks to Nacos discovery). Used only by DeviceToolDispatcher and
     * DeviceHubClient via explicit {@code @LoadBalanced} qualifier.
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
        // which trips the moment a tool returns a high-res image. Bump to 16MB.
        return builder
                .exchangeStrategies(org.springframework.web.reactive.function.client.ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build())
                .build();
    }

    /**
     * Dedicated pool for SSE streaming work. Each chat request may block on a
     * tool call, so we don't want to tie up Tomcat workers.
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
