package com.agentplatform.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 request: needs both an {@code id} (so the response can correlate)
 * and a {@code method} name. Use {@link JsonRpcNotification} when no response is expected.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcRequest(
        String jsonrpc,
        String id,
        String method,
        JsonNode params
) implements JsonRpcMessage {

    public JsonRpcRequest(String id, String method, JsonNode params) {
        this(VERSION, id, method, params);
    }
}
