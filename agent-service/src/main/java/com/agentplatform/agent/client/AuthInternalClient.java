package com.agentplatform.agent.client;

import com.agentplatform.api.auth.UserPreferenceDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

/**
 * Feign client to auth-service's internal endpoints. Resolved through Nacos
 * discovery + Spring Cloud LoadBalancer ({@code lb://auth-service} via the
 * {@code @FeignClient} name). Currently exposes the user preference document
 * so {@code ChatService} can inject it into the LLM system prompt.
 */
@FeignClient(name = "auth-service", contextId = "internalAuth", path = "/internal")
public interface AuthInternalClient {

    @GetMapping("/users/{id}/preferences")
    UserPreferenceDto getPreferences(@PathVariable("id") UUID userId);
}
