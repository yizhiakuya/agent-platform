package com.agentplatform.agent.config;

import com.agentplatform.agent.ai.VisionAwareToolCallingManager;
import com.agentplatform.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
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
    public ChatClient singleProviderChatClient(ObjectProvider<ChatModel> chatModelProvider,
                                               Environment env,
                                               AgentProperties props) {
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
        boolean cacheOn = Boolean.TRUE.equals(props.agent().memory().enablePromptCache());
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(modelName)
                .maxTokens(maxTokens)
                .temperature(null)   // Opus 4.7 rejects the param outright; force serialiser to omit it
                .topK(null)
                .topP(null)
                .cacheOptions(cacheOn ? buildSystemCacheOptions() : AnthropicCacheOptions.DISABLED)
                .build();
        log.info("[agent] singleProviderChatClient ready (model={}, maxTokens={}, promptCache={})",
                modelName, maxTokens, cacheOn);
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
        boolean cacheOn = Boolean.TRUE.equals(props.agent().memory().enablePromptCache());
        boolean visionOn = Boolean.TRUE.equals(props.agent().memory().enableVisionToolResults());
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
                            ChatClient cc = buildAnthropicClient(p, cacheOn, visionOn);
                            out.add(cc);
                            log.info("[agent] provider '{}' ready: kind=anthropic-messages model={} baseUrl={} promptCache={} vision={}",
                                    p.name(), p.model(), p.baseUrl(), cacheOn, visionOn);
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
     *
     * <p>When {@code cacheOn} is true, the resulting options carry a SYSTEM_ONLY
     * cache config with multi-block system caching enabled — ChatService is
     * expected to feed the prompt as multiple SystemMessages (stable head first,
     * volatile RAG context routed to the user message instead) so only the
     * stable head consumes a breakpoint.
     */
    private static ChatClient buildAnthropicClient(AgentProperties.Provider p, boolean cacheOn, boolean visionOn) {
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
                .cacheOptions(cacheOn ? buildSystemCacheOptions() : AnthropicCacheOptions.DISABLED)
                .build();
        AnthropicChatModel.Builder builder = AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(options)
                // Spring AI 1.x default RetryTemplate is maxAttempts=10 with
                // exponential backoff — a single transient 5xx or timeout
                // explodes into up to 10 sub2api hits and 10 billing rows.
                // Cap at 2 attempts (1 retry, ~500ms backoff): covers brief
                // network blips without flooding the upstream relay.
                .retryTemplate(restrainedRetryTemplate());
        if (visionOn) {
            // Wrap the default tool-calling manager so we can post-process
            // tool responses: any base64 images stashed in the shared
            // tool-context by RemoteToolCallback get appended as a sibling
            // UserMessage with Media attachments, which the Anthropic chat
            // model already knows how to render as multimodal content blocks.
            ToolCallingManager wrapped = new VisionAwareToolCallingManager(
                    DefaultToolCallingManager.builder().build());
            builder = builder.toolCallingManager(wrapped);
        }
        AnthropicChatModel model = builder.build();
        return ChatClient.builder(model).defaultOptions(options).build();
    }

    /**
     * Cache config for the system prompt: SYSTEM_ONLY strategy + multi-block
     * caching, so that {@link com.agentplatform.agent.chat.ChatService} can
     * split the system prompt into a stable head SystemMessage (gets the
     * breakpoint) and the volatile RAG memory block flows through the user
     * message (no breakpoint, never poisons the cached prefix).
     *
     * <p>{@code minContentLength=1024} matches Anthropic's hard floor for
     * Sonnet/Opus — anything shorter than 1024 chars is below the model's
     * cache floor anyway, so we'd waste a breakpoint on something the API
     * wouldn't cache. Our stable head sits around 4500 chars so this passes.
     * (Tools array is intentionally NOT cached — device tools are dynamic.)
     */
    private static AnthropicCacheOptions buildSystemCacheOptions() {
        return AnthropicCacheOptions.builder()
                .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                .messageTypeMinContentLength(MessageType.SYSTEM, 1024)
                .multiBlockSystemCaching(true)
                .build();
    }

    /**
     * 2-attempt retry policy: caps the upstream blast radius when sub2api or
     * Anthropic flakes. Backoff starts at 500ms and stops fast; we'd rather
     * surface a fast error to the user than rack up 10 billing rows for one
     * bad request.
     */
    private static RetryTemplate restrainedRetryTemplate() {
        SimpleRetryPolicy policy = new SimpleRetryPolicy();
        policy.setMaxAttempts(2);
        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(500);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(2_000);
        RetryTemplate t = new RetryTemplate();
        t.setRetryPolicy(policy);
        t.setBackOffPolicy(backoff);
        return t;
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
