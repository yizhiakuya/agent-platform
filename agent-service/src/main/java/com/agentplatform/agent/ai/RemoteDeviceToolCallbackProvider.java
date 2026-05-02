package com.agentplatform.agent.ai;

import com.agentplatform.agent.client.DeviceHubClient;
import com.agentplatform.agent.client.DeviceToolDispatcher;
import com.agentplatform.api.hub.OnlineDeviceDto;
import com.agentplatform.protocol.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Bridges Spring AI's static-feeling {@code ToolCallbackProvider} contract to
 * our dynamic, per-user, per-request set of remote device tools.
 *
 * <p>Spring AI calls {@link #getToolCallbacks()} once per chat request (we wire
 * the provider into each {@code ChatClient.prompt()} call). On each call we
 * fetch the current user's online devices from device-hub and compose one
 * {@link RemoteToolCallback} per (device, tool) pair.
 *
 * <p>For PR 8 only the first online device is used (single-device bias keeps
 * tool names un-namespaced). Multi-device support with alias prefixes lands in
 * PR 13/14.
 */
@Component
public class RemoteDeviceToolCallbackProvider implements ToolCallbackProvider {

    private final DeviceHubClient deviceHubClient;
    private final DeviceToolDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final List<ToolPreInterceptor> preInterceptors;
    private final ApplicationEventPublisher events;

    public RemoteDeviceToolCallbackProvider(DeviceHubClient deviceHubClient,
                                            DeviceToolDispatcher dispatcher,
                                            ObjectMapper mapper,
                                            List<ToolPreInterceptor> preInterceptors,
                                            ApplicationEventPublisher events) {
        this.deviceHubClient = deviceHubClient;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
        this.preInterceptors = preInterceptors;
        this.events = events;
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        // Spring AI's provider contract has no caller context, so this overload
        // just returns no tools — ChatService always uses getForUser(...) below.
        return new ToolCallback[0];
    }

    /**
     * Per-user resolution used by {@code ChatService}. Spring AI doesn't have a
     * built-in per-request hook into the provider, so we manually call this and
     * pass the list to {@code ChatClient.prompt().toolCallbacks(...)}.
     */
    public ToolCallback[] getForUser(UUID userId) {
        List<OnlineDeviceDto> online = deviceHubClient.listOnlineByUser(userId);
        if (online == null || online.isEmpty()) {
            return new ToolCallback[0];
        }
        OnlineDeviceDto first = online.get(0);
        if (first.manifest() == null || first.manifest().tools() == null) {
            return new ToolCallback[0];
        }
        return Stream.<ToolSpec>concat(first.manifest().tools().stream(), Stream.empty())
                .map(spec -> (ToolCallback) new RemoteToolCallback(
                        first.deviceId(), userId, spec, dispatcher, mapper, preInterceptors, events))
                .toArray(ToolCallback[]::new);
    }
}
