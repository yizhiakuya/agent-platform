package com.agentplatform.agent.ai;

import com.agentplatform.agent.client.DeviceHubClient;
import com.agentplatform.agent.client.DeviceToolDispatcher;
import com.agentplatform.agent.client.InternalChatFeignClient;
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
 * <p>v0: only the first online device of the user is used (single-device
 * bias keeps tool names un-namespaced). Multi-device with alias prefixes is
 * not yet wired in — see plan stage 2 roadmap.
 */
@Component
public class RemoteDeviceToolCallbackProvider {

    private final DeviceHubClient deviceHubClient;
    private final DeviceToolDispatcher dispatcher;
    private final ObjectMapper mapper;
    private final EmbeddingService embeddingService;
    private final PhotoEmbeddingService photoEmbeddingService;
    private final InternalChatFeignClient internalChatClient;
    private final List<ToolPreInterceptor> preInterceptors;
    private final ApplicationEventPublisher events;
    private final boolean visionToolResults;
    private final AgentProperties props;

    public RemoteDeviceToolCallbackProvider(DeviceHubClient deviceHubClient,
                                            DeviceToolDispatcher dispatcher,
                                            ObjectMapper mapper,
                                            EmbeddingService embeddingService,
                                            PhotoEmbeddingService photoEmbeddingService,
                                            InternalChatFeignClient internalChatClient,
                                            List<ToolPreInterceptor> preInterceptors,
                                            ApplicationEventPublisher events,
                                            AgentProperties props) {
        this.deviceHubClient = deviceHubClient;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
        this.embeddingService = embeddingService;
        this.photoEmbeddingService = photoEmbeddingService;
        this.internalChatClient = internalChatClient;
        this.preInterceptors = preInterceptors;
        this.events = events;
        this.props = props;
        this.visionToolResults = props != null && props.agent() != null && props.agent().memory() != null
                && Boolean.TRUE.equals(props.agent().memory().enableVisionToolResults());
    }

    /**
     * Resolve the live tool set for {@code userId}. Empty bundle (zero tools,
     * empty dispatch map) when the user has no online devices or the device
     * hasn't yet reported a manifest.
     */
    public ResolvedTools getForUser(UUID userId) {
        return getForUser(userId, null);
    }

    public ResolvedTools getForUser(UUID userId, UUID preferredDeviceId) {
        List<OnlineDeviceDto> online = deviceHubClient.listOnlineByUser(userId);
        if (online == null || online.isEmpty()) {
            return new ResolvedTools(List.of(), Map.of());
        }
        OnlineDeviceDto selected = selectDevice(online, preferredDeviceId);
        if (selected == null) {
            return new ResolvedTools(List.of(), Map.of());
        }
        if (selected.manifest() == null || selected.manifest().tools() == null) {
            return new ResolvedTools(List.of(), Map.of());
        }
        List<ToolSpec> specs = selected.manifest().tools();
        List<Tool> defs = new ArrayList<>(specs.size() + 1);
        Map<String, RemoteToolCallback> dispatch = new HashMap<>((specs.size() + 1) * 2);
        boolean hasSemanticCandidates = false;
        for (ToolSpec spec : specs) {
            if ("photos.semantic_candidates".equals(spec.name())) {
                hasSemanticCandidates = true;
                continue;
            }
            RemoteToolCallback cb = new RemoteToolCallback(new RemoteToolCallback.RemoteToolContext()
                    .withDeviceId(selected.deviceId())
                    .withUserId(userId)
                    .withSpec(spec)
                    .withDispatcher(dispatcher)
                    .withMapper(mapper)
                    .withPreInterceptors(preInterceptors)
                    .withEvents(events)
                    .withVisionEnabled(visionToolResults));
            defs.add(cb.toAnthropicTool());
            dispatch.put(cb.name(), cb);
        }
        if (hasSemanticCandidates) {
            SemanticPhotoSearchCallback cb = new SemanticPhotoSearchCallback(
                    selected.deviceId(), userId, dispatcher, mapper, embeddingService,
                    photoEmbeddingService, internalChatClient, events, visionToolResults, props);
            defs.add(cb.toAnthropicTool());
            dispatch.put(cb.name(), cb);
        }
        return new ResolvedTools(defs, dispatch);
    }

    private OnlineDeviceDto selectDevice(List<OnlineDeviceDto> online, UUID preferredDeviceId) {
        if (preferredDeviceId == null) {
            return online.get(0);
        }
        return online.stream()
                .filter(d -> preferredDeviceId.equals(d.deviceId()))
                .findFirst()
                .orElse(null);
    }
}
