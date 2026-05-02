package com.agentplatform.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonRpcCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonRpcCodec codec = new JsonRpcCodec(mapper);

    @Test
    void roundtrip_request() throws IOException {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("limit", 5);
        JsonRpcRequest req = new JsonRpcRequest("call-1", JsonRpcMethods.TOOL_CALL, params);

        String wire = codec.encode(req);
        JsonRpcMessage decoded = codec.decode(wire);

        assertThat(decoded).isInstanceOf(JsonRpcRequest.class);
        JsonRpcRequest decReq = (JsonRpcRequest) decoded;
        assertThat(decReq.id()).isEqualTo("call-1");
        assertThat(decReq.method()).isEqualTo("tool.call");
        assertThat(decReq.params().get("limit").asInt()).isEqualTo(5);
        assertThat(decReq.jsonrpc()).isEqualTo("2.0");
    }

    @Test
    void roundtrip_response_success() throws IOException {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("count", 42);
        JsonRpcResponse resp = JsonRpcResponse.success("call-1", result);

        JsonRpcResponse decoded = (JsonRpcResponse) codec.decode(codec.encode(resp));

        assertThat(decoded.id()).isEqualTo("call-1");
        assertThat(decoded.result().get("count").asInt()).isEqualTo(42);
        assertThat(decoded.error()).isNull();
        assertThat(decoded.hasError()).isFalse();
    }

    @Test
    void roundtrip_response_error() throws IOException {
        JsonRpcError err = new JsonRpcError(JsonRpcError.TOOL_TIMEOUT, "Tool timed out after 30s");
        JsonRpcResponse resp = JsonRpcResponse.failure("call-1", err);

        JsonRpcResponse decoded = (JsonRpcResponse) codec.decode(codec.encode(resp));

        assertThat(decoded.error().code()).isEqualTo(JsonRpcError.TOOL_TIMEOUT);
        assertThat(decoded.error().message()).isEqualTo("Tool timed out after 30s");
        assertThat(decoded.result()).isNull();
        assertThat(decoded.hasError()).isTrue();
    }

    @Test
    void roundtrip_notification() throws IOException {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("call_id", "abc");
        params.put("progress", 0.5);
        JsonRpcNotification note = new JsonRpcNotification(JsonRpcMethods.PROGRESS, params);

        JsonRpcMessage decoded = codec.decode(codec.encode(note));

        assertThat(decoded).isInstanceOf(JsonRpcNotification.class);
        JsonRpcNotification dn = (JsonRpcNotification) decoded;
        assertThat(dn.method()).isEqualTo("$/progress");
        assertThat(dn.params().get("progress").asDouble()).isEqualTo(0.5);
    }

    @Test
    void encoded_request_omits_null_params() {
        JsonRpcRequest req = new JsonRpcRequest("id-1", JsonRpcMethods.HELLO, null);
        String wire = codec.encode(req);
        assertThat(wire).doesNotContain("\"params\"");
        assertThat(wire).contains("\"jsonrpc\":\"2.0\"");
        assertThat(wire).contains("\"id\":\"id-1\"");
    }

    @Test
    void decode_message_with_neither_id_nor_method_fails() {
        assertThatThrownBy(() -> codec.decode("{\"jsonrpc\":\"2.0\"}"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Not a valid JSON-RPC message");
    }
}
