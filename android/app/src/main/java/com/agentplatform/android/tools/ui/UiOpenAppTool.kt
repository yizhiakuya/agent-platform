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

        val waitMs = 2500L
        var foregroundPackage = waitForForegroundPackage(packageName, waitMs, launchStartedAtMs)
        if (foregroundPackage != packageName && launchResult.source == "application" && UiAccessibilityService.isAvailable()) {
            val accessibilityResult = sendLaunchIntentFromAccessibility(intent)
            launchResults += accessibilityResult
            if (accessibilityResult.sent) {
                foregroundPackage = waitForForegroundPackage(packageName, waitMs, System.currentTimeMillis())
            }
        }
        val opened = foregroundPackage == packageName
        val result = mapper.createObjectNode().apply {
            put("opened", foregroundPackage == packageName)
            put("intentSent", true)
            put("launchedFrom", launchResults.lastOrNull { it.sent }?.source ?: launchResult.source)
            put("package", packageName)
            put("foregroundPackage", foregroundPackage)
            put("verified", foregroundPackage == packageName)
            put("waitMs", waitMs)
            val attempts = mapper.createArrayNode()
            launchResults.forEach { result ->
                attempts.add(mapper.createObjectNode().apply {
                    put("source", result.source)
                    put("sent", result.sent)
                    if (!result.error.isNullOrBlank()) put("error", result.error)
                })
            }
            set<JsonNode>("launchAttempts", attempts)
            if (foregroundPackage != packageName) {
                put("blockedBySystem", true)
                put("error", "launch intent sent but target app did not reach foreground")
                put(
                    "hint",
                    "MIUI/HyperOS may require enabling background popup/background launch permission for Agent Platform"
                )
                set<ObjectNode>("error_detail", mapper.createObjectNode().apply {
                    put("code", "foreground_verification_failed")
                    put("message", "launch intent sent but target app did not reach foreground")
                    put("retryable", true)
                    put("hint", "MIUI/HyperOS may require enabling background popup/background launch permission for Agent Platform")
                })
            }
        }
        return ToolResultEnvelope.applyStandardFields(mapper, this, result, ok = opened, request = args)
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

    private suspend fun waitForForegroundPackage(packageName: String, waitMs: Long, minUpdatedAtMs: Long): String {
        val deadline = System.currentTimeMillis() + waitMs
        var lastPackage = ""
        while (System.currentTimeMillis() < deadline) {
            lastPackage = foregroundPackage(packageName, minUpdatedAtMs)
            if (lastPackage == packageName) return lastPackage
            delay(250L)
        }
        return foregroundPackage(packageName, minUpdatedAtMs).ifBlank { lastPackage }
    }

    private fun foregroundPackage(expectedPackage: String, minUpdatedAtMs: Long): String {
        val cached = UiAccessibilityService.currentPackage(minUpdatedAtMs)
        if (cached == expectedPackage) return cached
        return runCatching {
            UiAccessibilityService.dumpTree(mapper, 1).path("package").asText("")
        }.getOrDefault("").ifBlank { cached }
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
}
