package com.agentplatform.agent.config;

import com.agentplatform.agent.ai.ConfiguredProvider;
import com.agentplatform.agent.ai.LangChain4jModelFactory;
import com.agentplatform.agent.ai.BackgroundLlmClient;
import com.agentplatform.agent.ai.RoutingBackgroundChatModel;
import com.agentplatform.security.InternalToken;
import com.agentplatform.security.JwtUtil;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import dev.langchain4j.model.chat.ChatModel;
import feign.RequestInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AgentBeans {

    private static final Logger log = LoggerFactory.getLogger(AgentBeans.class);
    private static final String ANTHROPIC_MESSAGES_KIND = "anthropic-messages";
    private static final String CODEX_RESPONSES_KIND = "codex-responses";

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
    public List<ConfiguredProvider> chatClients(AgentProperties props,
                                                LangChain4jModelFactory langChain4jModelFactory) {
        List<AgentProperties.Provider> defs = props.agent().providers();
        if (defs == null || defs.isEmpty()) {
            throw new IllegalStateException("agent-platform.agent.providers must not be empty");
        }
        List<ConfiguredProvider> out = new ArrayList<>(defs.size());
        for (AgentProperties.Provider p : defs) {
            configuredProvider(p, langChain4jModelFactory).ifPresent(out::add);
        }
        if (out.isEmpty()) {
            throw new IllegalStateException(
                    "no usable LLM provider in agent-platform.agent.providers (all blank/placeholder)");
        }
        return out;
    }

    private Optional<ConfiguredProvider> configuredProvider(
            AgentProperties.Provider p,
            LangChain4jModelFactory langChain4jModelFactory) {
        if (p == null) return Optional.empty();
        String kind = providerKind(p);
        if (!ANTHROPIC_MESSAGES_KIND.equals(kind) && !CODEX_RESPONSES_KIND.equals(kind)) {
            log.warn("[agent] skipping provider '{}' kind={} (unsupported)",
                    p.name(), kind);
            return Optional.empty();
        }
        if (isMissingApiKey(p.apiKey())) {
            log.warn("[agent] skipping provider '{}': empty/placeholder apiKey", p.name());
            return Optional.empty();
        }
        String baseUrl = p.baseUrl() == null || p.baseUrl().isBlank()
                ? defaultBaseUrl(kind) : p.baseUrl();
        AnthropicClient client = anthropicClient(p, kind, baseUrl);
        ChatModel backgroundChatModel = langChain4jModelFactory.backgroundChatModel(p, kind, baseUrl, p.model());
        log.info("[agent] provider '{}' ready: kind={} model={} baseUrl={}",
                p.name(), kind, p.model(), baseUrl);
        return Optional.of(new ConfiguredProvider(
                p.name(), kind, client, backgroundChatModel, baseUrl, p.apiKey(), p.model()));
    }

    private static String providerKind(AgentProperties.Provider p) {
        return p.kind() == null || p.kind().isBlank()
                ? ANTHROPIC_MESSAGES_KIND : p.kind().trim();
    }

    private static boolean isMissingApiKey(String apiKey) {
        return apiKey == null
                || apiKey.isBlank()
                || "PLACEHOLDER".equalsIgnoreCase(apiKey.trim());
    }

    private static AnthropicClient anthropicClient(AgentProperties.Provider p, String kind, String baseUrl) {
        if (!ANTHROPIC_MESSAGES_KIND.equals(kind)) return null;
        // SDK-level retries disabled: ChatService wraps the whole
        // provider call in an outer failover loop.
        return AnthropicOkHttpClient.builder()
                .apiKey(p.apiKey())
                .baseUrl(baseUrl)
                .maxRetries(0)
                .build();
    }

    @Bean("backgroundFactExtractorChatModel")
    public ChatModel backgroundFactExtractorChatModel(List<ConfiguredProvider> providers,
                                                      AgentProperties props,
                                                      BackgroundLlmClient backgroundLlmClient) {
        return new RoutingBackgroundChatModel(
                providers,
                backgroundLlmClient,
                () -> props.agent().memory().factExtractorModel(),
                1024);
    }

    @Bean("sessionSummarizerChatModel")
    public ChatModel sessionSummarizerChatModel(List<ConfiguredProvider> providers,
                                                AgentProperties props,
                                                BackgroundLlmClient backgroundLlmClient) {
        return new RoutingBackgroundChatModel(
                providers,
                backgroundLlmClient,
                () -> props.agent().memory().factExtractorModel(),
                Math.max(200, props.agent().memory().summaryMaxTokens()));
    }

    private static String defaultBaseUrl(String kind) {
        return CODEX_RESPONSES_KIND.equals(kind)
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
    public WebClient hubWebClient(
            @LoadBalanced WebClient.Builder builder,
            @Value("${agent-platform.internal.token:${agent-platform.jwt.secret}}") String internalToken) {
        // Default Spring WebClient caps in-memory response decode at 256KB,
        // which trips the moment a tool returns a high-res image. Bump to 16MB.
        return builder
                .defaultHeader(InternalToken.HEADER, internalToken)
                .exchangeStrategies(org.springframework.web.reactive.function.client.ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build())
                .build();
    }

    @Bean
    public RequestInterceptor internalTokenFeignInterceptor(
            @Value("${agent-platform.internal.token:${agent-platform.jwt.secret}}") String internalToken) {
        return template -> template.header(InternalToken.HEADER, internalToken);
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
