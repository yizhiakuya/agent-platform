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

    /** Version tag for the result and metadata contract this tool follows. */
    val schemaVersion: String
        get() = "agent_tool_contract/v1"

    /** Primary tool class from docs/agent-tool-contract-v1.md. */
    val toolClass: String
        get() = when (name.substringBefore('.')) {
            "photos", "videos" -> when {
                name.contains(".get_") -> "inspect"
                name.contains(".list_") || name.contains(".recent_") || name.contains(".semantic_") -> "search"
                else -> "act"
            }
            "ui" -> when {
                name == "ui.dump_tree" || name == "ui.screen_capture" || name == "ui.list_apps" -> "verify"
                else -> "act"
            }
            else -> "act"
        }

    /** Safety tier used by the agent and UI to reason about confirmations. */
    val safetyLevel: String
        get() = when {
            confirmRequired -> "sensitive_action"
            toolClass == "act" -> "local_state"
            else -> "read_only"
        }

    /** Default frontend rendering policy when the result does not override it. */
    val defaultDisplayPolicy: String
        get() = when (toolClass) {
            "search" -> "show_grid"
            "inspect" -> "show_primary"
            "verify" -> "debug_only"
            else -> "debug_only"
        }

    /** Expected top-level result kind. Tool results may override per call. */
    val resultType: String
        get() = when (toolClass) {
            "search" -> "results"
            "inspect" -> "confirmed"
            "verify" -> "state"
            else -> "action"
        }

    /**
     * If true, the agent platform will pop a confirm dialog on the user's
     * device before {@link #execute} is invoked. Use for any tool that
     * sends/edits external state (delete photo, send SMS, ...).
     */
    val confirmRequired: Boolean
        get() = false

    suspend fun execute(args: JsonNode): JsonNode
}
