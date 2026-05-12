package com.agentplatform.android.core.tool

import com.fasterxml.jackson.databind.JsonNode

/**
 * Single capability the device exposes.
 *
 * <p>All execution is suspending so tool implementations can do {@code IO}
 * dispatches without blocking OkHttp's WebSocket thread.
 */
interface Tool {
    val name: String
    val description: String

    /** JSON Schema describing this tool's args object. */
    val schema: JsonNode

    /**
     * If true, the agent platform will pop a confirm dialog on the user's
     * device before {@link #execute} is invoked. Use for any tool that
     * sends/edits external state (delete photo, send SMS, ...).
     */
    val confirmRequired: Boolean
        get() = false

    suspend fun execute(args: JsonNode): JsonNode
}
