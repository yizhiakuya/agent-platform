package com.agentplatform.android.tools.ui

import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.ui.accessibility.UiAccessibilityService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Type text into the active input target. The best path is still for the LLM
 * to call [UiTapTool] on the input first, but the accessibility service also
 * tries focused editable nodes, the Android 13+ input connection, and a
 * clipboard paste fallback.
 *
 * On most apps this performs ACTION_SET_TEXT which replaces the field's
 * contents in a single accessibility event — much faster and more
 * reliable than synthesising key events one character at a time.
 */
class UiTypeTextTool(private val mapper: ObjectMapper) : Tool {
    override val name = "ui.type_text"

    override val description = """
        Type text into the active input field. Prefer tapping the intended
        input first, then call ui.type_text. The tool can also use the active
        Android input connection or a clipboard paste fallback when an app
        shows the keyboard but does not expose a normal editable node. Replaces
        the field's current text when the target supports replacement; pass
        the full intended content, not an incremental delta.
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
            return ToolResultEnvelope.error(
                mapper = mapper,
                tool = this,
                code = "accessibility_disabled",
                message = "accessibility service not enabled - open Settings -> Accessibility -> Agent Platform",
                hint = "Enable Agent Platform in Android Accessibility settings.",
                request = args
            )
        }
        val result = UiAccessibilityService.typeText(text)
        val out = mapper.createObjectNode().apply {
            put("typed", result.typed)
            put("length", text.length)
            if (result.method != null) put("method", result.method)
            put("focused_input", result.focusedInput)
            put("editable_candidates", result.editableCandidates)
            if (!result.typed) {
                put("reason", result.reason ?: INPUT_UNAVAILABLE_MESSAGE)
                put("hint", "tap the intended input field and retry ui.type_text; if the app still hides the field, ask the user to paste manually")
            }
        }
        ToolResultEnvelope.applyStandardFields(mapper, this, out, ok = result.typed, request = args)
        if (!result.typed) {
            out.put("error", result.reason ?: INPUT_UNAVAILABLE_MESSAGE)
            out.set<ObjectNode>("error_detail", mapper.createObjectNode().apply {
                put("code", "input_unavailable")
                put("message", result.reason ?: INPUT_UNAVAILABLE_MESSAGE)
                put("retryable", true)
                put("hint", "Tap the intended input field and retry ui.type_text.")
            })
        }
        return out
    }

    companion object {
        private const val INPUT_UNAVAILABLE_MESSAGE = "unable to type into the current screen"
    }
}
