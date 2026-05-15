package com.agentplatform.android.tools.ui

import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.ui.accessibility.UiAccessibilityService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Run a global system action: BACK / HOME / RECENTS / NOTIFICATIONS /
 * QUICK_SETTINGS / POWER_DIALOG / LOCK_SCREEN / TAKE_SCREENSHOT.
 * Equivalent to pressing the corresponding hardware/system button.
 */
class UiGlobalTool(private val mapper: ObjectMapper) : Tool {
    override val name = "ui.global"

    override val description = """
        Trigger a global system action — same effect as pressing the
        corresponding hardware/system button. Allowed actions:
        BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, POWER_DIALOG,
        LOCK_SCREEN, TAKE_SCREENSHOT (saves to gallery, NOT for agent vision —
        use ui.screen_capture for that).
        Requires the user to have enabled accessibility for Agent Platform.
    """.trimIndent()

    override val confirmRequired = true

    override val schema: JsonNode = mapper.readTree("""
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["BACK", "HOME", "RECENTS", "NOTIFICATIONS",
                       "QUICK_SETTINGS", "POWER_DIALOG", "LOCK_SCREEN",
                       "TAKE_SCREENSHOT"]
            }
          },
          "required": ["action"]
        }
    """.trimIndent())

    override suspend fun execute(args: JsonNode): JsonNode {
        val action = args.path("action").asText("")
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
        val ok = UiAccessibilityService.globalAction(action)
        val result = mapper.createObjectNode().apply {
            put("dispatched", ok)
            put("action", action)
            if (!ok) put("hint", "unknown action — see the enum in this tool's schema")
        }
        return ToolResultEnvelope.applyStandardFields(mapper, this, result, ok = ok, request = args)
    }
}
