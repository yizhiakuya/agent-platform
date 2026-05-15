package com.agentplatform.android.tools.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.agentplatform.android.core.tool.Tool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Locale

/**
 * List or search launchable apps visible to Agent Platform. Use this before
 * ui.open_app when the user gives an app's display name instead of a package.
 */
class UiListAppsTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {
    override val name = "ui.list_apps"

    override val description = """
        List launchable Android apps on the phone and their package/activity
        names. Use this before ui.open_app when the user names an app in natural
        language (for example "小黑盒", "微信", "QQ") or when ui.open_app says
        the guessed package is not installed. Supports fuzzy query over label,
        package, and launcher activity. Returns compact candidates sorted by
        relevance, including the package to pass to ui.open_app.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree("""
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "Optional app label/package/activity substring, e.g. 小黑盒, heihei, com.max."
            },
            "limit": {
              "type": "integer",
              "minimum": 1,
              "maximum": 100,
              "default": 20,
              "description": "Max apps to return."
            },
            "include_system": {
              "type": "boolean",
              "default": false,
              "description": "Include system apps. Usually keep false to reduce noise."
            }
          }
        }
    """.trimIndent())

    override suspend fun execute(args: JsonNode): JsonNode {
        val query = args.path("query").asText("").trim()
        val limit = args.path("limit").asInt(20).coerceIn(1, 100)
        val includeSystem = args.path("include_system").asBoolean(false)
        val normalizedQuery = normalize(query)

        val apps = launchableApps()
            .asSequence()
            .filter { includeSystem || !it.isSystem }
            .mapNotNull { app ->
                val score = score(app, normalizedQuery)
                if (normalizedQuery.isNotBlank() && score <= 0) null else app to score
            }
            .sortedWith(compareByDescending<Pair<AppInfo, Int>> { it.second }
                .thenBy { it.first.label.lowercase(Locale.ROOT) }
                .thenBy { it.first.packageName })
            .take(limit)
            .toList()

        return mapper.createObjectNode().apply {
            put("query", query)
            put("count", apps.size)
            put("limit", limit)
            put("include_system", includeSystem)
            val arr = mapper.createArrayNode()
            apps.forEach { (app, score) ->
                arr.add(mapper.createObjectNode().apply {
                    put("label", app.label)
                    put("package", app.packageName)
                    put("activity", app.activityName)
                    put("system", app.isSystem)
                    if (normalizedQuery.isNotBlank()) put("score", score)
                })
            }
            set<JsonNode>("apps", arr)
        }
    }

    private fun launchableApps(): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { info ->
                val appInfo = info.activityInfo.applicationInfo
                AppInfo(
                    label = info.loadLabel(pm)?.toString().orEmpty().ifBlank { info.activityInfo.packageName },
                    packageName = info.activityInfo.packageName,
                    activityName = info.activityInfo.name,
                    isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .distinctBy { it.packageName + "/" + it.activityName }
    }

    private fun score(app: AppInfo, normalizedQuery: String): Int {
        if (normalizedQuery.isBlank()) return 1
        val label = normalize(app.label)
        val packageName = normalize(app.packageName)
        val activity = normalize(app.activityName)
        return when {
            label == normalizedQuery -> 100
            packageName == normalizedQuery -> 95
            label.startsWith(normalizedQuery) -> 80
            packageName.startsWith(normalizedQuery) -> 75
            label.contains(normalizedQuery) -> 60
            packageName.contains(normalizedQuery) -> 55
            activity.contains(normalizedQuery) -> 40
            else -> 0
        }
    }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace("\\s+".toRegex(), "")

    private data class AppInfo(
        val label: String,
        val packageName: String,
        val activityName: String,
        val isSystem: Boolean
    )
}
