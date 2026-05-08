package com.agentplatform.agent.ai;

import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.api.chat.RuntimeSkillDto;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillRegistryRuntimeTest {

    @Test
    void allIncludesRuntimeSkillsForUser() {
        InternalChatFeignClient chat = mock(InternalChatFeignClient.class);
        SkillRegistry registry = new SkillRegistry(chat);
        UUID userId = UUID.randomUUID();
        RuntimeSkillDto runtime = skill(userId, "deploy-notes", "Deploy playbook", "body");
        when(chat.listRuntimeSkills(userId, false)).thenReturn(List.of(runtime));

        assertThat(registry.all(userId))
                .extracting(SkillDef::name)
                .contains("deploy-notes");
    }

    @Test
    void getPrefersRuntimeSkillOverPackagedSkill() {
        InternalChatFeignClient chat = mock(InternalChatFeignClient.class);
        SkillRegistry registry = new SkillRegistry(chat);
        UUID userId = UUID.randomUUID();
        RuntimeSkillDto runtime = skill(userId, "agent-platform", "Runtime override", "runtime body");
        when(chat.getRuntimeSkill("agent-platform", userId)).thenReturn(runtime);

        assertThat(registry.get(userId, "agent-platform"))
                .hasValueSatisfying(def -> {
                    assertThat(def.description()).isEqualTo("Runtime override");
                    assertThat(def.body()).isEqualTo("runtime body");
                });
    }

    private static RuntimeSkillDto skill(UUID userId, String name, String description, String body) {
        OffsetDateTime now = OffsetDateTime.now();
        return new RuntimeSkillDto(
                UUID.randomUUID(),
                userId,
                name,
                description,
                body,
                true,
                now,
                now);
    }
}
