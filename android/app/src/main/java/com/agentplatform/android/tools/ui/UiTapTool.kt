package com.agentplatform.android.tools.ui

import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.ui.accessibility.UiAccessibilityService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Single-finger tap at (x, y) in screen pixels. Use after [UiScreenCaptureTool]
 * shows the LLM what's on screen, or after [UiDumpTreeTool] gives it node bounds.
 *
 * Marked confirm_required because mistapping in a banking / messaging app
 * has consequences beyond what the LLM can see in one screenshot.
 */
class UiTapTool(private val mapper: ObjectMapper) : Tool {
    override val name = "ui.tap"

    override val description = """
        Single-finger tap at screen coordinates (x, y) in pixels.
        x is horizontal (0 = left edge), y is vertical (0 = top edge).
        Get coordinates from `ui.screen_capture` (visual) or `ui.dump_tree` (node bounds).
        Requires the user to have enabled accessibility for Agent Platform.
    """.trimIndent()

    override val confirmRequired = true

    override val schema: JsonNode = mapper.readTree("""
        {
          "type": "object",
          "properties": {
            "x": { "type": "number", "description": "Horizontal pixel coordinate." },
            "y": { "type": "number", "description": "Vertical pixel coordinate." }
          },
          "required": ["x", "y"]
        }
    """.trimIndent())

    override suspend fun execute(args: JsonNode): JsonNode {
        val x = args.path("x").floatValue()
        val y = args.path("y").floatValue()
        if (!UiAccessibilityService.isAvailable()) {
            return errorResp("accessibility service not enabled — open Settings → Accessibility → Agent Platform")
        }
        val ok = UiAccessibilityService.tap(x, y)
        return mapper.createObjectNode().apply {
            put("dispatched", ok)
            put("x", x.toDouble())
            put("y", y.toDouble())
        }
    }

    private fun errorResp(message: String): JsonNode =
        mapper.createObjectNode().apply { put("error", message) }
}
