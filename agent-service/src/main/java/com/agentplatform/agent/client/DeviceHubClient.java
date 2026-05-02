package com.agentplatform.agent.client;

import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.hub.OnlineDeviceDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Read-side adapter to device-hub's {@code /internal/devices/online} endpoint.
 * Uses {@code lb://device-hub-service} (Spring Cloud LoadBalancer + Nacos).
 */
@Component
public class DeviceHubClient {

    private static final Logger log = LoggerFactory.getLogger(DeviceHubClient.class);

    private final WebClient webClient;
    private final String baseUri;

    public DeviceHubClient(WebClient hubWebClient, AgentProperties props) {
        this.webClient = hubWebClient;
        this.baseUri = props.agent().hubBaseUri();
    }

    public List<OnlineDeviceDto> listOnlineByUser(UUID userId) {
        try {
            return webClient.get()
                    .uri(baseUri + "/internal/devices/online?userId=" + userId)
                    .retrieve()
                    .bodyToFlux(OnlineDeviceDto.class)
                    .collectList()
                    .block(Duration.ofSeconds(5));
        } catch (Exception e) {
            log.warn("listOnlineByUser({}) failed: {}", userId, e.getMessage());
            return List.of();
        }
    }
}
