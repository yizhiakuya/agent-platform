package com.agentplatform.auth.controller;

import com.agentplatform.api.auth.UserPreferenceDto;
import com.agentplatform.auth.dto.UpdatePreferenceRequest;
import com.agentplatform.auth.service.UserPreferenceService;
import com.agentplatform.security.PrincipalContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * User-scoped preference document. Mounted at {@code /api/me/preferences},
 * which is inside {@code PathBasedJwtFilter}'s {@code /api/me} protected
 * prefix — a valid user JWT is required and the userId is taken from the
 * principal context (so users can only read/write their own document).
 */
@RestController
@RequestMapping("/api/me/preferences")
public class UserPreferenceController {

    private final UserPreferenceService service;

    public UserPreferenceController(UserPreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public UserPreferenceDto get() {
        UUID userId = PrincipalContext.requireUserId();
        return service.get(userId);
    }

    @PutMapping
    public UserPreferenceDto put(@RequestBody UpdatePreferenceRequest req) {
        UUID userId = PrincipalContext.requireUserId();
        return service.put(userId, req.content());
    }
}
