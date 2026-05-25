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
        val request = closeRequest(args)
        val targetResolution = resolveTarget(args)
        validateRequest(request, targetResolution)?.let { return it }
        return closeRecentTask(request, targetResolution)
    }

    private fun closeRequest(args: JsonNode): CloseRequest =
        CloseRequest(
            mode = args.path("mode").asText("recent_task").trim().lowercase(Locale.ROOT),
            timeoutMs = args.path("timeout_ms").asLong(DEFAULT_TIMEOUT_MS).coerceIn(1000L, 12000L),
            steps = mapper.createArrayNode(),
            raw = args
        )

    private fun validateRequest(request: CloseRequest, target: TargetResolution): JsonNode? =
        when {
            target.error != null -> closeFailure(request, target, target.error, target.hint)
            target.packageName.isBlank() -> closeFailure(
                request,
                target,
                "unable_to_resolve_target_package",
                "Provide package directly, or use a more specific label/query."
            )
            target.packageName in protectedPackages() -> closeFailure(
                request,
                target,
                "protected_package_blocked",
                "Refusing to close protected/system-critical package: ${target.packageName}"
            )
            request.mode == "force_stop" -> closeFailure(
                request,
                target,
                "privileged_access_required",
                "force_stop requires Shizuku-backed implementation; this build currently supports recent_task only."
            )
            request.mode != "recent_task" -> closeFailure(
                request,
                target,
                "invalid_mode",
                "mode must be recent_task or force_stop"
            )
            !UiAccessibilityService.isAvailable() -> closeFailure(
                request,
                target,
                "accessibility_disabled",
                "Enable Agent Platform Accessibility service in Android settings."
            )
            else -> null
        }

    private fun closeFailure(
        request: CloseRequest,
        target: TargetResolution,
        error: String,
        hint: String?
    ): JsonNode =
        closeResult(
            closeResultSpec(
                request = request,
                target = target.target,
                observedState = emptyObservedState(),
                ok = false,
                verified = false,
                error = error,
                hint = hint
            )
        )

    private suspend fun closeRecentTask(request: CloseRequest, targetResolution: TargetResolution): JsonNode {
        val beforeForeground = observeForegroundPackage()
        val beforeRecentsProbe = probeRecentsForTarget(
            target = targetResolution,
            timeoutMs = request.timeoutMs,
            openRecents = true,
            steps = request.steps
        )
        val beforeInRecents = beforeRecentsProbe.matched
        if (!beforeRecentsProbe.recentsOpened) {
            return recentsUnavailableResult(request, targetResolution, beforeForeground, beforeInRecents)
        }

        val dismissResult = dismissTargetCard(beforeRecentsProbe, request.steps)
        UiAccessibilityService.globalAction("HOME")
        delay(250L)

        val verification = verifyRecentTaskClosed(request, targetResolution)
        val ok = dismissResult.dispatched && verification.verified

        return closeResult(
            closeResultSpec(
                request = request,
                target = targetResolution.target,
                observedState = observedState(
                    beforeForeground,
                    beforeInRecents,
                    verification.afterForeground,
                    verification.afterInRecents
                ),
                ok = ok,
                verified = verification.verified,
                error = if (ok) null else buildFailureMessage(
                    dismissResult.dispatched,
                    verification.notForeground,
                    verification.removedFromRecents
                ),
                hint = if (ok) null else "If recents card did not dismiss on this ROM, inspect with ui.dump_tree and retry."
            )
        )
    }

    private suspend fun recentsUnavailableResult(
        request: CloseRequest,
        target: TargetResolution,
        beforeForeground: String,
        beforeInRecents: Boolean
    ): JsonNode =
        closeResult(
            closeResultSpec(
                request = request,
                target = target.target,
                observedState = observedState(
                    beforeForeground = beforeForeground,
                    beforeInRecents = beforeInRecents,
                    afterForeground = observeForegroundPackage(),
                    afterInRecents = beforeInRecents
                ),
                ok = false,
                verified = false,
                error = "recents_unavailable",
                hint = "Could not open Android Recents via accessibility global action."
            )
        )

    private suspend fun verifyRecentTaskClosed(
        request: CloseRequest,
        target: TargetResolution
    ): CloseVerification {
        val afterForeground = observeForegroundPackage()
        val afterRecentsProbe = probeRecentsForTarget(
            target = target,
            timeoutMs = request.timeoutMs,
            openRecents = true,
            steps = request.steps
        )
        UiAccessibilityService.globalAction("HOME")

        val afterInRecents = afterRecentsProbe.matched
        val notForeground = afterForeground != target.packageName
        val removedFromRecents = !afterInRecents
        return CloseVerification(
            afterForeground = afterForeground,
            afterInRecents = afterInRecents,
            notForeground = notForeground,
            removedFromRecents = removedFromRecents,
            verified = notForeground && removedFromRecents,
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
        if (openRecents && !openRecents(steps)) {
            return emptyRecentsProbe(recentsOpened = false)
        }

        val tree = UiAccessibilityService.dumpTreeWithTimeout(mapper, RECENTS_TREE_DEPTH, timeoutMs)
        val candidates = recentTaskCandidates(tree, target)
        val matchedBounds = bestRecentMatch(candidates)

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

    private suspend fun openRecents(steps: ArrayNode): Boolean {
        val opened = UiAccessibilityService.globalAction("RECENTS")
        steps.addObject().apply {
            put("name", "open_recents")
            put("ok", opened)
        }
        if (opened) delay(500L)
        return opened
    }

    private fun emptyRecentsProbe(recentsOpened: Boolean): RecentsProbeResult =
        RecentsProbeResult(
            recentsOpened = recentsOpened,
            matched = false,
            cardBounds = null,
            candidates = emptyList()
        )

    private fun recentTaskCandidates(tree: JsonNode, target: TargetResolution): List<RecentsCandidate> {
        val nodes = tree.path("nodes")
        if (!nodes.isArray) return emptyList()

        val matcher = RecentTargetMatcher(
            packageName = target.packageName,
            normalizedPackage = normalize(target.packageName),
            labelNeedle = normalize(target.label),
            queryNeedle = normalize(target.query)
        )
        return nodes.mapNotNull { node -> recentTaskCandidate(node, matcher) }
    }

    private fun recentTaskCandidate(node: JsonNode, matcher: RecentTargetMatcher): RecentsCandidate? {
        val text = node.path("text").asText("")
        val desc = node.path("desc").asText("")
        val id = node.path("id").asText("")
        val raw = "$text $desc $id"
        val hay = normalize(raw)
        if (hay.isBlank() || !looksLikeTaskCard(id, desc)) return null

        val bounds = parseBounds(node.path("bounds")) ?: return null
        val packageHint = extractPackageHint(raw)
        val match = matcher.match(hay, packageHint)
        return RecentsCandidate(
            matched = match.matched,
            label = text.ifBlank { desc }.take(100),
            packageHint = packageHint,
            bounds = bounds,
            packageHintMatched = match.packageHintMatched,
            packageTextMatched = match.packageTextMatched,
            labelMatched = match.labelMatched,
            queryMatched = match.queryMatched
        )
    }

    private fun looksLikeTaskCard(id: String, desc: String): Boolean =
        id.contains("task", ignoreCase = true) ||
            id.contains("recent", ignoreCase = true) ||
            desc.contains("recent", ignoreCase = true) ||
            desc.contains("未加锁") ||
            desc.contains("锁定")

    private fun bestRecentMatch(candidates: List<RecentsCandidate>): Bounds? =
        candidates.firstOrNull { it.packageHintMatched }?.bounds
            ?: candidates.firstOrNull { it.packageTextMatched }?.bounds
            ?: candidates.firstOrNull { it.labelMatched || it.queryMatched }?.bounds

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

    private fun closeResultSpec(
        request: CloseRequest,
        target: ObjectNode,
        observedState: ObjectNode,
        ok: Boolean,
        verified: Boolean,
        error: String?,
        hint: String?
    ): CloseResultSpec =
        CloseResultSpec(
            outcome = CloseOutcome(ok = ok, verified = verified, error = error, hint = hint),
            context = CloseResultContext(
                mode = request.mode,
                target = target,
                observedState = observedState,
                steps = request.steps,
                request = request.raw
            )
        )

    private fun closeResult(spec: CloseResultSpec): ObjectNode {
        val outcome = spec.outcome
        val context = spec.context
        val result = mapper.createObjectNode().apply {
            put("ok", outcome.ok)
            put("verified", outcome.verified)
            put("mode", context.mode)
            set<JsonNode>("target", context.target)
            set<JsonNode>("observed_state", context.observedState)
            set<JsonNode>("steps", context.steps)
            if (!outcome.error.isNullOrBlank()) put("error", outcome.error)
            if (!outcome.hint.isNullOrBlank()) put("hint", outcome.hint)
        }
        return ToolResultEnvelope.applyStandardFields(mapper, this, result, ok = outcome.ok, request = context.request)
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

    private data class CloseRequest(
        val mode: String,
        val timeoutMs: Long,
        val steps: ArrayNode,
        val raw: JsonNode
    )

    private data class CloseResultSpec(
        val outcome: CloseOutcome,
        val context: CloseResultContext
    )

    private data class CloseOutcome(
        val ok: Boolean,
        val verified: Boolean,
        val error: String?,
        val hint: String?
    )

    private data class CloseResultContext(
        val mode: String,
        val target: ObjectNode,
        val observedState: ObjectNode,
        val steps: ArrayNode,
        val request: JsonNode
    )

    private data class CloseVerification(
        val afterForeground: String,
        val afterInRecents: Boolean,
        val notForeground: Boolean,
        val removedFromRecents: Boolean,
        val verified: Boolean
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

    private data class RecentTargetMatcher(
        val packageName: String,
        val normalizedPackage: String,
        val labelNeedle: String,
        val queryNeedle: String
    ) {
        fun match(hay: String, packageHint: String?): RecentTargetMatch =
            RecentTargetMatch(
                packageHintMatched = !packageHint.isNullOrBlank() && packageHint == packageName,
                packageTextMatched = normalizedPackage.isNotBlank() && hay.contains(normalizedPackage),
                labelMatched = labelNeedle.isNotBlank() && hay.contains(labelNeedle),
                queryMatched = queryNeedle.isNotBlank() && hay.contains(queryNeedle)
            )
    }

    private data class RecentTargetMatch(
        val packageHintMatched: Boolean,
        val packageTextMatched: Boolean,
        val labelMatched: Boolean,
        val queryMatched: Boolean
    ) {
        val matched: Boolean
            get() = packageHintMatched || packageTextMatched || labelMatched || queryMatched
    }

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
