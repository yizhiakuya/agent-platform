package com.agentplatform.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 notification: a one-way message — the receiver does not respond.
 * Used for {@code tool.manifest}, heartbeats, progress events, cancellation, etc.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcNotification(
        String jsonrpc,
        String method,
        JsonNode params
) implements JsonRpcMessage {

    public JsonRpcNotification(String method, JsonNode params) {
        this(VERSION, method, params);
    }
}
