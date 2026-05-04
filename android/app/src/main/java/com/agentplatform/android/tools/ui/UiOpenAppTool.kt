package com.agentplatform.android.tools.ui

import android.content.Context
import android.content.Intent
import com.agentplatform.android.core.tool.Tool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

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
        Open an installed Android app by package name and bring it to the foreground.
        Use before ui.dump_tree or ui.screen_capture when the user asks to inspect
        another app. Examples: WeChat is com.tencent.mm, QQ is com.tencent.mobileqq.
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
        context.startActivity(intent)
        return mapper.createObjectNode().apply {
            put("opened", true)
            put("package", packageName)
        }
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
}
