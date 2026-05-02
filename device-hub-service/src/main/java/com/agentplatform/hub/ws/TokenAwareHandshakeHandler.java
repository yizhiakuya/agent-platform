package com.agentplatform.hub.ws;

import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.util.List;

/**
 * Spring's default subprotocol negotiation requires the handler to declare the
 * exact strings it supports — but our protocol carries the bearer token inline:
 * client sends {@code Sec-WebSocket-Protocol: bearer.<jwt>}. Token is dynamic,
 * so we can't enumerate it.
 *
 * <p>This handshake handler accepts any subprotocol that starts with
 * {@code "bearer."} and echoes the SAME value back. RFC 6455 requires the
 * server-selected subprotocol to appear in the client's offered list — if we
 * returned the constant {@code "bearer"} instead, OkHttp / browser clients
 * would treat it as a protocol mismatch and immediately close the connection
 * with a reconnect-loop. The bearer token in the echoed value is information
 * the client already has, so this isn't a leak.
 *
 * <p>Token verification itself happens in {@link DeviceHandshakeInterceptor}.
 */
public class TokenAwareHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected String selectProtocol(List<String> requestedProtocols, WebSocketHandler webSocketHandler) {
        if (requestedProtocols == null) return null;
        for (String p : requestedProtocols) {
            if (p != null && p.startsWith("bearer.")) {
                return p;   // echo back identical to what the client sent
            }
        }
        return null;
    }
}
