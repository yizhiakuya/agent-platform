package com.agentplatform.gateway.filter;

import com.agentplatform.gateway.config.GatewayProperties;
import com.agentplatform.security.JwtUtil;
import com.agentplatform.security.Principal;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Verify {@code Authorization: Bearer <jwt>} on incoming requests, reject 401 on
 * protected paths, and inject {@code X-Principal-Type / X-User-Id / X-Device-Id /
 * X-Jti} headers on the upstream request so downstream services don't have to
 * re-verify.
 *
 * <p>WS paths ({@code /ws/**}) are passed through untouched in PR 4 — WS handshake
 * authentication will be added in PR 6 ({@code DeviceWsAuthFilter}) which has to
 * read {@code Sec-WebSocket-Protocol} and call auth-service's verify endpoint.
 *
 * <p>JWT verification is done locally with the shared {@link JwtUtil} (same secret
 * as auth-service issued with), avoiding a round-trip to auth-service per request.
 * Revocation lookup is sacrificed at this stage; PR 13 will add a remote verify
 * call (with caching) to honour the jti blacklist.
 */
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwtUtil jwt;
    private final List<String> protectedPrefixes;

    public JwtAuthFilter(JwtUtil jwt, GatewayProperties props) {
        this.jwt = jwt;
        this.protectedPrefixes = props.protectedPaths() == null ? List.of() : props.protectedPaths();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        String path = req.getURI().getPath();

        // PR 4: WebSocket paths bypass JWT (handshake auth comes in PR 6)
        if (path.startsWith("/ws/")) {
            return chain.filter(exchange);
        }

        boolean isProtected = isProtected(path);
        String header = req.getHeaders().getFirst("Authorization");
        Principal principal = null;

        if (header != null && header.startsWith(BEARER)) {
            try {
                principal = jwt.verify(header.substring(BEARER.length()));
            } catch (JwtException e) {
                if (isProtected) {
                    return reject(exchange, "Invalid token");
                }
                log.debug("Bearer token present but invalid on public path {}: {}", path, e.getMessage());
            }
        }

        if (isProtected && principal == null) {
            return reject(exchange, "Authentication required");
        }

        if (principal != null) {
            ServerHttpRequest.Builder mutated = req.mutate()
                    .header("X-Principal-Type", principal.type())
                    .header("X-User-Id", principal.userId())
                    .header("X-Jti", principal.jti() == null ? "" : principal.jti());
            if (principal.isDevice()) {
                mutated.header("X-Device-Id", principal.subject());
            }
            return chain.filter(exchange.mutate().request(mutated.build()).build());
        }

        return chain.filter(exchange);
    }

    private boolean isProtected(String path) {
        return protectedPrefixes.stream().anyMatch(path::startsWith);
    }

    private Mono<Void> reject(ServerWebExchange exchange, String reason) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add("WWW-Authenticate", "Bearer");
        log.debug("Rejecting {} {}: {}", exchange.getRequest().getMethod(), exchange.getRequest().getURI().getPath(), reason);
        return exchange.getResponse().setComplete();
    }

    /**
     * Run before the default routing/load-balancer filters so we can short-circuit
     * with 401 before the request hits a route. Spring Cloud Gateway's
     * {@code RoutePredicateHandlerMapping} runs at order = 1, so anything
     * negative beats it.
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
