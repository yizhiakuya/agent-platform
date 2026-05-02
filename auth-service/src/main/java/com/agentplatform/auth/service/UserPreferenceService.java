package com.agentplatform.auth.service;

import com.agentplatform.api.auth.UserPreferenceDto;
import com.agentplatform.auth.entity.UserPreference;
import com.agentplatform.auth.repository.UserPreferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * CRUD for per-user preference documents. {@link #get(UUID)} returns an empty
 * document when no row exists yet (so callers — both the user's web UI and
 * agent-service's prompt builder — never have to special-case "first time").
 * {@link #put(UUID, String)} upserts: insert if missing, update content and
 * bump {@code updated_at} otherwise.
 */
@Service
public class UserPreferenceService {

    private final UserPreferenceRepository repo;

    public UserPreferenceService(UserPreferenceRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public UserPreferenceDto get(UUID userId) {
        return repo.findById(userId)
                .map(p -> new UserPreferenceDto(p.getContent(), p.getUpdatedAt()))
                .orElseGet(() -> new UserPreferenceDto("", null));
    }

    @Transactional
    public UserPreferenceDto put(UUID userId, String content) {
        String safe = content == null ? "" : content;
        UserPreference entity = repo.findById(userId).orElseGet(() -> {
            UserPreference fresh = new UserPreference();
            fresh.setUserId(userId);
            return fresh;
        });
        entity.setContent(safe);
        entity.setUpdatedAt(OffsetDateTime.now());
        UserPreference saved = repo.save(entity);
        return new UserPreferenceDto(saved.getContent(), saved.getUpdatedAt());
    }
}
