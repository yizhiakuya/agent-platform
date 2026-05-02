package com.agentplatform.hub.registry;

import com.agentplatform.hub.call.PendingCallRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;

public class MockDeviceProvisioner implements DeviceProvisioner {

    private final ScheduledExecutorService executor;
    private final PendingCallRegistry pendingCalls;
    private final ObjectMapper mapper;
    private final long fakeLatencyMs;

    public MockDeviceProvisioner(ScheduledExecutorService executor,
                                 PendingCallRegistry pendingCalls,
                                 ObjectMapper mapper,
                                 long fakeLatencyMs) {
        this.executor = executor;
        this.pendingCalls = pendingCalls;
        this.mapper = mapper;
        this.fakeLatencyMs = fakeLatencyMs;
    }

    @Override
    public DeviceSession provision(UUID deviceId, UUID userId) {
        return new MockDeviceSession(deviceId, userId, executor, pendingCalls, mapper, fakeLatencyMs);
    }
}
