package com.agentplatform.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Encode and decode {@link JsonRpcMessage} on the wire.
 *
 * <p>Encoding is just Jackson serialisation — Jackson handles the {@code @JsonInclude(NON_NULL)}
 * on each record so we never emit empty fields. Decoding cannot rely on a discriminator field
 * (the spec doesn't define one) so we look at which of {@code id} and {@code method} are present:
 * <ul>
 *   <li>both → {@link JsonRpcRequest}</li>
 *   <li>only {@code method} → {@link JsonRpcNotification}</li>
 *   <li>only {@code id} → {@link JsonRpcResponse}</li>
 * </ul>
 */
public final class JsonRpcCodec {

    private final ObjectMapper mapper;

    public JsonRpcCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(JsonRpcMessage msg) {
        try {
            return mapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise JSON-RPC message", e);
        }
    }

    public JsonRpcMessage decode(String json) throws IOException {
        JsonNode node = mapper.readTree(json);
        boolean hasId = node.has("id") && !node.get("id").isNull();
        boolean hasMethod = node.has("method") && !node.get("method").isNull();

        if (hasMethod && hasId) {
            return mapper.treeToValue(node, JsonRpcRequest.class);
        }
        if (hasMethod) {
            return mapper.treeToValue(node, JsonRpcNotification.class);
        }
        if (hasId) {
            return mapper.treeToValue(node, JsonRpcResponse.class);
        }
        throw new IOException("Not a valid JSON-RPC message (no id and no method): " + json);
    }
}
