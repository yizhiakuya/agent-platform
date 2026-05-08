package com.agentplatform.android.tools.ui

import android.content.Context
import android.content.Intent
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.ui.accessibility.UiAccessibilityService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
            return mapper.createObjectNode().apply { put("error", "missing package") }
        }

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: knownLaunchIntent(packageName)
            ?: return mapper.createObjectNode().apply {
                put("opened", false)
                put("package", packageName)
                put("error", "app not installed or has no launcher activity")
            }

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        val launchResults = mutableListOf<LaunchResult>()
        val launchResult = sendLaunchIntent(intent)
        launchResults += launchResult
        if (!launchResult.sent) {
            return mapper.createObjectNode().apply {
                put("opened", false)
                put("intentSent", false)
                put("package", packageName)
                put("verified", false)
                put("error", launchResult.error ?: "failed to start activity")
            }
        }

        if (!UiAccessibilityService.isAvailable()) {
            return mapper.createObjectNode().apply {
                put("opened", false)
                put("intentSent", true)
                put("package", packageName)
                put("verified", false)
                put("error", "accessibility service not enabled; foreground package could not be verified")
            }
        }

        val waitMs = 2500L
        var foregroundPackage = waitForForegroundPackage(packageName, waitMs)
        if (foregroundPackage != packageName && launchResult.source == "application" && UiAccessibilityService.isAvailable()) {
            val accessibilityResult = sendLaunchIntentFromAccessibility(intent)
            launchResults += accessibilityResult
            if (accessibilityResult.sent) {
                foregroundPackage = waitForForegroundPackage(packageName, waitMs)
            }
        }
        return mapper.createObjectNode().apply {
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
            }
        }
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

    private suspend fun waitForForegroundPackage(packageName: String, waitMs: Long): String {
        val deadline = System.currentTimeMillis() + waitMs
        var lastPackage = ""
        while (System.currentTimeMillis() < deadline) {
            lastPackage = foregroundPackage()
            if (lastPackage == packageName) return lastPackage
            delay(250L)
        }
        return foregroundPackage().ifBlank { lastPackage }
    }

    private fun foregroundPackage(): String =
        runCatching {
            UiAccessibilityService.dumpTree(mapper, 1).path("package").asText("")
        }.getOrDefault("")

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
