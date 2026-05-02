package com.agentplatform.hub.ws;

import com.agentplatform.hub.config.HubProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class DeviceWebSocketConfig implements WebSocketConfigurer {

    private static final String ENDPOINT = "/ws/device";

    private final DeviceWsHandler handler;
    private final DeviceHandshakeInterceptor interceptor;
    private final HubProperties props;

    public DeviceWebSocketConfig(DeviceWsHandler handler,
                                 DeviceHandshakeInterceptor interceptor,
                                 HubProperties props) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, ENDPOINT)
                .addInterceptors(interceptor)
                .setHandshakeHandler(new TokenAwareHandshakeHandler())
                .setAllowedOrigins(props.wsAllowedOriginsOrDefault().toArray(new String[0]));
    }
}
