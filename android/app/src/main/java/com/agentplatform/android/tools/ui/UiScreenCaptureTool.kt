package com.agentplatform.android.tools.ui

import android.graphics.Bitmap
import android.util.Base64
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.ui.accessibility.UiAccessibilityService
import com.agentplatform.android.ui.capture.UiCaptureManager
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Snapshot the device screen as a JPEG and return it for the LLM to see
 * via native multimodal tool_result. Use after [UiTapTool] / [UiSwipeTool]
 * to verify the gesture had the expected effect, or as a first step when
 * [UiDumpTreeTool] returns sparse semantics (custom-rendered apps).
 *
 * Returns:
 *   - vision_b64: base64 JPEG, q85 (forwarded to vision-capable Claude
 *     as a real image block)
 *   - capture: { w, h } scaled image dimensions
 *   - device:  { w, h } native pixels (for coordinate scaling)
 *
 * Read-only — no confirm gating. The user must have approved either the
 * AccessibilityService screenshot capability or the MediaProjection consent.
 */
class UiScreenCaptureTool(
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {

    override val name = "ui.screen_capture"

    override val description = """
        Take a JPEG screenshot of the device's foreground screen and send it
        to the LLM as a vision attachment. Use to verify gesture effects or
        when ui.dump_tree returns sparse semantics. Tap/swipe coordinates
        the LLM picks from this image are in CAPTURE pixels — multiply by
        device.w/capture.w (and same for height) to get native pixel
        coordinates for ui.tap.
        Uses AccessibilityService screenshots when available; otherwise falls
        back to the one-time MediaProjection permission from MainActivity.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree("""
        {
          "type": "object",
          "properties": {
            "delay_ms": {
              "type": "integer",
              "minimum": 0,
              "maximum": 2000,
              "default": 0,
              "description": "Wait this long before snapshotting — useful right after a tap/swipe so the UI has settled."
            },
            "quality": {
              "type": "integer",
              "minimum": 50,
              "maximum": 95,
              "default": 80,
              "description": "JPEG quality. 80 is a good vision/cost trade-off."
            }
          }
        }
    """.trimIndent())

    override suspend fun execute(args: JsonNode): JsonNode {
        val delayMs = args.path("delay_ms").asLong(0L).coerceIn(0L, 2000L)
        val quality = args.path("quality").asInt(80).coerceIn(50, 95)

        if (delayMs > 0) delay(delayMs)

        val accessibilityBitmap = if (UiAccessibilityService.canTakeScreenshots()) {
            UiAccessibilityService.screenshot()
        } else {
            null
        }
        val mediaProjectionBitmap = if (accessibilityBitmap == null) {
            withContext(ioDispatcher) { UiCaptureManager.captureNow() }
        } else {
            null
        }
        val bitmap = accessibilityBitmap ?: mediaProjectionBitmap ?: return ToolResultEnvelope.error(
                mapper = mapper,
                tool = this,
                code = "screen_capture_unavailable",
                message = "screen capture unavailable - enable Agent Platform accessibility service or open the app and tap '允许屏幕识别'",
                retryable = true,
                hint = "Enable accessibility screenshots or grant MediaProjection screen capture permission.",
                request = args
        )

        val b64 = withContext(ioDispatcher) { encodeJpegBase64(bitmap, quality) }
        val capW: Int
        val capH: Int
        val devW: Int
        val devH: Int
        if (accessibilityBitmap != null) {
            capW = bitmap.width
            capH = bitmap.height
            devW = bitmap.width
            devH = bitmap.height
        } else {
            val cap = UiCaptureManager.captureSize()
            val dev = UiCaptureManager.deviceSize()
            capW = cap.first
            capH = cap.second
            devW = dev.first
            devH = dev.second
        }
        bitmap.recycle()

        val result = mapper.createObjectNode().apply {
            put("vision_b64", b64)
            put("source", if (accessibilityBitmap != null) "accessibility" else "media_projection")
            set<JsonNode>("capture", mapper.createObjectNode().apply {
                put("w", capW); put("h", capH)
            })
            set<JsonNode>("device", mapper.createObjectNode().apply {
                put("w", devW); put("h", devH)
            })
        }
        return ToolResultEnvelope.applyStandardFields(
            mapper = mapper,
            tool = this,
            result = result,
            ok = true,
            displayPolicy = "debug_only",
            request = args
        )
    }

    private fun encodeJpegBase64(bitmap: Bitmap, quality: Int): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
