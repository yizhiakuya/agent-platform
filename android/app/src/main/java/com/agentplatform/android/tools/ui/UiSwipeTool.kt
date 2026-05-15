package com.agentplatform.android.tools.ui

import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.ui.accessibility.UiAccessibilityService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Single-finger swipe from one point to another. Used for scrolling lists,
 * dismissing notifications, dragging sliders, navigating tabs, etc.
 *
 * Default duration of 300 ms produces a clean fling on most apps; below
 * 100 ms is treated as a flick (more momentum), above 800 ms typically
 * dragging instead of fling.
 */
class UiSwipeTool(private val mapper: ObjectMapper) : Tool {
    override val name = "ui.swipe"

    override val description = """
        Single-finger swipe from (x1, y1) to (x2, y2). Use for scrolling,
        dragging, dismissing, navigating tabs.
        Duration in milliseconds: 100=flick, 300=normal scroll, 800+=drag.
        Requires the user to have enabled accessibility for Agent Platform.
    """.trimIndent()

    override val confirmRequired = true

    override val schema: JsonNode = mapper.readTree("""
        {
          "type": "object",
          "properties": {
            "x1": { "type": "number" },
            "y1": { "type": "number" },
            "x2": { "type": "number" },
            "y2": { "type": "number" },
            "duration_ms": {
              "type": "integer",
              "minimum": 50,
              "maximum": 5000,
              "default": 300,
              "description": "Total gesture duration."
            }
          },
          "required": ["x1", "y1", "x2", "y2"]
        }
    """.trimIndent())

    override suspend fun execute(args: JsonNode): JsonNode {
        val x1 = args.path("x1").floatValue()
        val y1 = args.path("y1").floatValue()
        val x2 = args.path("x2").floatValue()
        val y2 = args.path("y2").floatValue()
        val duration = args.path("duration_ms").asLong(300L).coerceIn(50L, 5000L)
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
        val ok = UiAccessibilityService.swipe(x1, y1, x2, y2, duration)
        val result = mapper.createObjectNode().apply {
            put("dispatched", ok)
            put("duration_ms", duration)
            put("x1", x1.toDouble())
            put("y1", y1.toDouble())
            put("x2", x2.toDouble())
            put("y2", y2.toDouble())
        }
        return ToolResultEnvelope.applyStandardFields(mapper, this, result, ok = ok, request = args)
    }
}
