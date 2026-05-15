package com.agentplatform.agent.chat;

import com.agentplatform.agent.ai.PersonaBundle;
import com.agentplatform.agent.ai.PersonaLoader;
import com.agentplatform.agent.ai.SkillRegistry;
import com.agentplatform.agent.client.AuthInternalClient;
import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.chat.MessageDto;
import com.agentplatform.api.chat.MessageRole;
import com.agentplatform.api.chat.SessionContextSummaryDto;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextAssemblerTest {

    @Test
    void replaysFullHistoryWhenSummaryIsMissing() {
        Fixture fixture = new Fixture(null);
        List<MessageDto> rows = messages(20, fixture.sessionId);
        when(fixture.chatClient.listMessages(fixture.sessionId, fixture.userId)).thenReturn(rows);

        ContextBundle bundle = fixture.assembler.assemble(fixture.userId, fixture.sessionId, "current", List.of());

        assertThat(bundle.historyRows()).extracting(MessageDto::content)
                .containsExactlyElementsOf(rows.stream().map(MessageDto::content).toList());
        assertThat(bundle.stats().recentHistoryMessages()).isEqualTo(20);
        assertThat(bundle.stats().summarizedMessages()).isZero();
        assertThat(bundle.userText()).doesNotContain("# SESSION SUMMARY");
    }

    @Test
    void replaysFullHistoryWhenSummaryIsBlank() {
        SessionContextSummaryDto blank = summary("   ", 8);
        Fixture fixture = new Fixture(blank);
        List<MessageDto> rows = messages(20, fixture.sessionId);
        when(fixture.chatClient.listMessages(fixture.sessionId, fixture.userId)).thenReturn(rows);

        ContextBundle bundle = fixture.assembler.assemble(fixture.userId, fixture.sessionId, "current", List.of());

        assertThat(bundle.historyRows()).hasSize(20);
        assertThat(bundle.stats().summarizedMessages()).isZero();
        assertThat(bundle.userText()).doesNotContain("# SESSION SUMMARY");
    }

    @Test
    void trimsHistoryOnlyWhenSummaryIsUsable() {
        SessionContextSummaryDto summary = summary("User asked to preserve context.", 8);
        Fixture fixture = new Fixture(summary);
        List<MessageDto> rows = messages(20, fixture.sessionId);
        when(fixture.chatClient.listMessages(fixture.sessionId, fixture.userId)).thenReturn(rows);

        ContextBundle bundle = fixture.assembler.assemble(fixture.userId, fixture.sessionId, "current", List.of());

        assertThat(bundle.historyRows()).extracting(MessageDto::content)
                .containsExactly("m15", "m16", "m17", "m18", "m19", "m20");
        assertThat(bundle.stats().recentHistoryMessages()).isEqualTo(6);
        assertThat(bundle.stats().summarizedMessages()).isEqualTo(14);
        assertThat(bundle.userText()).contains("# SESSION SUMMARY");
        assertThat(bundle.userText()).contains("Covered messages: 8");
    }

    private static List<MessageDto> messages(int count, UUID sessionId) {
        List<MessageDto> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(new MessageDto(
                    UUID.randomUUID(),
                    sessionId,
                    i % 2 == 0 ? MessageRole.ASSISTANT : MessageRole.USER,
                    "m" + i,
                    null,
                    OffsetDateTime.now().plusSeconds(i)));
        }
        return rows;
    }

    private static SessionContextSummaryDto summary(String text, int coveredMessages) {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        return new SessionContextSummaryDto(
                UUID.randomUUID(),
                sessionId,
                userId,
                UUID.randomUUID(),
                coveredMessages,
                text,
                12,
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    private static AgentProperties props() {
        return new AgentProperties(
                new AgentProperties.Jwt("secret", "issuer"),
                new AgentProperties.Agent(
                        35_000,
                        "http://device-hub-service:8080",
                        4096,
                        24,
                        24,
                        10,
                        List.of(),
                        new AgentProperties.Memory(
                                null,
                                0,
                                0,
                                null,
                                false,
                                0,
                                null,
                                0,
                                false,
                                0,
                                null,
                                null,
                                null,
                                null,
                                6,
                                18,
                                48_000,
                                1_200,
                                true),
                        null));
    }

    private static class Fixture {
        final UUID userId = UUID.randomUUID();
        final UUID sessionId = UUID.randomUUID();
        final InternalChatFeignClient chatClient = mock(InternalChatFeignClient.class);
        final ContextAssembler assembler;

        Fixture(SessionContextSummaryDto summary) {
            PersonaLoader personaLoader = mock(PersonaLoader.class);
            when(personaLoader.getBundle()).thenReturn(new PersonaBundle("identity", "soul", "agents", "tools"));

            AuthInternalClient authClient = mock(AuthInternalClient.class);
            SkillRegistry skillRegistry = new SkillRegistry(chatClient);
            HistoryReplayer historyReplayer = new HistoryReplayer(chatClient);
            AgentProperties props = props();
            when(chatClient.listArtifacts(sessionId, userId, 12)).thenReturn(List.of());
            when(chatClient.getContextSummary(sessionId, userId)).thenReturn(summary);

            assembler = new ContextAssembler(
                    personaLoader,
                    skillRegistry,
                    authClient,
                    chatClient,
                    props,
                    historyReplayer);
        }
    }
}
