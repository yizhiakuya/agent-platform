package com.agentplatform.protocol;

/**
 * Sealed root of JSON-RPC 2.0 messages exchanged between server and Android devices.
 *
 * <p>The wire format always carries {@code "jsonrpc": "2.0"}. Distinguishing the three
 * concrete subtypes requires inspecting which fields are present:
 * <ul>
 *   <li>{@link JsonRpcRequest}      — has both {@code id} and {@code method}</li>
 *   <li>{@link JsonRpcResponse}     — has {@code id}, plus exactly one of {@code result}/{@code error}</li>
 *   <li>{@link JsonRpcNotification} — has {@code method}, no {@code id}</li>
 * </ul>
 * Use {@link JsonRpcCodec#decode(String)} to deserialise a wire string to the right subtype.
 */
public sealed interface JsonRpcMessage
        permits JsonRpcRequest, JsonRpcResponse, JsonRpcNotification {
    String VERSION = "2.0";

    String jsonrpc();
}
