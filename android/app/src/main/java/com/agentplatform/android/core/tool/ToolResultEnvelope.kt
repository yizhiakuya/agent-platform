package com.agentplatform.android.core.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

object ToolResultEnvelope {
    const val SCHEMA_VERSION = "agent_tool_contract/v1"

    fun applyStandardFields(
        mapper: ObjectMapper,
        tool: Tool,
        result: ObjectNode,
        ok: Boolean,
        resultType: String = tool.resultType,
        displayPolicy: String = tool.defaultDisplayPolicy,
        request: JsonNode? = null
    ): ObjectNode {
        result.put("ok", ok)
        result.put("schema_version", tool.schemaVersion)
        result.put("tool", tool.name)
        result.put("result_type", resultType)
        result.put("candidate_only", false)
        result.put("display_policy", displayPolicy)
        if (request != null && !request.isMissingNode && !request.isNull) {
            result.set<JsonNode>("request", request.deepCopy())
        }
        if (!result.has("display")) {
            result.set<ObjectNode>("display", mapper.createObjectNode().apply {
                put("policy", displayPolicy)
            })
        }
        return result
    }

    fun error(
        mapper: ObjectMapper,
        tool: Tool,
        code: String,
        message: String,
        retryable: Boolean = false,
        hint: String? = null,
        request: JsonNode? = null
    ): ObjectNode {
        val out = mapper.createObjectNode()
        applyStandardFields(
            mapper = mapper,
            tool = tool,
            result = out,
            ok = false,
            request = request
        )
        out.put("error", message)
        out.set<ObjectNode>("error_detail", mapper.createObjectNode().apply {
            put("code", code)
            put("message", message)
            put("retryable", retryable)
            if (!hint.isNullOrBlank()) put("hint", hint)
        })
        return out
    }
}
