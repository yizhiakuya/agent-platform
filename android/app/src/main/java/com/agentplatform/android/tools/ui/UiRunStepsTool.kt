package com.agentplatform.android.tools.ui

import android.content.Context
import android.content.Intent
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.ui.accessibility.UiAccessibilityService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Execute a short ordered UI macro on-device.
 *
 * This removes repeated server -> LLM -> device round trips for known flows:
 * the model can use a learned app skill to send several deterministic actions
 * in one tool call, then inspect only the final or failure state.
 */
class UiRunStepsTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {
    override val name = "ui.run_steps"

    override val description = """
        Execute a short ordered UI macro on the Android device. Use this for
        known workflows from a loaded app skill after coordinates/resource
        targets were learned from a recent ui.dump_tree or ui.screen_capture.
        Supported actions: open_app, tap, long_press, swipe, type_text, global,
        wait, dump_tree. Steps run sequentially on the phone; this is faster than
        calling one UI tool per LLM turn, but it cannot branch based on
        intermediate screens. Prefer observe=final or observe=on_failure.
        Use exploratory single-step tools when the current page is unknown.
    """.trimIndent()

    override val confirmRequired = true

    override val schema: JsonNode = mapper.readTree("""
        {
          "type": "object",
          "properties": {
            "steps": {
              "type": "array",
              "minItems": 1,
              "maxItems": 12,
              "description": "Ordered UI steps to execute.",
              "items": {
                "type": "object",
                "properties": {
                  "action": {
                    "type": "string",
                    "enum": ["open_app", "tap", "long_press", "swipe", "type_text", "global", "wait", "dump_tree"]
                  },
                  "package": {
                    "type": "string",
                    "description": "Android package name for action=open_app."
                  },
                  "x": { "type": "number", "description": "Tap or long_press x coordinate." },
                  "y": { "type": "number", "description": "Tap or long_press y coordinate." },
                  "x1": { "type": "number", "description": "Swipe start x coordinate." },
                  "y1": { "type": "number", "description": "Swipe start y coordinate." },
                  "x2": { "type": "number", "description": "Swipe end x coordinate." },
                  "y2": { "type": "number", "description": "Swipe end y coordinate." },
                  "duration_ms": {
                    "type": "integer",
                    "minimum": 0,
                    "maximum": 3000,
                    "description": "Gesture or wait duration, depending on action."
                  },
                  "text": {
                    "type": "string",
                    "description": "Full text for action=type_text."
                  },
                  "global_action": {
                    "type": "string",
                    "enum": ["BACK", "HOME", "RECENTS", "NOTIFICATIONS",
                             "QUICK_SETTINGS", "POWER_DIALOG", "LOCK_SCREEN",
                             "TAKE_SCREENSHOT"]
                  },
                  "max_depth": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": 30,
                    "description": "Tree depth for action=dump_tree."
                  }
                },
                "required": ["action"]
              }
            },
            "observe": {
              "type": "string",
              "enum": ["none", "final", "after_each", "on_failure"],
              "default": "final",
              "description": "When to include ui.dump_tree observations in the result."
            },
            "abort_on_failure": {
              "type": "boolean",
              "default": true,
              "description": "Stop executing later steps after the first failed step."
            },
            "step_delay_ms": {
              "type": "integer",
              "minimum": 0,
              "maximum": 1000,
              "default": 200,
              "description": "Delay between steps so target apps can settle."
            },
            "final_max_depth": {
              "type": "integer",
              "minimum": 1,
              "maximum": 30,
              "default": 12,
              "description": "Depth for final or failure tree observations."
            }
          },
          "required": ["steps"]
        }
    """.trimIndent())

    override suspend fun execute(args: JsonNode): JsonNode {
        if (!UiAccessibilityService.isAvailable()) {
            return mapper.createObjectNode().apply {
                put("ok", false)
                put("error", "accessibility service not enabled - open Settings -> Accessibility -> Agent Platform")
            }
        }

        val steps = args.path("steps")
        if (!steps.isArray || steps.size() == 0) {
            return mapper.createObjectNode().apply {
                put("ok", false)
                put("error", "steps must be a non-empty array")
            }
        }
        if (steps.size() > MAX_STEPS) {
            return mapper.createObjectNode().apply {
                put("ok", false)
                put("error", "too many steps; max is $MAX_STEPS")
            }
        }

        val observe = normalizedObserve(args.path("observe").asText("final").lowercase())
        val abortOnFailure = args.path("abort_on_failure").asBoolean(true)
        val stepDelayMs = args.path("step_delay_ms").asLong(DEFAULT_STEP_DELAY_MS)
            .coerceIn(0L, MAX_STEP_DELAY_MS)
        val finalMaxDepth = args.path("final_max_depth").asInt(DEFAULT_TREE_DEPTH)
            .coerceIn(1, MAX_TREE_DEPTH)

        val results = mapper.createArrayNode()
        var ok = true
        var failedStep = -1
        var aborted = false

        for (i in 0 until steps.size()) {
            val step = steps[i]
            val action = step.path("action").asText("")
            val startedAt = System.currentTimeMillis()
            val result = executeStep(action, step)
            val stepOk = result.path("ok").asBoolean(false)
            result.put("index", i)
            result.put("action", action)
            result.put("duration_ms", System.currentTimeMillis() - startedAt)

            if (observe == "after_each" || (!stepOk && observe == "on_failure")) {
                result.set<JsonNode>("observation", safeDumpTree(finalMaxDepth))
            }
            results.add(result)

            if (!stepOk) {
                ok = false
                failedStep = i
                if (abortOnFailure) {
                    aborted = true
                    break
                }
            }

            if (i < steps.size() - 1 && stepDelayMs > 0) {
                delay(stepDelayMs)
            }
        }

        return mapper.createObjectNode().apply {
            put("ok", ok)
            put("executed_steps", results.size())
            put("aborted", aborted)
            if (failedStep >= 0) put("failed_step", failedStep)
            set<JsonNode>("results", results)
            if (observe == "final" || (!ok && observe == "on_failure")) {
                set<JsonNode>("observation", safeDumpTree(finalMaxDepth))
            }
        }
    }

    private suspend fun executeStep(action: String, step: JsonNode): ObjectNode =
        when (action) {
            "open_app" -> openApp(step)
            "tap" -> tap(step)
            "long_press" -> longPress(step)
            "swipe" -> swipe(step)
            "type_text" -> typeText(step)
            "global" -> global(step)
            "wait" -> waitStep(step)
            "dump_tree" -> dumpTreeStep(step)
            else -> errorStep("unknown action '$action'")
        }

    private suspend fun openApp(step: JsonNode): ObjectNode {
        val packageName = step.path("package").asText("")
        if (packageName.isBlank()) return errorStep("missing package")

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: knownLaunchIntent(packageName)
            ?: return errorStep("app not installed or has no launcher activity").apply {
                put("package", packageName)
            }
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )

        val attempts = mapper.createArrayNode()
        var sent = sendLaunchIntent(intent, "application", attempts)
        if (!sent) {
            sent = sendLaunchIntentFromAccessibility(intent, attempts)
        }
        val requestedWaitMs = step.path("duration_ms").asLong(DEFAULT_OPEN_WAIT_MS)
        val waitMs = if (requestedWaitMs <= 0L) {
            DEFAULT_OPEN_WAIT_MS
        } else {
            requestedWaitMs.coerceIn(250L, MAX_WAIT_MS)
        }
        delay(waitMs)
        var foreground = foregroundPackage()
        var foregroundPackage = foreground.packageName
        if (sent && foregroundPackage != packageName) {
            sent = sendLaunchIntentFromAccessibility(intent, attempts)
            if (sent) {
                delay(waitMs)
                foreground = foregroundPackage()
                foregroundPackage = foreground.packageName
            }
        }

        return mapper.createObjectNode().apply {
            put("ok", sent && foregroundPackage == packageName)
            put("package", packageName)
            put("foregroundPackage", foregroundPackage)
            put("verificationSource", foreground.source)
            if (foreground.cachedPackage.isNotBlank()) put("cachedForegroundPackage", foreground.cachedPackage)
            if (foreground.activeWindowPackage.isNotBlank()) put("activeWindowPackage", foreground.activeWindowPackage)
            put("wait_ms", waitMs)
            set<JsonNode>("launchAttempts", attempts)
            if (foregroundPackage != packageName) {
                put("error", "launch intent sent but target app foreground could not be verified")
            }
        }
    }

    private suspend fun tap(step: JsonNode): ObjectNode {
        val x = numberArg(step, "x") ?: return errorStep("missing numeric x")
        val y = numberArg(step, "y") ?: return errorStep("missing numeric y")
        val dispatched = UiAccessibilityService.tap(x.toFloat(), y.toFloat())
        return mapper.createObjectNode().apply {
            put("ok", dispatched)
            put("dispatched", dispatched)
            put("x", x)
            put("y", y)
        }
    }

    private suspend fun longPress(step: JsonNode): ObjectNode {
        val x = numberArg(step, "x") ?: return errorStep("missing numeric x")
        val y = numberArg(step, "y") ?: return errorStep("missing numeric y")
        val duration = step.path("duration_ms").asLong(DEFAULT_LONG_PRESS_MS)
            .coerceIn(MIN_LONG_PRESS_MS, MAX_LONG_PRESS_MS)
        val dispatched = UiAccessibilityService.longPress(x.toFloat(), y.toFloat(), duration)
        return mapper.createObjectNode().apply {
            put("ok", dispatched)
            put("dispatched", dispatched)
            put("x", x)
            put("y", y)
            put("duration_ms", duration)
        }
    }

    private suspend fun swipe(step: JsonNode): ObjectNode {
        val x1 = numberArg(step, "x1") ?: return errorStep("missing numeric x1")
        val y1 = numberArg(step, "y1") ?: return errorStep("missing numeric y1")
        val x2 = numberArg(step, "x2") ?: return errorStep("missing numeric x2")
        val y2 = numberArg(step, "y2") ?: return errorStep("missing numeric y2")
        val duration = step.path("duration_ms").asLong(DEFAULT_SWIPE_MS).coerceIn(50L, MAX_WAIT_MS)
        val dispatched = UiAccessibilityService.swipe(
            x1.toFloat(),
            y1.toFloat(),
            x2.toFloat(),
            y2.toFloat(),
            duration
        )
        return mapper.createObjectNode().apply {
            put("ok", dispatched)
            put("dispatched", dispatched)
            put("duration_ms", duration)
        }
    }

    private fun typeText(step: JsonNode): ObjectNode {
        val textNode = step.get("text") ?: return errorStep("missing text")
        val result = UiAccessibilityService.typeText(textNode.asText(""))
        return mapper.createObjectNode().apply {
            put("ok", result.typed)
            put("typed", result.typed)
            put("length", textNode.asText("").length)
            if (result.method != null) put("method", result.method)
            put("focused_input", result.focusedInput)
            put("editable_candidates", result.editableCandidates)
            if (!result.typed) put("error", result.reason ?: "unable to type into the current screen")
        }
    }

    private fun global(step: JsonNode): ObjectNode {
        val action = step.path("global_action").asText(step.path("action_name").asText(""))
        if (action.isBlank()) return errorStep("missing global_action")
        val dispatched = UiAccessibilityService.globalAction(action)
        return mapper.createObjectNode().apply {
            put("ok", dispatched)
            put("dispatched", dispatched)
            put("global_action", action)
            if (!dispatched) put("error", "unknown or unsupported global_action")
        }
    }

    private suspend fun waitStep(step: JsonNode): ObjectNode {
        val duration = step.path("duration_ms").asLong(DEFAULT_WAIT_MS).coerceIn(0L, MAX_WAIT_MS)
        delay(duration)
        return mapper.createObjectNode().apply {
            put("ok", true)
            put("waited_ms", duration)
        }
    }

    private fun dumpTreeStep(step: JsonNode): ObjectNode {
        val maxDepth = step.path("max_depth").asInt(DEFAULT_TREE_DEPTH).coerceIn(1, MAX_TREE_DEPTH)
        val tree = safeDumpTree(maxDepth)
        return mapper.createObjectNode().apply {
            put("ok", !tree.has("error"))
            put("max_depth", maxDepth)
            set<JsonNode>("tree", tree)
            if (tree.has("error")) put("error", tree.path("error").asText())
        }
    }

    private fun safeDumpTree(maxDepth: Int): ObjectNode =
        runCatching { UiAccessibilityService.dumpTree(mapper, maxDepth) }
            .getOrElse { error ->
                mapper.createObjectNode().apply { put("error", error.message ?: "dump_tree failed") }
            }

    private suspend fun foregroundPackage(): ForegroundObservation {
        val cached = UiAccessibilityService.currentPackage()
        if (cached.isNotBlank()) {
            return ForegroundObservation(
                packageName = cached,
                source = "cached_accessibility_event",
                cachedPackage = cached,
                activeWindowPackage = ""
            )
        }
        val activeWindow = withTimeoutOrNull(ACTIVE_WINDOW_PROBE_TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) {
                UiAccessibilityService.activeWindowPackage()
            }
        }.orEmpty()
        return ForegroundObservation(
            packageName = activeWindow,
            source = if (activeWindow.isBlank()) "unavailable" else "active_window",
            cachedPackage = "",
            activeWindowPackage = activeWindow
        )
    }

    private fun sendLaunchIntent(intent: Intent, source: String, attempts: com.fasterxml.jackson.databind.node.ArrayNode): Boolean =
        runCatching {
            context.startActivity(intent)
            attempts.add(mapper.createObjectNode().apply {
                put("source", source)
                put("sent", true)
            })
            true
        }.getOrElse { error ->
            attempts.add(mapper.createObjectNode().apply {
                put("source", source)
                put("sent", false)
                put("error", error.message ?: "startActivity failed")
            })
            false
        }

    private fun sendLaunchIntentFromAccessibility(
        intent: Intent,
        attempts: com.fasterxml.jackson.databind.node.ArrayNode
    ): Boolean =
        runCatching { UiAccessibilityService.startActivityFromService(intent) }
            .getOrDefault(false)
            .also { sent ->
                attempts.add(mapper.createObjectNode().apply {
                    put("source", "accessibility")
                    put("sent", sent)
                })
            }

    private fun knownLaunchIntent(packageName: String): Intent? =
        when (packageName) {
            "com.tencent.mm" -> launcherIntent(packageName, "com.tencent.mm.ui.LauncherUI")
            "com.tencent.mobileqq" -> launcherIntent(packageName, "com.tencent.mobileqq.activity.SplashActivity")
            else -> null
        }

    private fun launcherIntent(packageName: String, activityName: String): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(packageName, activityName)

    private fun numberArg(step: JsonNode, name: String): Double? {
        val value = step.get(name) ?: return null
        return if (value.isNumber) value.asDouble() else null
    }

    private fun normalizedObserve(value: String): String =
        when (value) {
            "none", "final", "after_each", "on_failure" -> value
            else -> "final"
        }

    private data class ForegroundObservation(
        val packageName: String,
        val source: String,
        val cachedPackage: String,
        val activeWindowPackage: String
    )

    private fun errorStep(message: String): ObjectNode =
        mapper.createObjectNode().apply {
            put("ok", false)
            put("error", message)
        }

    private companion object {
        private const val MAX_STEPS = 12
        private const val DEFAULT_STEP_DELAY_MS = 200L
        private const val MAX_STEP_DELAY_MS = 1_000L
        private const val DEFAULT_OPEN_WAIT_MS = 1_000L
        private const val DEFAULT_WAIT_MS = 500L
        private const val DEFAULT_SWIPE_MS = 300L
        private const val DEFAULT_LONG_PRESS_MS = 800L
        private const val MIN_LONG_PRESS_MS = 300L
        private const val MAX_LONG_PRESS_MS = 3_000L
        private const val MAX_WAIT_MS = 3_000L
        private const val ACTIVE_WINDOW_PROBE_TIMEOUT_MS = 300L
        private const val DEFAULT_TREE_DEPTH = 12
        private const val MAX_TREE_DEPTH = 30
    }
}
