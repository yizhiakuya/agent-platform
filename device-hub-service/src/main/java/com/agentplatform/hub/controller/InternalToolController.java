package com.agentplatform.hub.controller;

import com.agentplatform.api.auth.DeviceSeenRequest;
import com.agentplatform.hub.call.PendingCallRegistry;
import com.agentplatform.hub.client.AuthInternalClient;
import com.agentplatform.hub.config.HubProperties;
import com.agentplatform.api.hub.InternalCallRequest;
import com.agentplatform.hub.registry.DeviceProvisioner;
import com.agentplatform.hub.registry.DeviceRegistry;
import com.agentplatform.hub.registry.DeviceSession;
import com.agentplatform.protocol.JsonRpcMethods;
import com.agentplatform.protocol.JsonRpcNotification;
import com.agentplatform.protocol.JsonRpcRequest;
import com.agentplatform.protocol.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Async tool-call entry point. Returns a {@link DeferredResult} so the Tomcat
 * worker is freed immediately; {@link PendingCallRegistry} resolves the
 * DeferredResult when the device's response (or timeout / cancel) lands.
 */
@RestController
@RequestMapping("/internal/tools")
public class InternalToolController {

    private static final Logger log = LoggerFactory.getLogger(InternalToolController.class);

    private final DeviceRegistry registry;
    private final DeviceProvisioner provisioner;
    private final PendingCallRegistry pendingCalls;
    private final HubProperties props;
    private final ObjectMapper mapper;
    private final AuthInternalClient authClient;

    public InternalToolController(DeviceRegistry registry,
                                  DeviceProvisioner provisioner,
                                  PendingCallRegistry pendingCalls,
                                  HubProperties props,
                                  ObjectMapper mapper,
                                  AuthInternalClient authClient) {
        this.registry = registry;
        this.provisioner = provisioner;
        this.pendingCalls = pendingCalls;
        this.props = props;
        this.mapper = mapper;
        this.authClient = authClient;
    }

    @PostMapping("/call")
    public DeferredResult<ToolResult> call(@RequestBody InternalCallRequest req) {
        if (req.deviceId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId required");
        }
        if (req.toolName() == null || req.toolName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "toolName required");
        }

        DeviceSession session = activeSessionOrProvision(req);
        if (req.userId() != null && !session.userId().equals(req.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Device " + req.deviceId() + " does not belong to this user");
        }
        if (!isDeviceStillActive(session)) {
            registry.offline(session.deviceId());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Device " + req.deviceId() + " is no longer active");
        }

        UUID callId = UUID.randomUUID();
        long timeoutMs = req.timeoutMs() != null && req.timeoutMs() > 0
                ? req.timeoutMs() : props.toolCallTimeoutMs();

        DeviceSession finalSession = session;
        DeferredResult<ToolResult> dr = pendingCalls.register(callId, timeoutMs, () -> {
            try {
                ObjectNode params = mapper.createObjectNode();
                params.put("call_id", callId.toString());
                finalSession.send(new JsonRpcNotification(JsonRpcMethods.CANCEL, params));
            } catch (Exception e) {
                log.debug("Cancel-on-timeout notify failed for {}: {}", callId, e.getMessage());
            }
        });

        ObjectNode params = mapper.createObjectNode();
        params.put("tool", req.toolName());
        params.set("args", req.args() == null ? mapper.createObjectNode() : req.args());
        session.send(new JsonRpcRequest(callId.toString(), JsonRpcMethods.TOOL_CALL, params));

        return dr;
    }

    private DeviceSession activeSessionOrProvision(InternalCallRequest req) {
        DeviceSession session = registry.getSession(req.deviceId()).filter(DeviceSession::isOpen).orElse(null);
        if (session != null) {
            return session;
        }
        DeviceSession provisioned = provisioner.provision(req.deviceId(),
                req.userId() != null ? req.userId() : new UUID(0L, 0L));
        if (provisioned == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Device " + req.deviceId() + " is offline");
        }
        registry.online(provisioned);
        log.info("Auto-provisioned session for device {} ({})", req.deviceId(),
                provisioned.getClass().getSimpleName());
        return provisioned;
    }

    private boolean isDeviceStillActive(DeviceSession session) {
        try {
            authClient.markDeviceSeen(new DeviceSeenRequest(
                    session.deviceId(), session.userId(), OffsetDateTime.now()));
            return true;
        } catch (Exception e) {
            log.warn("Device {} failed active check before tool call: {}", session.deviceId(), e.toString());
            return false;
        }
    }
}
