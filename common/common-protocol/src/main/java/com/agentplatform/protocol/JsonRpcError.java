package com.agentplatform.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 error object. The {@code code} field uses the standard reserved
 * range for protocol-level errors and a private range for application errors.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcError(int code, String message, JsonNode data) {

    public JsonRpcError(int code, String message) {
        this(code, message, null);
    }

    /** -32700: server received invalid JSON */
    public static final int PARSE_ERROR = -32700;
    /** -32600: payload is not a valid JSON-RPC request */
    public static final int INVALID_REQUEST = -32600;
    /** -32601: method does not exist or is not exposed */
    public static final int METHOD_NOT_FOUND = -32601;
    /** -32602: method exists but parameters are invalid */
    public static final int INVALID_PARAMS = -32602;
    /** -32603: server-side internal error */
    public static final int INTERNAL_ERROR = -32603;

    /** -32001: tool name unknown to the device */
    public static final int TOOL_NOT_FOUND = -32001;
    /** -32002: tool execution timed out before returning a result */
    public static final int TOOL_TIMEOUT = -32002;
    /** -32003: tool call cancelled by server or user */
    public static final int TOOL_CANCELLED = -32003;
    /** -32004: user denied the confirmation step */
    public static final int CONFIRMATION_REJECTED = -32004;
    /** -32005: target device is not currently connected */
    public static final int DEVICE_OFFLINE = -32005;
}
