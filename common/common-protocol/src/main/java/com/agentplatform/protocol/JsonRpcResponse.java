package com.agentplatform.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 response: correlated to a request by {@code id}. Exactly one of
 * {@code result} / {@code error} is non-null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcResponse(
        String jsonrpc,
        String id,
        JsonNode result,
        JsonRpcError error
) implements JsonRpcMessage {

    public static JsonRpcResponse success(String id, JsonNode result) {
        return new JsonRpcResponse(VERSION, id, result, null);
    }

    public static JsonRpcResponse failure(String id, JsonRpcError error) {
        return new JsonRpcResponse(VERSION, id, null, error);
    }

    public boolean hasError() {
        return error != null;
    }
}
