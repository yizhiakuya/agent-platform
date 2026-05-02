package com.agentplatform.agent.config;

import com.agentplatform.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
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
     * Single-provider ChatClient sourced from Spring AI's auto-configured
     * {@link ChatModel} bean (driven by {@code spring.ai.anthropic.*} props).
     * This is the legacy v0 path; kept so existing deployments without
     * {@code agent-platform.agent.providers} keep working.
     *
     * <p>Returns null when no API key / no auto-configured model — ChatService
     * falls back to its mock LLM path in that case.
     */
    @Bean
    public ChatClient singleProviderChatClient(ObjectProvider<ChatModel> chatModelProvider, Environment env) {
        String apiKey = env.getProperty("spring.ai.anthropic.api-key", "");
        if (apiKey.isBlank() || apiKey.contains("PLACEHOLDER")) {
            log.warn("[agent] ANTHROPIC_API_KEY missing/placeholder — singleProviderChatClient=null (mock LLM fallback)");
            return null;
        }
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            log.warn("[agent] No Anthropic ChatModel bean — singleProviderChatClient=null");
            return null;
        }
        // Build default options WITHOUT temperature — Claude Opus 4.7 (a reasoning
        // model) rejects the param outright. Spring AI auto-config quietly sets a
        // default temperature even when application.yaml omits it, so we have to
        // explicitly construct the default options ourselves to keep it null.
        String modelName = env.getProperty("spring.ai.anthropic.chat.options.model", "claude-opus-4-7");
        Integer maxTokens = Integer.valueOf(env.getProperty("spring.ai.anthropic.chat.options.max-tokens", "4096"));
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(modelName)
                .maxTokens(maxTokens)
                .temperature(null)   // Opus 4.7 rejects the param outright; force serialiser to omit it
                .topK(null)
                .topP(null)
                .build();
        log.info("[agent] singleProviderChatClient ready (model={}, maxTokens={})", modelName, maxTokens);
        return ChatClient.builder(model).defaultOptions(options).build();
    }

    /**
     * Provider pool. ChatService iterates this in order, falling over to the
     * next on synchronous setup failure. Construction rules:
     * <ul>
     *   <li>If {@code agent-platform.agent.providers} is non-empty, build one
     *       ChatClient per provider whose kind is wired (currently only
     *       {@code anthropic-messages}). Providers with blank apiKey are
     *       skipped with a warn — better to ship a degraded list than crash
     *       startup.</li>
     *   <li>If the resulting list is empty AND {@code singleProviderChatClient}
     *       is non-null, fall back to that single client so legacy
     *       {@code spring.ai.anthropic.*} configs keep working.</li>
     *   <li>If both are empty, return an empty list — ChatService will route
     *       all traffic through the mock LLM path.</li>
     * </ul>
     *
     * <p>TODO P2: support {@code openai-responses} kind for non-Anthropic
     * providers and a true provider-decorator that lets {@code .stream()}
     * itself fail over via {@code reactor.core.publisher.Flux.onErrorResume}.
     * v0 only fails over on synchronous (builder / pre-stream) errors.
     */
    @Bean
    public List<ChatClient> chatClients(AgentProperties props,
                                        ObjectProvider<ChatClient> singleProvider) {
        List<AgentProperties.Provider> configured = props.agent().providers();
        List<ChatClient> out = new ArrayList<>();
        if (configured != null) {
            for (AgentProperties.Provider p : configured) {
                if (p == null) continue;
                if (p.apiKey() == null || p.apiKey().isBlank() || p.apiKey().contains("PLACEHOLDER")) {
                    log.warn("[agent] provider '{}' has blank/placeholder apiKey — skipping", p.name());
                    continue;
                }
                String kind = p.kind() == null ? "anthropic-messages" : p.kind();
                switch (kind) {
                    case "anthropic-messages" -> {
                        try {
                            ChatClient cc = buildAnthropicClient(p);
                            out.add(cc);
                            log.info("[agent] provider '{}' ready: kind=anthropic-messages model={} baseUrl={}",
                                    p.name(), p.model(), p.baseUrl());
                        } catch (RuntimeException ex) {
                            log.warn("[agent] provider '{}' build failed: {}; skipping", p.name(), ex.getMessage());
                        }
                    }
                    case "openai-responses" -> log.warn(
                            "[agent] provider '{}' kind=openai-responses not yet wired; skipping (TODO P2)",
                            p.name());
                    default -> log.warn("[agent] provider '{}' unknown kind '{}'; skipping", p.name(), kind);
                }
            }
        }
        if (out.isEmpty()) {
            ChatClient fallback = singleProvider.getIfAvailable();
            if (fallback != null) {
                log.info("[agent] no provider pool entries — using legacy singleProviderChatClient");
                out.add(fallback);
            } else {
                log.warn("[agent] no chat clients configured — ChatService will use mock LLM path");
            }
        }
        return out;
    }

    /**
     * Build an Anthropic-format ChatClient from one provider config. Each
     * provider gets its own AnthropicApi (own baseUrl + apiKey) so the pool
     * can mix sub2api proxies, the official Anthropic API, and a self-hosted
     * relay without state crossover.
     */
    private static ChatClient buildAnthropicClient(AgentProperties.Provider p) {
        AnthropicApi api = AnthropicApi.builder()
                .baseUrl(p.baseUrl() == null || p.baseUrl().isBlank() ? "https://api.anthropic.com" : p.baseUrl())
                .apiKey(p.apiKey())
                .build();
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(p.model() == null || p.model().isBlank() ? "claude-sonnet-4-6" : p.model())
                .maxTokens(4096)
                .temperature(null)
                .topK(null)
                .topP(null)
                .build();
        AnthropicChatModel model = AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(options)
                .build();
        return ChatClient.builder(model).defaultOptions(options).build();
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

    /**
     * Plain (non-LB) WebClient.Builder marked {@code @Primary} so that Spring AI's
     * Anthropic auto-config picks this one up, NOT the load-balanced variant —
     * otherwise it tries to resolve {@code subapi.rainaki.top} as a service name
     * against Nacos and 503s. Same reasoning for the {@link RestClient.Builder}.
     */
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
        return builder.build();
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
