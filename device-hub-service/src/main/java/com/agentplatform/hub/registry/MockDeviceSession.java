package com.agentplatform.hub.registry;

import com.agentplatform.hub.call.PendingCallRegistry;
import com.agentplatform.protocol.JsonRpcMessage;
import com.agentplatform.protocol.JsonRpcMethods;
import com.agentplatform.protocol.JsonRpcRequest;
import com.agentplatform.protocol.ToolManifest;
import com.agentplatform.protocol.ToolResult;
import com.agentplatform.protocol.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Network-free mock session. Whenever {@link #send} receives a
 * {@link JsonRpcMethods#TOOL_CALL} request, it schedules a fake successful
 * result {@code fakeLatencyMs} later, completing the corresponding callId in
 * {@link PendingCallRegistry}. This keeps controller async/timeout/cancel
 * paths testable without a real Android device.
 */
public class MockDeviceSession implements DeviceSession {

    private static final Logger log = LoggerFactory.getLogger(MockDeviceSession.class);

    private final UUID deviceId;
    private final UUID userId;
    private final OffsetDateTime connectedAt = OffsetDateTime.now();
    private final ScheduledExecutorService executor;
    private final PendingCallRegistry pendingCalls;
    private final ObjectMapper mapper;
    private final long fakeLatencyMs;

    private final AtomicReference<ToolManifest> manifestRef;
    private final AtomicBoolean open = new AtomicBoolean(true);

    public MockDeviceSession(UUID deviceId, UUID userId,
                             ScheduledExecutorService executor,
                             PendingCallRegistry pendingCalls,
                             ObjectMapper mapper,
                             long fakeLatencyMs) {
        this.deviceId = deviceId;
        this.userId = userId;
        this.executor = executor;
        this.pendingCalls = pendingCalls;
        this.mapper = mapper;
        this.fakeLatencyMs = fakeLatencyMs;
        this.manifestRef = new AtomicReference<>(defaultManifest(mapper));
    }

    private static ToolManifest defaultManifest(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        ObjectNode limit = mapper.createObjectNode();
        limit.put("type", "integer");
        limit.put("minimum", 1);
        limit.put("maximum", 100);
        props.set("limit", limit);
        schema.set("properties", props);
        schema.set("required", mapper.createArrayNode().add("limit"));

        return new ToolManifest(List.of(
                new ToolSpec(
                        "photos.list_recent",
                        "Mock: returns a fake recent-photos response without touching real storage.",
                        schema,
                        false)));
    }

    @Override public UUID deviceId() { return deviceId; }
    @Override public UUID userId() { return userId; }
    @Override public OffsetDateTime connectedAt() { return connectedAt; }
    @Override public boolean isOpen() { return open.get(); }
    @Override public ToolManifest manifest() { return manifestRef.get(); }
    @Override public void updateManifest(ToolManifest manifest) { manifestRef.set(manifest); }

    @Override
    public void send(JsonRpcMessage msg) {
        if (!open.get()) {
            log.warn("send to closed mock session {}", deviceId);
            return;
        }
        if (msg instanceof JsonRpcRequest req && JsonRpcMethods.TOOL_CALL.equals(req.method())) {
            UUID callId;
            try {
                callId = UUID.fromString(req.id());
            } catch (IllegalArgumentException e) {
                log.warn("Mock session got non-UUID call id '{}', ignoring", req.id());
                return;
            }
            executor.schedule(() -> deliverFakeResult(callId, req), fakeLatencyMs, TimeUnit.MILLISECONDS);
            return;
        }
        log.debug("Mock session {} ignoring {}", deviceId, msg);
    }

    private void deliverFakeResult(UUID callId, JsonRpcRequest req) {
        try {
            ObjectNode result = mapper.createObjectNode();
            result.put("mocked", true);
            result.put("device", deviceId.toString());
            if (req.params() != null) {
                result.set("echoed_params", req.params());
            }
            pendingCalls.complete(callId, ToolResult.ok(result));
        } catch (Exception e) {
            log.warn("Mock fake-result delivery failed for {}", callId, e);
            pendingCalls.cancel(callId, "Mock failure: " + e.getMessage());
        }
    }

    @Override
    public void close(String reason) {
        if (open.compareAndSet(true, false)) {
            log.debug("Mock session {} closed: {}", deviceId, reason);
        }
    }
}
