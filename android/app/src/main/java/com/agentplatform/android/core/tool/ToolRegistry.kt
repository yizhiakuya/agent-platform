package com.agentplatform.android.core.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * In-process map of tool name → Tool implementation. The Foreground Service
 * builds one of these on startup, registers the tools the device supports,
 * and reports the resulting {@code tool.manifest} to the server.
 */
class ToolRegistry {

    private val tools = LinkedHashMap<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun get(name: String): Tool? = tools[name]

    fun all(): List<Tool> = tools.values.toList()

    /** Build the {@code tool.manifest} params object that gets sent to the hub. */
    fun toManifestParams(mapper: ObjectMapper): ObjectNode {
        val params = mapper.createObjectNode()
        val arr = mapper.createArrayNode()
        for (t in tools.values) {
            val obj: ObjectNode = mapper.createObjectNode()
            obj.put("name", t.name)
            obj.put("description", t.description)
            obj.set<JsonNode>("schema", t.schema)
            obj.put("confirmRequired", t.confirmRequired)
            obj.put("schemaVersion", t.schemaVersion)
            obj.put("toolClass", t.toolClass)
            obj.put("safetyLevel", t.safetyLevel)
            obj.put("defaultDisplayPolicy", t.defaultDisplayPolicy)
            obj.put("resultType", t.resultType)
            arr.add(obj)
        }
        params.set<JsonNode>("tools", arr)
        return params
    }
}
