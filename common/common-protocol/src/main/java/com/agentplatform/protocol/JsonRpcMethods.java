package com.agentplatform.protocol;

/**
 * Method names used in {@link JsonRpcRequest} and {@link JsonRpcNotification}.
 * Centralised here so server and Android client agree on the exact strings.
 */
public final class JsonRpcMethods {

    private JsonRpcMethods() {}

    /** device → server: handshake (request, returns sessionId in response). */
    public static final String HELLO = "hello";

    /** device → server: register the tools this device exposes (notification, sent post-hello). */
    public static final String TOOL_MANIFEST = "tool.manifest";

    /** device → server: ack of a confirmation prompt (notification). */
    public static final String CONFIRM_ACK = "tool.confirm.ack";

    /** device → server: app-level heartbeat (notification, in addition to WS ping/pong). */
    public static final String HEARTBEAT = "heartbeat";

    /** device ↔ server: progress event for a long-running tool call (notification). */
    public static final String PROGRESS = "$/progress";

    /** server → device: invoke a tool (request). */
    public static final String TOOL_CALL = "tool.call";

    /** server → device: ask user to approve a sensitive call (request). */
    public static final String TOOL_CONFIRM = "tool.confirm";

    /** server → device: cancel an in-flight call (notification). */
    public static final String CANCEL = "$/cancel";
}
