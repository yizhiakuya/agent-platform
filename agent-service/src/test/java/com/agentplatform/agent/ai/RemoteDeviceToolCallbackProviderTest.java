package com.agentplatform.agent.ai;

import com.agentplatform.agent.client.DeviceHubClient;
import com.agentplatform.agent.client.DeviceToolDispatcher;
import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.hub.OnlineDeviceDto;
import com.agentplatform.protocol.ToolManifest;
import com.agentplatform.protocol.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemoteDeviceToolCallbackProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preferredDeviceWinsWhenItIsOnline() {
        UUID userId = UUID.randomUUID();
        UUID firstDevice = UUID.randomUUID();
        UUID preferredDevice = UUID.randomUUID();
        DeviceHubClient hub = mock(DeviceHubClient.class);
        when(hub.listOnlineByUser(userId)).thenReturn(List.of(
                online(firstDevice, userId, "photos.list_recent"),
                online(preferredDevice, userId, "ui.open_app")));

        RemoteDeviceToolCallbackProvider provider = new RemoteDeviceToolCallbackProvider(
                hub,
                mock(DeviceToolDispatcher.class),
                mapper,
                mock(EmbeddingService.class),
                mock(PhotoEmbeddingService.class),
                mock(InternalChatFeignClient.class),
                List.of(),
                null,
                null);

        ResolvedTools tools = provider.getForUser(userId, preferredDevice);

        assertThat(tools.dispatch()).containsOnlyKeys("ui_open_app");
        RemoteToolCallback callback = tools.dispatch().get("ui_open_app");
        assertThat(callback.spec().name()).isEqualTo("ui.open_app");
    }

    @Test
    void returnsNoDeviceToolsWhenPreferredDeviceIsMissing() {
        UUID userId = UUID.randomUUID();
        UUID firstDevice = UUID.randomUUID();
        DeviceHubClient hub = mock(DeviceHubClient.class);
        when(hub.listOnlineByUser(userId)).thenReturn(List.of(
                online(firstDevice, userId, "photos.list_recent")));

        RemoteDeviceToolCallbackProvider provider = new RemoteDeviceToolCallbackProvider(
                hub,
                mock(DeviceToolDispatcher.class),
                mapper,
                mock(EmbeddingService.class),
                mock(PhotoEmbeddingService.class),
                mock(InternalChatFeignClient.class),
                List.of(),
                null,
                null);

        ResolvedTools tools = provider.getForUser(userId, UUID.randomUUID());

        assertThat(tools.dispatch()).isEmpty();
        assertThat(tools.definitions()).isEmpty();
    }

    private OnlineDeviceDto online(UUID deviceId, UUID userId, String toolName) {
        ToolSpec spec = new ToolSpec(
                toolName,
                "test tool",
                mapper.createObjectNode()
                        .put("type", "object")
                        .set("properties", mapper.createObjectNode()),
                false);
        return new OnlineDeviceDto(
                deviceId,
                userId,
                new ToolManifest(List.of(spec)),
                OffsetDateTime.now());
    }
}
