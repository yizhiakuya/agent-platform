package com.agentplatform.auth.controller;

import com.agentplatform.api.auth.UserPreferenceDto;
import com.agentplatform.auth.service.UserPreferenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal endpoint — only the gateway / sibling services are supposed to
 * reach this. The gateway never proxies {@code /internal/**} from public
 * clients (its route table excludes the prefix), and {@code PathBasedJwtFilter}
 * does not protect {@code /internal/**} either. agent-service calls this via
 * {@code AuthInternalClient} when building the LLM system prompt, so it can
 * inject the user's preference document.
 */
@RestController
@RequestMapping("/internal/users")
public class InternalUserPreferenceController {

    private final UserPreferenceService service;

    public InternalUserPreferenceController(UserPreferenceService service) {
        this.service = service;
    }

    @GetMapping("/{id}/preferences")
    public UserPreferenceDto getPreferences(@PathVariable("id") UUID userId) {
        return service.get(userId);
    }
}
