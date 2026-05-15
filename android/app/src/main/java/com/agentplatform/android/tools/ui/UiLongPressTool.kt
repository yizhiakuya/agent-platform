package com.agentplatform.android.tools.ui

import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.ui.accessibility.UiAccessibilityService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Single-finger long press at (x, y), primarily for opening image/context
 * menus inside third-party apps so the agent can choose save/share actions.
 */
class UiLongPressTool(private val mapper: ObjectMapper) : Tool {
    override val name = "ui.long_press"

    override val description = """
        Single-finger long press at screen coordinates (x, y) in pixels.
        Use this to open context menus in Android apps, for example long-press
        an image in WeChat/QQ and then tap "Save image" / "Save to album".
        x is horizontal (0 = left edge), y is vertical (0 = top edge).
        Get coordinates from `ui.screen_capture` or `ui.dump_tree` bounds.
        Requires the user to have enabled accessibility for Agent Platform.
    """.trimIndent()

    override val confirmRequired = true

    override val schema: JsonNode = mapper.readTree("""
        {
          "type": "object",
          "properties": {
            "x": { "type": "number", "description": "Horizontal pixel coordinate." },
            "y": { "type": "number", "description": "Vertical pixel coordinate." },
            "duration_ms": {
              "type": "integer",
              "minimum": 300,
              "maximum": 3000,
              "default": 800,
              "description": "How long to hold the press before lifting."
            }
          },
          "required": ["x", "y"]
        }
    """.trimIndent())

    override suspend fun execute(args: JsonNode): JsonNode {
        val x = args.path("x").floatValue()
        val y = args.path("y").floatValue()
        val durationMs = args.path("duration_ms").asLong(DEFAULT_LONG_PRESS_MS)
            .coerceIn(MIN_LONG_PRESS_MS, MAX_LONG_PRESS_MS)
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
        val ok = UiAccessibilityService.longPress(x, y, durationMs)
        val result = mapper.createObjectNode().apply {
            put("dispatched", ok)
            put("x", x.toDouble())
            put("y", y.toDouble())
            put("duration_ms", durationMs)
        }
        return ToolResultEnvelope.applyStandardFields(mapper, this, result, ok = ok, request = args)
    }

    private companion object {
        private const val DEFAULT_LONG_PRESS_MS = 800L
        private const val MIN_LONG_PRESS_MS = 300L
        private const val MAX_LONG_PRESS_MS = 3_000L
    }
}
