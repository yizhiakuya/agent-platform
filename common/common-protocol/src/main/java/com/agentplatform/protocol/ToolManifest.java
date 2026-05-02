package com.agentplatform.protocol;

import java.util.List;

/**
 * Carries every tool a device exposes. Sent by the device as a
 * {@link JsonRpcMethods#TOOL_MANIFEST} notification right after the
 * {@link JsonRpcMethods#HELLO} handshake completes.
 */
public record ToolManifest(List<ToolSpec> tools) {

    public ToolManifest {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
