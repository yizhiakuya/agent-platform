package com.agentplatform.android.tools.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.ui.accessibility.UiAccessibilityService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.Locale
import kotlinx.coroutines.delay

class AppsCloseTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {
    override val name: String = "apps.close"
    override val confirmRequired: Boolean = true
    override val safetyLevel: String = "sensitive_action"

    override val description: String = """
        Close an app with explicit semantics. mode=recent_task removes the app card
        from Android Recents and verifies the app is not foreground. mode=force_stop
        is reserved for privileged Shizuku force-stop and currently returns
        privileged_access_required if unavailable.
        Prefer package when known. label/query are fallback selectors and must
        resolve to a single candidate.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "mode": {
              "type": "string",
              "enum": ["recent_task", "force_stop"],
              "default": "recent_task"
            },
            "package": { "type": "string" },
            "label": { "type": "string" },
            "query": { "type": "string" },
            "timeout_ms": {
              "type": "integer",
              "minimum": 1000,
              "maximum": 12000,
              "default": 4000
            }
          },
          "required": ["mode"]
        }
        """.trimIndent()
    )

    override suspend fun execute(args: JsonNode): JsonNode {
        val mode = args.path("mode").asText("recent_task").trim().lowercase(Locale.ROOT)
        val timeoutMs = args.path("timeout_ms").asLong(DEFAULT_TIMEOUT_MS).coerceIn(1000L, 12000L)
        val steps = mapper.createArrayNode()

        val targetResolution = resolveTarget(args)
        if (targetResolution.error != null) {
            return closeResult(
                ok = false,
                verified = false,
                mode = mode,
                target = targetResolution.target,
                observedState = emptyObservedState(),
                steps = steps,
                error = targetResolution.error,
                hint = targetResolution.hint,
                request = args
            )
        }

        val targetPackage = targetResolution.packageName
        if (targetPackage.isBlank()) {
            return closeResult(
                ok = false,
                verified = false,
                mode = mode,
                target = targetResolution.target,
                observedState = emptyObservedState(),
                steps = steps,
                error = "unable_to_resolve_target_package",
                hint = "Provide package directly, or use a more specific label/query.",
                request = args
            )
        }
        if (targetPackage in protectedPackages()) {
            return closeResult(
                ok = false,
                verified = false,
                mode = mode,
                target = targetResolution.target,
                observedState = emptyObservedState(),
                steps = steps,
                error = "protected_package_blocked",
                hint = "Refusing to close protected/system-critical package: $targetPackage",
                request = args
            )
        }

        if (mode == "force_stop") {
            return closeResult(
                ok = false,
                verified = false,
                mode = mode,
                target = targetResolution.target,
                observedState = emptyObservedState(),
                steps = steps,
                error = "privileged_access_required",
                hint = "force_stop requires Shizuku-backed implementation; this build currently supports recent_task only.",
                request = args
            )
        }
        if (mode != "recent_task") {
            return closeResult(
                ok = false,
                verified = false,
                mode = mode,
                target = targetResolution.target,
                observedState = emptyObservedState(),
                steps = steps,
                error = "invalid_mode",
                hint = "mode must be recent_task or force_stop",
                request = args
            )
        }

        if (!UiAccessibilityService.isAvailable()) {
            return closeResult(
                ok = false,
                verified = false,
                mode = mode,
                target = targetResolution.target,
                observedState = emptyObservedState(),
                steps = steps,
                error = "accessibility_disabled",
                hint = "Enable Agent Platform Accessibility service in Android settings.",
                request = args
            )
        }

        val beforeForeground = observeForegroundPackage()
        val beforeRecentsProbe = probeRecentsForTarget(
            target = targetResolution,
            timeoutMs = timeoutMs,
            openRecents = true,
            steps = steps
        )
        val beforeInRecents = beforeRecentsProbe.matched
        if (!beforeRecentsProbe.recentsOpened) {
            return closeResult(
                ok = false,
                verified = false,
                mode = mode,
                target = targetResolution.target,
                observedState = observedState(
                    beforeForeground = beforeForeground,
                    beforeInRecents = beforeInRecents,
                    afterForeground = observeForegroundPackage(),
                    afterInRecents = beforeInRecents
                ),
                steps = steps,
                error = "recents_unavailable",
                hint = "Could not open Android Recents via accessibility global action.",
                request = args
            )
        }

        val dismissResult = dismissTargetCard(beforeRecentsProbe, steps)
        UiAccessibilityService.globalAction("HOME")
        delay(250L)

        val afterForeground = observeForegroundPackage()
        val afterRecentsProbe = probeRecentsForTarget(
            target = targetResolution,
            timeoutMs = timeoutMs,
            openRecents = true,
            steps = steps
        )
        UiAccessibilityService.globalAction("HOME")

        val afterInRecents = afterRecentsProbe.matched
        val notForeground = afterForeground != targetPackage
        val removedFromRecents = !afterInRecents
        val verified = notForeground && removedFromRecents
        val ok = dismissResult.dispatched && verified

        return closeResult(
            ok = ok,
            verified = verified,
            mode = mode,
            target = targetResolution.target,
            observedState = observedState(beforeForeground, beforeInRecents, afterForeground, afterInRecents),
            steps = steps,
            error = if (ok) null else buildFailureMessage(dismissResult.dispatched, notForeground, removedFromRecents),
            hint = if (ok) null else "If recents card did not dismiss on this ROM, inspect with ui.dump_tree and retry.",
            request = args
        )
    }

    private fun buildFailureMessage(dispatched: Boolean, notForeground: Boolean, removedFromRecents: Boolean): String =
        when {
            !dispatched -> "dismiss_gesture_failed"
            !removedFromRecents -> "target_still_in_recents"
            !notForeground -> "target_still_foreground"
            else -> "verification_failed"
        }

    private suspend fun dismissTargetCard(probe: RecentsProbeResult, steps: ArrayNode): DismissResult {
        if (!probe.matched || probe.cardBounds == null) {
            steps.addObject().apply {
                put("name", "dismiss_target_card")
                put("ok", false)
                put("error", "target_card_not_found_in_recents")
            }
            return DismissResult(dispatched = false)
        }
        val bounds = probe.cardBounds
        val centerX = (bounds.left + bounds.right) / 2f
        val startY = (bounds.top + bounds.bottom) / 2f
        val endY = (bounds.top - CARD_DISMISS_TRAVEL_PX).coerceAtLeast(20f)
        val dispatched = UiAccessibilityService.swipe(centerX, startY, centerX, endY, CARD_DISMISS_DURATION_MS)
        steps.addObject().apply {
            put("name", "dismiss_target_card")
            put("ok", dispatched)
            put("x", centerX.toDouble())
            put("y1", startY.toDouble())
            put("y2", endY.toDouble())
            put("duration_ms", CARD_DISMISS_DURATION_MS)
            set<JsonNode>("card_bounds", bounds.toJson(mapper))
        }
        delay(400L)
        return DismissResult(dispatched = dispatched)
    }

    private suspend fun probeRecentsForTarget(
        target: TargetResolution,
        timeoutMs: Long,
        openRecents: Boolean,
        steps: ArrayNode
    ): RecentsProbeResult {
        if (openRecents) {
            val opened = UiAccessibilityService.globalAction("RECENTS")
            steps.addObject().apply {
                put("name", "open_recents")
                put("ok", opened)
            }
            if (!opened) {
                return RecentsProbeResult(
                    recentsOpened = false,
                    matched = false,
                    cardBounds = null,
                    candidates = emptyList()
                )
            }
            delay(500L)
        }

        val tree = UiAccessibilityService.dumpTreeWithTimeout(mapper, RECENTS_TREE_DEPTH, timeoutMs)
        val nodes = tree.path("nodes")
        val candidates = mutableListOf<RecentsCandidate>()

        var matchedByPackageHint: Bounds? = null
        var matchedByPackageText: Bounds? = null
        var matchedByLabelOrQuery: Bounds? = null

        val normalizedPackage = normalize(target.packageName)
        val labelNeedle = normalize(target.label)
        val queryNeedle = normalize(target.query)

        if (nodes.isArray) {
            for (node in nodes) {
                val text = node.path("text").asText("")
                val desc = node.path("desc").asText("")
                val id = node.path("id").asText("")
                val raw = "$text $desc $id"
                val hay = normalize(raw)
                if (hay.isBlank()) continue

                val looksTaskCard = id.contains("task", ignoreCase = true) ||
                    id.contains("recent", ignoreCase = true) ||
                    desc.contains("recent", ignoreCase = true) ||
                    desc.contains("未加锁") ||
                    desc.contains("锁定")
                if (!looksTaskCard) continue

                val bounds = parseBounds(node.path("bounds")) ?: continue
                val packageHint = extractPackageHint(raw)
                val packageHintMatched = !packageHint.isNullOrBlank() && packageHint == target.packageName
                val packageTextMatched = normalizedPackage.isNotBlank() && hay.contains(normalizedPackage)
                val labelMatched = labelNeedle.isNotBlank() && hay.contains(labelNeedle)
                val queryMatched = queryNeedle.isNotBlank() && hay.contains(queryNeedle)
                val matched = packageHintMatched || packageTextMatched || labelMatched || queryMatched

                candidates += RecentsCandidate(
                    matched = matched,
                    label = text.ifBlank { desc }.take(100),
                    packageHint = packageHint,
                    bounds = bounds,
                    packageHintMatched = packageHintMatched,
                    packageTextMatched = packageTextMatched,
                    labelMatched = labelMatched,
                    queryMatched = queryMatched
                )

                if (packageHintMatched && matchedByPackageHint == null) {
                    matchedByPackageHint = bounds
                } else if (packageTextMatched && matchedByPackageText == null) {
                    matchedByPackageText = bounds
                } else if ((labelMatched || queryMatched) && matchedByLabelOrQuery == null) {
                    matchedByLabelOrQuery = bounds
                }
            }
        }

        val matchedBounds = matchedByPackageHint ?: matchedByPackageText ?: matchedByLabelOrQuery

        steps.addObject().apply {
            put("name", "scan_recents")
            put("ok", true)
            put("matched", matchedBounds != null)
            put("candidate_count", candidates.size)
            put("match_policy", "package_hint>package_text>label_query")
        }

        return RecentsProbeResult(
            recentsOpened = true,
            matched = matchedBounds != null,
            cardBounds = matchedBounds,
            candidates = candidates
        )
    }

    private fun observeForegroundPackage(): String {
        val cached = UiAccessibilityService.currentPackage()
        if (cached.isNotBlank()) return cached
        return UiAccessibilityService.activeWindowPackageWithTimeout(300L).packageName
    }

    private fun resolveTarget(args: JsonNode): TargetResolution {
        val packageName = args.path("package").asText("").trim()
        val label = args.path("label").asText("").trim()
        val query = args.path("query").asText("").trim()

        if (packageName.isNotBlank()) {
            val resolvedLabel = if (label.isNotBlank()) label else packageLabelFromPm(packageName).orEmpty()
            val node = mapper.createObjectNode().apply {
                put("package", packageName)
                if (resolvedLabel.isNotBlank()) put("label", resolvedLabel)
                if (query.isNotBlank()) put("query", query)
                put("resolved_by", "package")
            }
            return TargetResolution(
                packageName = packageName,
                label = resolvedLabel,
                query = query,
                target = node
            )
        }

        val searchTerm = when {
            label.isNotBlank() -> label
            query.isNotBlank() -> query
            else -> ""
        }
        if (searchTerm.isBlank()) {
            return TargetResolution(
                packageName = "",
                label = "",
                query = "",
                target = mapper.createObjectNode(),
                error = "missing_target",
                hint = "Provide package, or label/query to resolve app."
            )
        }

        val q = normalize(searchTerm)
        val candidates = launchableApps()
            .filter { app ->
                normalize(app.label).contains(q) || normalize(app.packageName).contains(q)
            }
            .sortedBy { it.label.lowercase(Locale.ROOT) }

        if (candidates.isEmpty()) {
            return TargetResolution(
                packageName = "",
                label = label,
                query = query,
                target = mapper.createObjectNode().apply {
                    if (label.isNotBlank()) put("label", label)
                    if (query.isNotBlank()) put("query", query)
                },
                error = "target_not_found",
                hint = "No installed launchable app matched label/query."
            )
        }

        if (candidates.size > 1) {
            val target = mapper.createObjectNode().apply {
                if (label.isNotBlank()) put("label", label)
                if (query.isNotBlank()) put("query", query)
                put("resolved_by", if (label.isNotBlank()) "label" else "query")
                set<JsonNode>("candidates", mapper.createArrayNode().apply {
                    candidates.take(MAX_CANDIDATES_IN_ERROR).forEach { app ->
                        addObject().apply {
                            put("label", app.label)
                            put("package", app.packageName)
                        }
                    }
                })
            }
            return TargetResolution(
                packageName = "",
                label = label,
                query = query,
                target = target,
                error = "ambiguous_target",
                hint = "Multiple apps matched. Provide package explicitly."
            )
        }

        val matched = candidates.first()
        val resolvedLabel = if (label.isNotBlank()) label else matched.label
        val target = mapper.createObjectNode().apply {
            put("package", matched.packageName)
            if (resolvedLabel.isNotBlank()) put("label", resolvedLabel)
            if (query.isNotBlank()) put("query", query)
            put("resolved_by", if (label.isNotBlank()) "label" else "query")
        }
        return TargetResolution(
            packageName = matched.packageName,
            label = resolvedLabel,
            query = query,
            target = target
        )
    }

    private fun packageLabelFromPm(packageName: String): String? {
        return runCatching {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo)?.toString()?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull()
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
                    isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .distinctBy { it.packageName }
            .filterNot { it.isSystem && it.packageName in protectedPackages() }
    }

    private fun parseBounds(node: JsonNode): Bounds? {
        if (!node.isObject) return null
        val left = node.path("l").asDouble(Double.NaN)
        val top = node.path("t").asDouble(Double.NaN)
        val right = node.path("r").asDouble(Double.NaN)
        val bottom = node.path("b").asDouble(Double.NaN)
        if (left.isNaN() || top.isNaN() || right.isNaN() || bottom.isNaN()) return null
        if (right <= left || bottom <= top) return null
        return Bounds(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    private fun extractPackageHint(input: String): String? {
        val regex = Regex("""[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+""")
        return regex.find(input)?.value
    }

    private fun observedState(
        beforeForeground: String,
        beforeInRecents: Boolean,
        afterForeground: String,
        afterInRecents: Boolean
    ): ObjectNode = mapper.createObjectNode().apply {
        set<JsonNode>("before", mapper.createObjectNode().apply {
            put("foreground_package", beforeForeground)
            put("in_recents", beforeInRecents)
        })
        set<JsonNode>("after", mapper.createObjectNode().apply {
            put("foreground_package", afterForeground)
            put("in_recents", afterInRecents)
        })
    }

    private fun emptyObservedState(): ObjectNode = observedState("", false, "", false)

    private fun closeResult(
        ok: Boolean,
        verified: Boolean,
        mode: String,
        target: ObjectNode,
        observedState: ObjectNode,
        steps: ArrayNode,
        error: String?,
        hint: String?,
        request: JsonNode
    ): ObjectNode {
        val result = mapper.createObjectNode().apply {
            put("ok", ok)
            put("verified", verified)
            put("mode", mode)
            set<JsonNode>("target", target)
            set<JsonNode>("observed_state", observedState)
            set<JsonNode>("steps", steps)
            if (!error.isNullOrBlank()) put("error", error)
            if (!hint.isNullOrBlank()) put("hint", hint)
        }
        return ToolResultEnvelope.applyStandardFields(mapper, this, result, ok = ok, request = request)
    }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace("\\s+".toRegex(), "")

    private fun protectedPackages(): Set<String> = setOf(
        context.packageName,
        "com.android.systemui",
        "com.android.settings",
        "com.miui.home",
        "com.android.launcher",
        "com.google.android.apps.nexuslauncher"
    )

    private data class AppInfo(
        val label: String,
        val packageName: String,
        val isSystem: Boolean
    )

    private data class TargetResolution(
        val packageName: String,
        val label: String,
        val query: String,
        val target: ObjectNode,
        val error: String? = null,
        val hint: String? = null
    )

    private data class DismissResult(
        val dispatched: Boolean
    )

    private data class RecentsProbeResult(
        val recentsOpened: Boolean,
        val matched: Boolean,
        val cardBounds: Bounds?,
        val candidates: List<RecentsCandidate>
    )

    private data class RecentsCandidate(
        val matched: Boolean,
        val label: String,
        val packageHint: String?,
        val bounds: Bounds,
        val packageHintMatched: Boolean,
        val packageTextMatched: Boolean,
        val labelMatched: Boolean,
        val queryMatched: Boolean
    )

    private data class Bounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        fun toJson(mapper: ObjectMapper): ObjectNode = mapper.createObjectNode().apply {
            put("l", left.toDouble())
            put("t", top.toDouble())
            put("r", right.toDouble())
            put("b", bottom.toDouble())
        }
    }

    private companion object {
        private const val DEFAULT_TIMEOUT_MS = 4000L
        private const val RECENTS_TREE_DEPTH = 24
        private const val CARD_DISMISS_DURATION_MS = 240L
        private const val CARD_DISMISS_TRAVEL_PX = 900f
        private const val MAX_CANDIDATES_IN_ERROR = 8
    }
}
