package com.agentplatform.agent.ai;

import com.agentplatform.agent.client.DeviceHubClient;
import com.agentplatform.agent.client.DeviceToolDispatcher;
import com.agentplatform.agent.config.AgentProperties;
import com.agentplatform.api.hub.OnlineDeviceDto;
import com.agentplatform.protocol.ToolSpec;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves the per-user, per-request set of remote device tools and packages
 * them as a {@link ResolvedTools} bundle for {@code ChatService}'s agentic
 * loop.
 *
 * <p>Called once per chat request: fetches the user's online devices from
 * device-hub, builds one {@link RemoteToolCallback} per (device, tool) pair,
 * and produces both the SDK-native {@link Tool} list (for
 * {@code MessageCreateParams.tools}) and a name-keyed dispatch map (for
 * routing {@code tool_use} blocks back to the matching callback).
 *
 * <p>For PR 8 only the first online device is used (single-device bias keeps
 * tool names un-namespaced). Multi-device support with alias prefixes lands in
 * PR 13/14.
 */
@Component
public class RemoteDeviceToolCallbackProvider {

    private final DeviceHubClient deviceHubClient;
    private final DeviceToolDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final List<ToolPreInterceptor> preInterceptors;
    private final ApplicationEventPublisher events;
    private final boolean visionToolResults;

    public RemoteDeviceToolCallbackProvider(DeviceHubClient deviceHubClient,
                                            DeviceToolDispatcher dispatcher,
                                            ObjectMapper mapper,
                                            List<ToolPreInterceptor> preInterceptors,
                                            ApplicationEventPublisher events,
                                            AgentProperties props) {
        this.deviceHubClient = deviceHubClient;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
        this.preInterceptors = preInterceptors;
        this.events = events;
        this.visionToolResults = props != null && props.agent() != null && props.agent().memory() != null
                && Boolean.TRUE.equals(props.agent().memory().enableVisionToolResults());
    }

    /**
     * Resolve the live tool set for {@code userId}. Empty bundle (zero tools,
     * empty dispatch map) when the user has no online devices or the device
     * hasn't yet reported a manifest.
     */
    public ResolvedTools getForUser(UUID userId) {
        List<OnlineDeviceDto> online = deviceHubClient.listOnlineByUser(userId);
        if (online == null || online.isEmpty()) {
            return new ResolvedTools(List.of(), Map.of());
        }
        OnlineDeviceDto first = online.get(0);
        if (first.manifest() == null || first.manifest().tools() == null) {
            return new ResolvedTools(List.of(), Map.of());
        }
        List<ToolSpec> specs = first.manifest().tools();
        List<Tool> defs = new ArrayList<>(specs.size());
        Map<String, RemoteToolCallback> dispatch = new HashMap<>(specs.size() * 2);
        for (ToolSpec spec : specs) {
            RemoteToolCallback cb = new RemoteToolCallback(
                    first.deviceId(), userId, spec, dispatcher, mapper,
                    preInterceptors, events, visionToolResults);
            defs.add(cb.toAnthropicTool());
            dispatch.put(cb.name(), cb);
        }
        return new ResolvedTools(defs, dispatch);
    }
}
