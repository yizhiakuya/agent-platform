package com.agentplatform.hub.ws;

import com.agentplatform.hub.config.HubProperties;
import com.agentplatform.security.JwtUtil;
import com.agentplatform.security.Principal;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Validates the WS handshake before {@link DeviceWsHandler} sees the connection.
 *
 * <ol>
 *   <li>{@code Origin} must be in the allow-list (defends against CSWSH).</li>
 *   <li>One of the {@code Sec-WebSocket-Protocol} values must be {@code bearer.<jwt>}.</li>
 *   <li>The JWT must verify, must have type {@code device}, and yields the
 *       deviceId / userId stored in session attributes for the handler.</li>
 * </ol>
 */
@Component
public class DeviceHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DeviceHandshakeInterceptor.class);
    private static final String BEARER_PREFIX = "bearer.";
    public static final String ATTR_DEVICE_ID = "deviceId";
    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_JTI = "jti";

    private final JwtUtil jwt;
    private final List<String> allowedOrigins;
    private final boolean allowAnyOrigin;

    public DeviceHandshakeInterceptor(JwtUtil jwt, HubProperties props) {
        this.jwt = jwt;
        this.allowedOrigins = props.wsAllowedOriginsOrDefault();
        this.allowAnyOrigin = this.allowedOrigins.contains("*");
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res,
                                   WebSocketHandler wsHandler, Map<String, Object> attrs) {
        String origin = req.getHeaders().getOrigin();
        if (!allowAnyOrigin && origin != null && !allowedOrigins.contains(origin)) {
            res.setStatusCode(HttpStatus.FORBIDDEN);
            log.warn("WS handshake rejected: disallowed origin {}", origin);
            return false;
        }

        String token = extractBearerToken(req.getHeaders());
        if (token == null) {
            res.setStatusCode(HttpStatus.UNAUTHORIZED);
            log.warn("WS handshake rejected: no bearer.<jwt> in Sec-WebSocket-Protocol");
            return false;
        }

        try {
            Principal p = jwt.verify(token);
            if (!p.isDevice()) {
                res.setStatusCode(HttpStatus.FORBIDDEN);
                log.warn("WS handshake rejected: principal type is '{}', not 'device'", p.type());
                return false;
            }
            attrs.put(ATTR_DEVICE_ID, UUID.fromString(p.subject()));
            attrs.put(ATTR_USER_ID, UUID.fromString(p.userId()));
            attrs.put(ATTR_JTI, p.jti());
            log.debug("WS handshake OK for device {}", p.subject());
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            res.setStatusCode(HttpStatus.UNAUTHORIZED);
            log.warn("WS handshake rejected: invalid token ({})", e.getMessage());
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    /**
     * Sec-WebSocket-Protocol can appear once with comma-separated values, or
     * repeated. We scan all of them and return the first {@code bearer.*}.
     */
    private String extractBearerToken(HttpHeaders headers) {
        List<String> values = headers.get("Sec-WebSocket-Protocol");
        if (values == null) return null;
        for (String raw : values) {
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (trimmed.startsWith(BEARER_PREFIX) && trimmed.length() > BEARER_PREFIX.length()) {
                    return trimmed.substring(BEARER_PREFIX.length());
                }
            }
        }
        return null;
    }
}
