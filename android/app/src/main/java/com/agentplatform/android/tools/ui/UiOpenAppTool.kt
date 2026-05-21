package com.agentplatform.android.tools.ui

import android.content.Context
import android.content.Intent
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
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
            return ToolResultEnvelope.error(
                mapper = mapper,
                tool = this,
                code = "invalid_args",
                message = "missing package",
                request = args
            )
        }

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: knownLaunchIntent(packageName)
            ?: return ToolResultEnvelope.applyStandardFields(mapper, this, mapper.createObjectNode().apply {
                put("opened", false)
                put("package", packageName)
                put("error", "app not installed or has no launcher activity")
                set<ObjectNode>("error_detail", mapper.createObjectNode().apply {
                    put("code", "app_not_found")
                    put("message", "app not installed or has no launcher activity")
                    put("retryable", false)
                })
            }, ok = false, request = args)

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        val launchResults = mutableListOf<LaunchResult>()
        val launchStartedAtMs = System.currentTimeMillis()
        val launchResult = sendLaunchIntent(intent)
        launchResults += launchResult
        if (!launchResult.sent) {
            return ToolResultEnvelope.applyStandardFields(mapper, this, mapper.createObjectNode().apply {
                put("opened", false)
                put("intentSent", false)
                put("package", packageName)
                put("verified", false)
                put("error", launchResult.error ?: "failed to start activity")
                set<ObjectNode>("error_detail", mapper.createObjectNode().apply {
                    put("code", "launch_failed")
                    put("message", launchResult.error ?: "failed to start activity")
                    put("retryable", true)
                })
            }, ok = false, request = args)
        }

        if (!UiAccessibilityService.isAvailable()) {
            return ToolResultEnvelope.applyStandardFields(mapper, this, mapper.createObjectNode().apply {
                put("opened", false)
                put("intentSent", true)
                put("package", packageName)
                put("verified", false)
                put("error", "accessibility service not enabled; foreground package could not be verified")
                set<ObjectNode>("error_detail", mapper.createObjectNode().apply {
                    put("code", "accessibility_disabled")
                    put("message", "accessibility service not enabled; foreground package could not be verified")
                    put("retryable", true)
                    put("hint", "Enable Agent Platform in Android Accessibility settings.")
                })
            }, ok = false, request = args)
        }

        val waitMs = DEFAULT_VERIFY_WAIT_MS
        var observation = waitForForegroundPackage(packageName, waitMs, launchStartedAtMs)
        if (observation.packageName != packageName && launchResult.source == "application" && UiAccessibilityService.isAvailable()) {
            val accessibilityResult = sendLaunchIntentFromAccessibility(intent)
            launchResults += accessibilityResult
            if (accessibilityResult.sent) {
                observation = waitForForegroundPackage(packageName, waitMs, System.currentTimeMillis())
            }
        }
        val foregroundPackage = observation.packageName
        val opened = foregroundPackage == packageName
        val intentSentButUnverified = !opened && (
            foregroundPackage.isBlank() ||
            foregroundPackage == "com.agentplatform.android" ||
            foregroundPackage.contains("launcher") ||
            foregroundPackage.contains("home")
        )
        val result = mapper.createObjectNode().apply {
            put("opened", opened)
            put("intentSent", true)
            put("launchedFrom", launchResults.lastOrNull { it.sent }?.source ?: launchResult.source)
            put("package", packageName)
            put("foregroundPackage", foregroundPackage)
            put("verified", opened)
            put("verificationSource", observation.source)
            put("intentSentButUnverified", intentSentButUnverified)
            put("blockedBySystem", false)
            put("waitMs", waitMs)
            if (observation.cachedPackage.isNotBlank()) put("cachedForegroundPackage", observation.cachedPackage)
            if (observation.activeWindowPackage.isNotBlank()) put("activeWindowPackage", observation.activeWindowPackage)
            if (observation.cachedPackageAgeMs != null) put("cachedForegroundPackageAgeMs", observation.cachedPackageAgeMs)
            val attempts = mapper.createArrayNode()
            launchResults.forEach { result ->
                attempts.add(mapper.createObjectNode().apply {
                    put("source", result.source)
                    put("sent", result.sent)
                    if (!result.error.isNullOrBlank()) put("error", result.error)
                })
            }
            set<JsonNode>("launchAttempts", attempts)
            if (!opened) {
                if (intentSentButUnverified) {
                    put(
                        "warning",
                        "launch intent was sent, but Accessibility did not provide a fresh foreground package"
                    )
                    set<ObjectNode>("next", mapper.createObjectNode().apply {
                        put("tool", "ui.dump_tree")
                        put("reason", "verify the current foreground screen after launch")
                    })
                } else {
                    val message =
                        "launch intent was sent, but another foreground package was observed"
                    put("error", message)
                    put("hint", "Call ui.dump_tree or ui.screen_capture to verify the current screen before deciding recovery.")
                    set<ObjectNode>("error_detail", mapper.createObjectNode().apply {
                        put("code", "foreground_verification_mismatch")
                        put("message", message)
                        put("retryable", true)
                        put("hint", "Call ui.dump_tree or ui.screen_capture to verify the current screen before deciding recovery.")
                    })
                }
            }
        }
        return ToolResultEnvelope.applyStandardFields(
            mapper,
            this,
            result,
            ok = opened || intentSentButUnverified,
            request = args
        )
    }

    private fun sendLaunchIntent(intent: Intent): LaunchResult {
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

    private data class LaunchResult(
        val sent: Boolean,
        val source: String,
        val error: String? = null
    )

    private data class ForegroundObservation(
        val packageName: String,
        val source: String,
        val cachedPackage: String,
        val activeWindowPackage: String,
        val cachedPackageAgeMs: Long?
    )

    private companion object {
        private const val DEFAULT_VERIFY_WAIT_MS = 1_500L
        private const val ACTIVE_WINDOW_PROBE_TIMEOUT_MS = 300L
    }
}
