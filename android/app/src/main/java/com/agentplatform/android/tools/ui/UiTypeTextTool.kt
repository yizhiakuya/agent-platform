package com.agentplatform.android.tools.ui

import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.ui.accessibility.UiAccessibilityService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Type text into the currently focused editable field. The LLM should
 * normally call [UiTapTool] on the input field first to focus it.
 *
 * On most apps this performs ACTION_SET_TEXT which replaces the field's
 * entire contents in a single accessibility event — much faster and more
 * reliable than synthesising key events one character at a time.
 */
class UiTypeTextTool(private val mapper: ObjectMapper) : Tool {
    override val name = "ui.type_text"

    override val description = """
        Type text into the currently focused editable field. Tap the input
        first to focus it (LLM should call ui.tap on the input field then
        ui.type_text). Replaces the field's entire current text — pass the
        full intended content, not an incremental delta.
        Requires the user to have enabled accessibility for Agent Platform.
    """.trimIndent()

    override val confirmRequired = true

    override val schema: JsonNode = mapper.readTree("""
        {
          "type": "object",
          "properties": {
            "text": {
              "type": "string",
              "description": "Full text to set in the focused input field."
            }
          },
          "required": ["text"]
        }
    """.trimIndent())

    override suspend fun execute(args: JsonNode): JsonNode {
        val text = args.path("text").asText("")
        if (!UiAccessibilityService.isAvailable()) {
            return mapper.createObjectNode().apply {
                put("error", "accessibility service not enabled — open Settings → Accessibility → Agent Platform")
            }
        }
        val ok = UiAccessibilityService.typeText(text)
        return mapper.createObjectNode().apply {
            put("typed", ok)
            put("length", text.length)
            if (!ok) put("hint", "no editable field has focus — call ui.tap on the input first")
        }
    }
}
