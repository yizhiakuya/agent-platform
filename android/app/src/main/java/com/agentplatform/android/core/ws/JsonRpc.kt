package com.agentplatform.android.core.ws

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Wire protocol shared with `common-protocol` on the server (kept in sync by hand).
 * Only the subset Android needs.
 */

const val JSON_RPC_VERSION = "2.0"

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcRequest(
    val jsonrpc: String = JSON_RPC_VERSION,
    val id: String,
    val method: String,
    val params: JsonNode? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcResponse(
    val jsonrpc: String = JSON_RPC_VERSION,
    val id: String,
    val result: JsonNode? = null,
    val error: JsonRpcError? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcNotification(
    val jsonrpc: String = JSON_RPC_VERSION,
    val method: String,
    val params: JsonNode? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcError(val code: Int, val message: String, val data: JsonNode? = null) {
    companion object {
        const val INTERNAL_ERROR = -32603
        const val INVALID_PARAMS = -32602
        const val TOOL_NOT_FOUND = -32001
    }
}

object JsonRpcMethods {
    const val HELLO = "hello"
    const val TOOL_MANIFEST = "tool.manifest"
    const val TOOL_CALL = "tool.call"
    const val HEARTBEAT = "heartbeat"
    const val PROGRESS = "\$/progress"
    const val CANCEL = "\$/cancel"
}

/** Decide what kind of message landed by inspecting which fields are present. */
fun decodeJsonRpc(mapper: ObjectMapper, raw: String): Any? {
    val node = mapper.readTree(raw)
    val hasMethod = node.has("method") && !node.get("method").isNull
    val hasId = node.has("id") && !node.get("id").isNull
    return when {
        hasMethod && hasId -> mapper.treeToValue(node, JsonRpcRequest::class.java)
        hasMethod -> mapper.treeToValue(node, JsonRpcNotification::class.java)
        hasId -> mapper.treeToValue(node, JsonRpcResponse::class.java)
        else -> null
    }
}
