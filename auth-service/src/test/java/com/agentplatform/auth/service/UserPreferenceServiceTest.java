package com.agentplatform.auth.service;

import com.agentplatform.api.auth.UserPreferenceDto;
import com.agentplatform.auth.entity.UserPreference;
import com.agentplatform.auth.repository.UserPreferenceRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserPreferenceServiceTest {

    @Test
    void missingPreferenceDefaultsAutoMemoryOn() {
        UserPreferenceRepository repo = mock(UserPreferenceRepository.class);
        UUID userId = UUID.randomUUID();
        when(repo.findById(userId)).thenReturn(Optional.empty());

        UserPreferenceDto dto = new UserPreferenceService(repo).get(userId);

        assertThat(dto.content()).isEmpty();
        assertThat(dto.updatedAt()).isNull();
        assertThat(dto.autoMemoryEnabledOrDefault()).isTrue();
    }

    @Test
    void canToggleAutoMemoryWithoutChangingContent() {
        UserPreferenceRepository repo = mock(UserPreferenceRepository.class);
        UUID userId = UUID.randomUUID();
        UserPreference existing = new UserPreference();
        existing.setUserId(userId);
        existing.setContent("keep these rules");
        when(repo.findById(userId)).thenReturn(Optional.of(existing));
        when(repo.save(any(UserPreference.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserPreferenceDto dto = new UserPreferenceService(repo).put(userId, null, false);

        assertThat(dto.content()).isEqualTo("keep these rules");
        assertThat(dto.autoMemoryEnabledOrDefault()).isFalse();
    }
}
