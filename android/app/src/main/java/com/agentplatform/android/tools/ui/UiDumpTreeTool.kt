package com.agentplatform.android.tools.ui

import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.ui.accessibility.UiAccessibilityService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Dump the AccessibilityNodeInfo tree of the foreground window. Returns a
 * structured JSON snapshot the LLM can read directly — text, content
 * description, resource id, clickable/editable/scrollable flags, screen
 * bounds. Token-cheap and exact, the preferred channel for "find the
 * 'login' button" style decisions.
 *
 * Does NOT see the actual pixels — for visual reasoning (custom-rendered
 * canvases, games, image-only buttons) use [UiScreenCaptureTool].
 *
 * Read-only — no confirm gating.
 */
class UiDumpTreeTool(private val mapper: ObjectMapper) : Tool {
    override val name = "ui.dump_tree"

    override val description = """
        Dump the foreground app's accessibility tree as JSON: text, content
        description, resource id, clickable/editable/scrollable flags,
        screen bounds. Token-cheap and exact. Use first; fall back to
        ui.screen_capture only when the tree is sparse (custom-rendered
        canvases, games, image-only buttons).
        Returns:
          - package: foreground app package name
          - nodes: array of {path, text?, desc?, id?, class?, clickable?,
                  editable?, scrollable?, bounds?{l,t,r,b}}
        Requires the user to have enabled accessibility for Agent Platform.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree("""
        {
          "type": "object",
          "properties": {
            "max_depth": {
              "type": "integer",
              "minimum": 1,
              "maximum": 30,
              "default": 12,
              "description": "Cap recursion depth — deeper trees blow up the LLM context."
            }
          }
        }
    """.trimIndent())

    override suspend fun execute(args: JsonNode): JsonNode {
        val maxDepth = args.path("max_depth").asInt(12).coerceIn(1, 30)
        if (!UiAccessibilityService.isAvailable()) {
            return ToolResultEnvelope.error(
                mapper = mapper,
                tool = this,
                code = "accessibility_disabled",
                message = "accessibility service not enabled - open Settings -> Accessibility -> Agent Platform",
                hint = "Enable Agent Platform in Android Accessibility settings.",
                request = args
            )
        }
        val tree = UiAccessibilityService.dumpTreeWithTimeout(mapper, maxDepth, DUMP_TREE_TIMEOUT_MS)
        if (tree.has("error")) {
            ToolResultEnvelope.applyStandardFields(mapper, this, tree, ok = false, request = args)
            tree.set<ObjectNode>("error_detail", mapper.createObjectNode().apply {
                put("code", if (tree.path("timed_out").asBoolean(false)) "dump_tree_timeout" else "dump_tree_failed")
                put("message", tree.path("error").asText())
                put("retryable", true)
            })
            return tree
        }
        return ToolResultEnvelope.applyStandardFields(mapper, this, tree, ok = true, request = args)
    }

    private companion object {
        private const val DUMP_TREE_TIMEOUT_MS = 2_500L
    }
}
