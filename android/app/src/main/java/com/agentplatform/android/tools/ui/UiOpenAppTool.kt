package com.agentplatform.android.tools.ui

import android.content.Context
import android.content.Intent
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.privilege.PrivilegeManager
import com.agentplatform.android.ui.accessibility.UiAccessibilityService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.delay

/**
 * Bring an installed app to the foreground by package name. This is a small
 * navigation helper so the agent can inspect apps without relying on adb.
 */
class UiOpenAppTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {
    override val name = "ui.open_app"

    override val safetyLevel = "local_state"

    override val description = """
        Open an installed Android app by package name and verify it reaches the
        foreground. Use before ui.dump_tree or ui.screen_capture when the user
        asks to inspect another app. Examples: WeChat is com.tencent.mm, QQ is
        com.tencent.mobileqq.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree("""
        {
          "type": "object",
          "properties": {
            "package": {
              "type": "string",
              "description": "Android package name, e.g. com.tencent.mm for WeChat."
            }
          },
          "required": ["package"]
        }
    """.trimIndent())

    override suspend fun execute(args: JsonNode): JsonNode {
        val packageName = args.path("package").asText("")
        if (packageName.isBlank()) {
            return missingPackage(args)
        }

        val launchTarget = launchTarget(packageName)
            ?: knownLaunchTarget(packageName)
            ?: return appNotFound(args, packageName)

        val launch = startLaunch(packageName, launchTarget)
        if (!launch.initialResult.sent) {
            return launchFailed(args, packageName, launch.initialResult)
        }

        if (!UiAccessibilityService.isAvailable()) {
            return accessibilityDisabled(args, packageName)
        }

        return launchResponse(args, packageName, verifyLaunch(packageName, launch))
    }

    private fun missingPackage(args: JsonNode): JsonNode =
        ToolResultEnvelope.error(
            mapper = mapper,
            tool = this,
            code = "invalid_args",
            message = "missing package",
            request = args
        )

    private fun appNotFound(args: JsonNode, packageName: String): JsonNode {
        val message = "app not installed or has no launcher activity"
        val result = mapper.createObjectNode().apply {
            put("opened", false)
            put("package", packageName)
            put("error", message)
            set<ObjectNode>("error_detail", errorDetail("app_not_found", message, retryable = false))
        }
        return standardResult(args, result, ok = false)
    }

    private fun startLaunch(packageName: String, launchTarget: LaunchTarget): LaunchAttempt {
        val intent = launchTarget.intent.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        val launchStartedAtMs = System.currentTimeMillis()
        val initialResult = sendLaunchIntent(packageName, launchTarget.activityName, intent)
        return LaunchAttempt(
            target = launchTarget,
            initialResult = initialResult,
            results = mutableListOf(initialResult),
            startedAtMs = launchStartedAtMs
        )
    }

    private fun launchFailed(args: JsonNode, packageName: String, launchResult: LaunchResult): JsonNode {
        val message = launchResult.error ?: "failed to start activity"
        val result = mapper.createObjectNode().apply {
            put("package", packageName)
            put("opened", false)
            put("intentSent", false)
            put("verified", false)
            put("error", message)
            set<ObjectNode>("error_detail", errorDetail("launch_failed", message, retryable = true))
        }
        return standardResult(args, result, ok = false)
    }

    private fun accessibilityDisabled(args: JsonNode, packageName: String): JsonNode {
        val message = "accessibility service not enabled; foreground package could not be verified"
        val result = mapper.createObjectNode().apply {
            put("opened", false)
            put("intentSent", true)
            put("package", packageName)
            put("verified", false)
            put("error", message)
            set<ObjectNode>(
                "error_detail",
                errorDetail("accessibility_disabled", message, retryable = true, hint = ACCESSIBILITY_HINT)
            )
        }
        return standardResult(args, result, ok = false)
    }

    private suspend fun verifyLaunch(packageName: String, launch: LaunchAttempt): LaunchVerification {
        var observation = waitForForegroundPackage(packageName, DEFAULT_VERIFY_WAIT_MS, launch.startedAtMs)
        observation = retryFromAccessibilityIfNeeded(packageName, launch, observation)
        observation = retryFromShellIfNeeded(packageName, launch, observation)
        return LaunchVerification(
            observation = observation,
            results = launch.results,
            initialResult = launch.initialResult,
            waitMs = DEFAULT_VERIFY_WAIT_MS
        )
    }

    private suspend fun retryFromAccessibilityIfNeeded(
        packageName: String,
        launch: LaunchAttempt,
        observation: ForegroundObservation
    ): ForegroundObservation {
        if (observation.packageName == packageName || launch.initialResult.source != "application") return observation
        val accessibilityResult = sendLaunchIntentFromAccessibility(launch.target.intent)
        launch.results += accessibilityResult
        return if (accessibilityResult.sent) {
            waitForForegroundPackage(packageName, DEFAULT_VERIFY_WAIT_MS, System.currentTimeMillis())
        } else {
            observation
        }
    }

    private suspend fun retryFromShellIfNeeded(
        packageName: String,
        launch: LaunchAttempt,
        observation: ForegroundObservation
    ): ForegroundObservation {
        if (observation.packageName == packageName) return observation
        val shellResults = sendLaunchIntentFromShell(packageName, launch.target.activityName)
        launch.results += shellResults
        return if (shellResults.any { it.sent }) {
            waitForForegroundPackage(packageName, SHELL_VERIFY_WAIT_MS, System.currentTimeMillis())
        } else {
            observation
        }
    }

    private fun launchResponse(args: JsonNode, packageName: String, verification: LaunchVerification): JsonNode {
        val observation = verification.observation
        val foregroundPackage = observation.packageName
        val opened = foregroundPackage == packageName
        val intentSentButUnverified = !opened && intentSentButUnverified(foregroundPackage)
        val result = mapper.createObjectNode().apply {
            addLaunchSummary(packageName, verification, opened, intentSentButUnverified)
            addObservationFields(observation)
            set<JsonNode>("launchAttempts", launchAttemptsNode(verification.results))
            if (!opened) addUnopenedDetails(intentSentButUnverified)
        }
        return standardResult(args, result, ok = opened || intentSentButUnverified)
    }

    private fun ObjectNode.addLaunchSummary(
        packageName: String,
        verification: LaunchVerification,
        opened: Boolean,
        intentSentButUnverified: Boolean
    ) {
        put("opened", opened)
        put("intentSent", true)
        put("launchedFrom", verification.results.lastOrNull { it.sent }?.source ?: verification.initialResult.source)
        put("package", packageName)
        put("foregroundPackage", verification.observation.packageName)
        put("verified", opened)
        put("verificationSource", verification.observation.source)
        put("intentSentButUnverified", intentSentButUnverified)
        put("blockedBySystem", false)
        put("waitMs", verification.waitMs)
    }

    private fun ObjectNode.addObservationFields(observation: ForegroundObservation) {
        if (observation.cachedPackage.isNotBlank()) put("cachedForegroundPackage", observation.cachedPackage)
        if (observation.activeWindowPackage.isNotBlank()) put("activeWindowPackage", observation.activeWindowPackage)
        observation.cachedPackageAgeMs?.let { put("cachedForegroundPackageAgeMs", it) }
    }

    private fun launchAttemptsNode(results: List<LaunchResult>) =
        mapper.createArrayNode().apply {
            results.forEach { result ->
                add(mapper.createObjectNode().apply {
                    put("source", result.source)
                    put("sent", result.sent)
                    if (!result.error.isNullOrBlank()) put("error", result.error)
                })
            }
        }

    private fun ObjectNode.addUnopenedDetails(intentSentButUnverified: Boolean) {
        if (intentSentButUnverified) {
            put("warning", "launch intent was sent, but Accessibility did not provide a fresh foreground package")
            set<ObjectNode>("next", mapper.createObjectNode().apply {
                put("tool", "ui.dump_tree")
                put("reason", "verify the current foreground screen after launch")
            })
        } else {
            val message = "launch intent was sent, but another foreground package was observed"
            put("error", message)
            put("hint", FOREGROUND_MISMATCH_HINT)
            set<ObjectNode>(
                "error_detail",
                errorDetail("foreground_verification_mismatch", message, retryable = true, hint = FOREGROUND_MISMATCH_HINT)
            )
        }
    }

    private fun intentSentButUnverified(foregroundPackage: String): Boolean =
        foregroundPackage.isBlank() ||
            foregroundPackage == "com.agentplatform.android" ||
            foregroundPackage.contains("launcher") ||
            foregroundPackage.contains("home")

    private fun errorDetail(
        code: String,
        message: String,
        retryable: Boolean,
        hint: String? = null
    ): ObjectNode =
        mapper.createObjectNode().apply {
            put("code", code)
            put("message", message)
            put("retryable", retryable)
            hint?.let { put("hint", it) }
        }

    private fun standardResult(args: JsonNode, result: ObjectNode, ok: Boolean): JsonNode =
        ToolResultEnvelope.applyStandardFields(mapper, this, result, ok = ok, request = args)

    private fun sendLaunchIntent(packageName: String, activityName: String?, intent: Intent): LaunchResult {
        val shellResult = sendLaunchIntentFromShell(packageName, activityName).firstOrNull { it.sent }
        if (shellResult != null) return shellResult
        if (UiAccessibilityService.isAvailable()) {
            val accessibilityResult = sendLaunchIntentFromAccessibility(intent)
            if (accessibilityResult.sent) return accessibilityResult
        }

        try {
            context.startActivity(intent)
            return LaunchResult(sent = true, source = "application")
        } catch (appError: Exception) {
            if (!UiAccessibilityService.isAvailable()) {
                return LaunchResult(sent = false, source = "application", error = appError.message)
            }
            return sendLaunchIntentFromAccessibility(intent, appError.message)
        }
    }

    private fun sendLaunchIntentFromAccessibility(intent: Intent, fallbackError: String? = null): LaunchResult {
        val sentFromAccessibility = UiAccessibilityService.startActivityFromService(intent)
        return if (sentFromAccessibility) {
            LaunchResult(sent = true, source = "accessibility")
        } else {
            LaunchResult(sent = false, source = "accessibility", error = fallbackError)
        }
    }

    private suspend fun waitForForegroundPackage(packageName: String, waitMs: Long, minUpdatedAtMs: Long): ForegroundObservation {
        val deadline = System.currentTimeMillis() + waitMs
        var lastObservation = foregroundPackage(packageName, minUpdatedAtMs)
        while (System.currentTimeMillis() < deadline) {
            lastObservation = foregroundPackage(packageName, minUpdatedAtMs)
            if (lastObservation.packageName == packageName) return lastObservation
            delay(250L)
        }
        val finalObservation = foregroundPackage(packageName, minUpdatedAtMs)
        return if (finalObservation.packageName.isNotBlank()) finalObservation else lastObservation
    }

    private suspend fun foregroundPackage(expectedPackage: String, minUpdatedAtMs: Long): ForegroundObservation {
        val cached = UiAccessibilityService.currentPackage(minUpdatedAtMs)
        val cachedUpdatedAt = UiAccessibilityService.currentPackageUpdatedAtMs()
        val cachedAgeMs = if (cachedUpdatedAt > 0L) System.currentTimeMillis() - cachedUpdatedAt else null
        if (cached == expectedPackage) {
            return ForegroundObservation(
                packageName = cached,
                source = "cached_accessibility_event",
                cachedPackage = cached,
                activeWindowPackage = "",
                cachedPackageAgeMs = cachedAgeMs
            )
        }
        val activeWindowResult = UiAccessibilityService.activeWindowPackageWithTimeout(ACTIVE_WINDOW_PROBE_TIMEOUT_MS)
        val activeWindow = activeWindowResult.packageName
        val packageName = when {
            activeWindow.isNotBlank() -> activeWindow
            cached.isNotBlank() -> cached
            else -> ""
        }
        val source = when {
            activeWindow.isNotBlank() -> "active_window"
            cached.isNotBlank() -> "cached_accessibility_event"
            else -> "unavailable"
        }
        return ForegroundObservation(
            packageName = packageName,
            source = source,
            cachedPackage = cached,
            activeWindowPackage = activeWindow,
            cachedPackageAgeMs = cachedAgeMs
        )
    }

    private fun sendLaunchIntentFromShell(packageName: String, activityName: String?): List<LaunchResult> =
        runCatching {
            PrivilegeManager.launchApp(packageName, activityName, timeoutSeconds = SHELL_LAUNCH_TIMEOUT_SECONDS)
                .map { shell ->
                    LaunchResult(
                        sent = shell.ok,
                        source = "shizuku:${shell.strategy}",
                        error = shell.result.stderr.takeIf { it.isNotBlank() }
                            ?: shell.result.stdout.takeIf { !shell.ok && it.isNotBlank() }
                            ?: if (shell.result.timedOut) "timed out" else null
                    )
                }
        }.getOrElse { error ->
            listOf(LaunchResult(sent = false, source = "shizuku", error = error.message))
        }

    private fun launchTarget(packageName: String): LaunchTarget? {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return LaunchTarget(intent, intent.component?.className)
    }

    private fun knownLaunchTarget(packageName: String): LaunchTarget? =
        when (packageName) {
            "com.tencent.mm" -> launcherTarget(packageName, "com.tencent.mm.ui.LauncherUI")
            "com.tencent.mobileqq" -> launcherTarget(packageName, "com.tencent.mobileqq.activity.SplashActivity")
            "com.max.xiaoheihe" -> launcherTarget(packageName, "com.max.xiaoheihe.SplashActivity")
            else -> null
        }

    private fun launcherTarget(packageName: String, activityName: String): LaunchTarget =
        LaunchTarget(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(packageName, activityName),
            activityName
        )

    private data class LaunchResult(
        val sent: Boolean,
        val source: String,
        val error: String? = null
    )

    private data class LaunchTarget(
        val intent: Intent,
        val activityName: String?
    )

    private data class LaunchAttempt(
        val target: LaunchTarget,
        val initialResult: LaunchResult,
        val results: MutableList<LaunchResult>,
        val startedAtMs: Long
    )

    private data class LaunchVerification(
        val observation: ForegroundObservation,
        val results: List<LaunchResult>,
        val initialResult: LaunchResult,
        val waitMs: Long
    )

    private data class ForegroundObservation(
        val packageName: String,
        val source: String,
        val cachedPackage: String,
        val activeWindowPackage: String,
        val cachedPackageAgeMs: Long?
    )

    private companion object {
        private const val DEFAULT_VERIFY_WAIT_MS = 2_500L
        private const val SHELL_VERIFY_WAIT_MS = 4_000L
        private const val SHELL_LAUNCH_TIMEOUT_SECONDS = 8L
        private const val ACTIVE_WINDOW_PROBE_TIMEOUT_MS = 300L
        private const val ACCESSIBILITY_HINT = "Enable Agent Platform in Android Accessibility settings."
        private const val FOREGROUND_MISMATCH_HINT =
            "Call ui.dump_tree or ui.screen_capture to verify the current screen before deciding recovery."
    }
}
