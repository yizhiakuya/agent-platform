package com.agentplatform.hub.config;

import com.agentplatform.hub.call.PendingCallRegistry;
import com.agentplatform.hub.registry.DeviceProvisioner;
import com.agentplatform.hub.registry.MockDeviceProvisioner;
import com.agentplatform.protocol.JsonRpcCodec;
import com.agentplatform.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class HubBeans {

    private static final Logger log = LoggerFactory.getLogger(HubBeans.class);

    /**
     * Single scheduled executor used by mock device sessions to fire fake
     * results, and by the timeout/cancel side of PendingCallRegistry.
     * Sized small — work items are O(microseconds), pure scheduling.
     */
    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService hubScheduler() {
        return Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "hub-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * In mock-mode, every unknown deviceId gets auto-provisioned with a
     * MockDeviceSession for hub-level tests or explicit local development
     * without a real Android device. When mock-mode is off, return a no-op provisioner that
     * never creates anything; missing devices then surface as 503 from
     * InternalToolController.
     */
    @Bean
    public DeviceProvisioner deviceProvisioner(HubProperties props,
                                               ScheduledExecutorService scheduler,
                                               PendingCallRegistry pendingCalls,
                                               ObjectMapper mapper) {
        if (props.mockMode()) {
            log.warn("[hub] mock-mode is ON — unknown devices will be auto-provisioned with fake sessions");
            return new MockDeviceProvisioner(scheduler, pendingCalls, mapper, props.mockFakeLatencyMs());
        }
        log.info("[hub] mock-mode OFF — devices must connect via real WebSocket");
        return DeviceProvisioner.noop();
    }

    /** Single shared JwtUtil used by the WebSocket handshake interceptor. */
    @Bean
    public JwtUtil jwtUtil(JwtProperties props) {
        return new JwtUtil(props.secret(), props.issuer());
    }

    /** Single shared codec for WS encode/decode and tests. */
    @Bean
    public JsonRpcCodec jsonRpcCodec(ObjectMapper mapper) {
        return new JsonRpcCodec(mapper);
    }

    @Bean
    public RequestInterceptor internalTokenFeignInterceptor(
            @Value("${agent-platform.internal.token:${agent-platform.jwt.secret}}") String internalToken) {
        return template -> template.header(com.agentplatform.security.InternalToken.HEADER, internalToken);
    }

    /**
     * Tomcat's default {@code maxTextMessageBufferSize} is only 8KB, which
     * truncates any tool result with a few base64 thumbnails
     * ({@code photos.list_recent} returns 5×256×256 JPEGs ≈ 50-100KB) and the
     * server closes the connection with code 1009. Bump to 4MB to match the
     * upload side-channel threshold; results larger than that should go via
     * {@code POST /api/uploads/...} instead of inline.
     */
    @Bean
    public ServletServerContainerFactoryBean wsServletContainer() {
        ServletServerContainerFactoryBean c = new OptionalWsContainerFactoryBean();
        c.setMaxTextMessageBufferSize(16 * 1024 * 1024);
        c.setMaxBinaryMessageBufferSize(16 * 1024 * 1024);
        c.setAsyncSendTimeout(10_000L);
        return c;
    }

    static class OptionalWsContainerFactoryBean extends ServletServerContainerFactoryBean {
        @Override
        public void afterPropertiesSet() {
            try {
                super.afterPropertiesSet();
            } catch (IllegalStateException e) {
                if (e.getMessage() != null
                        && e.getMessage().contains("jakarta.websocket.server.ServerContainer")) {
                    log.debug("[hub] skipping WS container tuning in MockServletContext");
                    return;
                }
                throw e;
            }
        }
    }
}
