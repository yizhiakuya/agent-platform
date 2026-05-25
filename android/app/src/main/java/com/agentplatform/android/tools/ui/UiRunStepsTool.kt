package com.agentplatform.android.tools.ui

import android.content.Context
import android.content.Intent
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.privilege.PrivilegeManager
import com.agentplatform.android.ui.accessibility.UiAccessibilityService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.delay

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
            return accessibilityUnavailable()
        }

        val steps = args.path("steps")
        validateSteps(steps)?.let { return it }
        val options = runOptions(args)
        return runStepsResult(executeSteps(steps, options), options)
    }

    private fun accessibilityUnavailable(): ObjectNode =
        mapper.createObjectNode().apply {
            put("ok", false)
            put("error", "accessibility service not enabled - open Settings -> Accessibility -> Agent Platform")
        }

    private fun validateSteps(steps: JsonNode): ObjectNode? =
        when {
            !steps.isArray || steps.size() == 0 -> errorStep("steps must be a non-empty array")
            steps.size() > MAX_STEPS -> errorStep("too many steps; max is $MAX_STEPS")
            else -> null
        }

    private fun runOptions(args: JsonNode): RunOptions =
        RunOptions(
            observe = normalizedObserve(args.path("observe").asText("final").lowercase()),
            abortOnFailure = args.path("abort_on_failure").asBoolean(true),
            stepDelayMs = args.path("step_delay_ms").asLong(DEFAULT_STEP_DELAY_MS)
                .coerceIn(0L, MAX_STEP_DELAY_MS),
            finalMaxDepth = args.path("final_max_depth").asInt(DEFAULT_TREE_DEPTH)
                .coerceIn(1, MAX_TREE_DEPTH)
        )

    private suspend fun executeSteps(steps: JsonNode, options: RunOptions): StepRunResult {
        val results = mapper.createArrayNode()
        var failedStep = -1
        var aborted = false

        for (i in 0 until steps.size()) {
            val result = executeStepAt(i, steps[i], options)
            val stepOk = result.path("ok").asBoolean(false)
            results.add(result)
            if (!stepOk) {
                failedStep = i
                aborted = options.abortOnFailure
                if (aborted) break
            }
            delayBeforeNextStep(i, steps.size(), options.stepDelayMs)
        }

        return StepRunResult(
            ok = failedStep < 0,
            failedStep = failedStep,
            aborted = aborted,
            results = results
        )
    }

    private suspend fun executeStepAt(index: Int, step: JsonNode, options: RunOptions): ObjectNode {
        val action = step.path("action").asText("")
        val startedAt = System.currentTimeMillis()
        val result = executeStep(action, step)
        val stepOk = result.path("ok").asBoolean(false)
        result.put("index", index)
        result.put("action", action)
        result.put("duration_ms", System.currentTimeMillis() - startedAt)
        if (shouldObserveStep(options.observe, stepOk)) {
            result.set<JsonNode>("observation", safeDumpTree(options.finalMaxDepth))
        }
        return result
    }

    private fun shouldObserveStep(observe: String, stepOk: Boolean): Boolean =
        observe == "after_each" || (!stepOk && observe == "on_failure")

    private suspend fun delayBeforeNextStep(index: Int, totalSteps: Int, delayMs: Long) {
        if (index < totalSteps - 1 && delayMs > 0) {
            delay(delayMs)
        }
    }

    private fun runStepsResult(run: StepRunResult, options: RunOptions): ObjectNode =
        mapper.createObjectNode().apply {
            put("ok", run.ok)
            put("executed_steps", run.results.size())
            put("aborted", run.aborted)
            if (run.failedStep >= 0) put("failed_step", run.failedStep)
            set<JsonNode>("results", run.results)
            if (shouldObserveFinal(options.observe, run.ok)) {
                set<JsonNode>("observation", safeDumpTree(options.finalMaxDepth))
            }
        }

    private fun shouldObserveFinal(observe: String, ok: Boolean): Boolean =
        observe == "final" || (!ok && observe == "on_failure")

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

        val launchTarget = UiAppLaunchHelper.launchTarget(context, packageName)
            ?: UiAppLaunchHelper.knownLaunchTarget(packageName)
            ?: return errorStep("app not installed or has no launcher activity").apply {
                put("package", packageName)
            }
        val attempts = mapper.createArrayNode()
        val waitMs = openWaitMs(step)
        val launch = launchAndVerify(packageName, launchTarget, attempts, waitMs)
        return appOpenResult(packageName, launch, attempts, waitMs)
    }

    private fun openWaitMs(step: JsonNode): Long {
        val requestedWaitMs = step.path("duration_ms").asLong(DEFAULT_OPEN_WAIT_MS)
        return if (requestedWaitMs <= 0L) {
            DEFAULT_OPEN_WAIT_MS
        } else {
            requestedWaitMs.coerceIn(250L, MAX_WAIT_MS)
        }
    }

    private suspend fun launchAndVerify(
        packageName: String,
        launchTarget: UiLaunchTarget,
        attempts: ArrayNode,
        waitMs: Long
    ): AppOpenAttempt {
        val intent = launchTarget.intentWithFlags()
        var sent = sendInitialLaunch(packageName, launchTarget.activityName, intent, attempts)
        var foreground = observeAfterWait(waitMs)
        if (sent && foreground.packageName != packageName) {
            val retry = retryAccessibilityLaunch(intent, attempts, waitMs)
            sent = retry.sent
            foreground = retry.foreground
        }
        if (foreground.packageName != packageName) {
            val retry = retryShellLaunch(packageName, launchTarget.activityName, attempts)
            sent = sent || retry.sent
            if (retry.sent) foreground = retry.foreground
        }
        return AppOpenAttempt(sent = sent, foreground = foreground)
    }

    private fun UiLaunchTarget.intentWithFlags(): Intent =
        intent.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }

    private fun sendInitialLaunch(
        packageName: String,
        activityName: String?,
        intent: Intent,
        attempts: ArrayNode
    ): Boolean =
        sendLaunchIntentFromShell(packageName, activityName, attempts) ||
            sendLaunchIntentFromAccessibility(intent, attempts) ||
            sendLaunchIntent(intent, "application", attempts)

    private suspend fun observeAfterWait(waitMs: Long): ForegroundObservation {
        delay(waitMs)
        return foregroundPackage()
    }

    private suspend fun retryAccessibilityLaunch(
        intent: Intent,
        attempts: ArrayNode,
        waitMs: Long
    ): AppOpenAttempt {
        val sent = sendLaunchIntentFromAccessibility(intent, attempts)
        val foreground = if (sent) observeAfterWait(waitMs) else foregroundPackage()
        return AppOpenAttempt(sent = sent, foreground = foreground)
    }

    private suspend fun retryShellLaunch(
        packageName: String,
        activityName: String?,
        attempts: ArrayNode
    ): AppOpenAttempt {
        val sent = sendLaunchIntentFromShell(packageName, activityName, attempts)
        val foreground = if (sent) observeAfterWait(SHELL_OPEN_WAIT_MS) else foregroundPackage()
        return AppOpenAttempt(sent = sent, foreground = foreground)
    }

    private fun appOpenResult(
        packageName: String,
        launch: AppOpenAttempt,
        attempts: ArrayNode,
        waitMs: Long
    ): ObjectNode =
        mapper.createObjectNode().apply {
            put("ok", launch.sent && launch.foreground.packageName == packageName)
            put("package", packageName)
            put("foregroundPackage", launch.foreground.packageName)
            put("verificationSource", launch.foreground.source)
            addForegroundDetails(launch.foreground)
            put("wait_ms", waitMs)
            set<JsonNode>("launchAttempts", attempts)
            if (launch.foreground.packageName != packageName) {
                put("error", "launch intent sent but target app foreground could not be verified")
            }
        }

    private fun ObjectNode.addForegroundDetails(foreground: ForegroundObservation) {
        if (foreground.cachedPackage.isNotBlank()) put("cachedForegroundPackage", foreground.cachedPackage)
        if (foreground.activeWindowPackage.isNotBlank()) put("activeWindowPackage", foreground.activeWindowPackage)
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
        runCatching { UiAccessibilityService.dumpTreeWithTimeout(mapper, maxDepth, DUMP_TREE_TIMEOUT_MS) }
            .getOrElse { error ->
                mapper.createObjectNode().apply { put("error", error.message ?: "dump_tree failed") }
            }

    private suspend fun foregroundPackage(): ForegroundObservation {
        val activeWindow = UiAccessibilityService
            .activeWindowPackageWithTimeout(ACTIVE_WINDOW_PROBE_TIMEOUT_MS)
            .packageName
        if (activeWindow.isNotBlank()) {
            return ForegroundObservation(
                packageName = activeWindow,
                source = "active_window",
                cachedPackage = UiAccessibilityService.currentPackage(),
                activeWindowPackage = activeWindow
            )
        }
        val cached = UiAccessibilityService.currentPackage()
        if (cached.isNotBlank()) {
            return ForegroundObservation(
                packageName = cached,
                source = "cached_accessibility_event",
                cachedPackage = cached,
                activeWindowPackage = ""
            )
        }
        return ForegroundObservation(
            packageName = "",
            source = "unavailable",
            cachedPackage = "",
            activeWindowPackage = ""
        )
    }

    private fun sendLaunchIntent(intent: Intent, source: String, attempts: ArrayNode): Boolean =
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
        attempts: ArrayNode
    ): Boolean =
        runCatching { UiAccessibilityService.startActivityFromService(intent) }
            .getOrDefault(false)
            .also { sent ->
                attempts.add(mapper.createObjectNode().apply {
                    put("source", "accessibility")
                    put("sent", sent)
                })
            }

    private fun sendLaunchIntentFromShell(
        packageName: String,
        activityName: String?,
        attempts: ArrayNode
    ): Boolean =
        runCatching {
            PrivilegeManager.launchApp(packageName, activityName, timeoutSeconds = SHELL_LAUNCH_TIMEOUT_SECONDS)
                .onEach { attempts.add(shellLaunchAttemptNode(it)) }
                .any { it.ok }
        }.getOrElse { error ->
            attempts.add(mapper.createObjectNode().apply {
                put("source", "shizuku")
                put("sent", false)
                put("error", error.message ?: "Shizuku launch failed")
            })
            false
        }

    private fun shellLaunchAttemptNode(shell: PrivilegeManager.AppLaunchShellResult): ObjectNode =
        mapper.createObjectNode().apply {
            put("source", "shizuku:${shell.strategy}")
            put("sent", shell.ok)
            shell.result.stderr.takeIf { it.isNotBlank() }?.let { put("error", it.take(500)) }
            shell.result.stdout.takeIf { !shell.ok && it.isNotBlank() }?.let { put("stdout", it.take(500)) }
            if (shell.result.timedOut) put("timed_out", true)
        }

    private fun numberArg(step: JsonNode, name: String): Double? {
        val value = step.get(name) ?: return null
        return if (value.isNumber) value.asDouble() else null
    }

    private fun normalizedObserve(value: String): String =
        when (value) {
            "none", "final", "after_each", "on_failure" -> value
            else -> "final"
        }

    private data class RunOptions(
        val observe: String,
        val abortOnFailure: Boolean,
        val stepDelayMs: Long,
        val finalMaxDepth: Int
    )

    private data class StepRunResult(
        val ok: Boolean,
        val failedStep: Int,
        val aborted: Boolean,
        val results: ArrayNode
    )

    private data class AppOpenAttempt(
        val sent: Boolean,
        val foreground: ForegroundObservation
    )

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
        private const val DEFAULT_OPEN_WAIT_MS = 1_500L
        private const val SHELL_OPEN_WAIT_MS = 3_500L
        private const val SHELL_LAUNCH_TIMEOUT_SECONDS = 8L
        private const val DEFAULT_WAIT_MS = 500L
        private const val DEFAULT_SWIPE_MS = 300L
        private const val DEFAULT_LONG_PRESS_MS = 800L
        private const val MIN_LONG_PRESS_MS = 300L
        private const val MAX_LONG_PRESS_MS = 3_000L
        private const val MAX_WAIT_MS = 3_000L
        private const val ACTIVE_WINDOW_PROBE_TIMEOUT_MS = 300L
        private const val DUMP_TREE_TIMEOUT_MS = 2_500L
        private const val DEFAULT_TREE_DEPTH = 12
        private const val MAX_TREE_DEPTH = 30
    }
}
