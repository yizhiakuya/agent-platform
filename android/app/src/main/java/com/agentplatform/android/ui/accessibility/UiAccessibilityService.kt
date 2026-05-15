package com.agentplatform.android.ui.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class UiTypeTextResult(
    val typed: Boolean,
    val method: String? = null,
    val reason: String? = null,
    val editableCandidates: Int = 0,
    val focusedInput: Boolean = false
)

/**
 * The single point through which the agent drives other apps' UI:
 * tap / swipe / type / global gestures, and dump the AccessibilityNodeInfo
 * tree of the foreground window so the LLM can read text and resource ids
 * without taking a screenshot.
 *
 * The service runs in the same process as [AgentForegroundService] (the
 * default — we don't declare android:process) so a static [instance]
 * reference is safe. Tools call methods on the singleton; if it's null the
 * user hasn't enabled the service in Settings → Accessibility yet.
 *
 * The user MUST enable us via system Settings — apps cannot programmatically
 * enable accessibility on their own (correct, this would be a malware
 * vector). The BoundScreen card surfaces the current state and a deep link
 * to the system settings page.
 */
class UiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "UiAccessibilityService"
        private const val MIN_LONG_PRESS_MS = 300L
        private const val MAX_LONG_PRESS_MS = 3_000L
        private const val MEDIA_CONFIRM_AUTO_APPROVE_MS = 12_000L

        private val mediaStoreConfirmPackages = setOf(
            "com.google.android.providers.media.module",
            "com.android.providers.media.module",
            "com.android.providers.media",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.miui.securitycenter"
        )

        private val mediaStoreConfirmTextHints = listOf(
            "移至回收站",
            "移到回收站",
            "放入回收站",
            "删除",
            "Trash",
            "Move to trash",
            "move to trash"
        )

        private val mediaStoreApproveTextHints = listOf(
            "允许",
            "确定",
            "确认",
            "删除",
            "移至回收站",
            "移到回收站",
            "Allow",
            "OK",
            "Move to trash",
            "Trash"
        )

        @Volatile
        private var instance: UiAccessibilityService? = null

        @Volatile
        private var currentPackageName: String = ""

        @Volatile
        private var currentPackageUpdatedAtMs: Long = 0L

        @Volatile
        private var mediaStoreConfirmAutoApproveUntilMs: Long = 0L

        /** True when the user has enabled the service and the system has
         *  bound to it (onServiceConnected fired). */
        fun isAvailable(): Boolean = instance != null

        fun canTakeScreenshots(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && instance != null

        fun currentPackage(minUpdatedAtMs: Long = 0L): String =
            if (currentPackageUpdatedAtMs >= minUpdatedAtMs) currentPackageName else ""

        fun armMediaStoreConfirmationAutoApprove() {
            mediaStoreConfirmAutoApproveUntilMs =
                System.currentTimeMillis() + MEDIA_CONFIRM_AUTO_APPROVE_MS
        }

        fun disarmMediaStoreConfirmationAutoApprove() {
            mediaStoreConfirmAutoApproveUntilMs = 0L
        }

        /** Internal — tools should not directly hold this; they go through
         *  the public methods so we can swap implementation later (e.g.
         *  add ratelimiting / confirm gating without touching call sites). */
        private fun require(): UiAccessibilityService =
            instance ?: error("Accessibility service not enabled — open Settings → Accessibility → Agent Platform")

        /** Single-finger tap at (x, y) in screen pixels. Suspends until the
         *  gesture is dispatched (a few hundred ms typically). */
        suspend fun tap(x: Float, y: Float): Boolean {
            val s = require()
            val path = Path().apply { moveTo(x, y) }
            // 1ms duration approximates a single ACTION_DOWN/UP click.
            val stroke = GestureDescription.StrokeDescription(path, 0L, 1L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return s.dispatchGestureAwait(gesture)
        }

        /** Single-finger long press at (x, y), useful for app image menus. */
        suspend fun longPress(x: Float, y: Float, durationMs: Long): Boolean {
            val s = require()
            val pressMs = durationMs.coerceIn(MIN_LONG_PRESS_MS, MAX_LONG_PRESS_MS)
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, pressMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return s.dispatchGestureAwait(gesture)
        }

        /** Single-finger swipe from (x1, y1) to (x2, y2) over [durationMs]. */
        suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
            val s = require()
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return s.dispatchGestureAwait(gesture)
        }

        /**
         * Type [text] into the active input target. Prefer Accessibility's
         * SET_TEXT API, then the Android 13+ accessibility input connection,
         * then a conservative clipboard paste fallback.
         */
        fun typeText(text: String): UiTypeTextResult {
            val s = require()
            val focused = s.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                try {
                    trySetText(focused, text)?.let {
                        return it.copy(focusedInput = true)
                    }
                    tryPasteText(s, focused, text)?.let {
                        return it.copy(focusedInput = true)
                    }
                } finally {
                    focused.recycle()
                }
            }

            tryInputConnectionText(s, text)?.let { return it }

            val candidates = findEditableNodes(s.rootInActiveWindow)
            try {
                if (candidates.size == 1) {
                    val target = candidates[0]
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    trySetText(target, text)?.let {
                        return it.copy(editableCandidates = 1)
                    }
                    tryPasteText(s, target, text)?.let {
                        return it.copy(editableCandidates = 1)
                    }
                    tryInputConnectionText(s, text)?.let {
                        return it.copy(editableCandidates = 1)
                    }
                    return UiTypeTextResult(
                        typed = false,
                        reason = "single editable node found, but it rejected SET_TEXT and paste actions",
                        editableCandidates = 1
                    )
                }

                return UiTypeTextResult(
                    typed = false,
                    reason = when (candidates.size) {
                        0 -> "no editable field or active input connection is available"
                        else -> "multiple editable fields are visible; tap the intended input first"
                    },
                    editableCandidates = candidates.size
                )
            } finally {
                candidates.forEach { it.recycle() }
            }
        }

        /**
         * Run a global system action. Allowed names map to AccessibilityService
         * GLOBAL_ACTION_*: BACK / HOME / RECENTS / NOTIFICATIONS / QUICK_SETTINGS /
         * POWER_DIALOG / LOCK_SCREEN / TAKE_SCREENSHOT.
         */
        fun globalAction(action: String): Boolean {
            val s = require()
            val code = when (action.uppercase()) {
                "BACK" -> GLOBAL_ACTION_BACK
                "HOME" -> GLOBAL_ACTION_HOME
                "RECENTS" -> GLOBAL_ACTION_RECENTS
                "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
                "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
                "POWER_DIALOG" -> GLOBAL_ACTION_POWER_DIALOG
                "LOCK_SCREEN" -> GLOBAL_ACTION_LOCK_SCREEN
                "TAKE_SCREENSHOT" -> GLOBAL_ACTION_TAKE_SCREENSHOT
                else -> return false
            }
            return s.performGlobalAction(code)
        }

        /**
         * Start an activity from the AccessibilityService context. Some Android
         * builds treat normal foreground-service launches as background app
         * starts, while a user-enabled accessibility service is allowed to help
         * the user navigate between apps.
         */
        fun startActivityFromService(intent: Intent): Boolean {
            val s = require()
            return runCatching {
                s.startActivity(intent)
                true
            }.getOrDefault(false)
        }

        /**
         * Walk the AccessibilityNodeInfo tree rooted at the active window and
         * emit a structured JSON snapshot. Only nodes that carry usable
         * semantics for the LLM — text / contentDescription / resource id /
         * clickable / editable / scrollable — are kept; visual containers
         * are flattened so the tree the LLM sees is small.
         */
        fun dumpTree(mapper: ObjectMapper, maxDepth: Int): ObjectNode {
            val s = require()
            val out = mapper.createObjectNode()
            val root = s.rootInActiveWindow
            if (root == null) {
                out.put("error", "no active window — locked screen or service not yet bound")
                return out
            }
            try {
                val packageName = root.packageName?.toString() ?: ""
                currentPackageName = packageName
                currentPackageUpdatedAtMs = System.currentTimeMillis()
                out.put("package", packageName)
                val nodesArr = mapper.createArrayNode()
                walk(root, "", maxDepth, mapper, nodesArr)
                out.set<JsonNode>("nodes", nodesArr)
            } finally {
                root.recycle()
            }
            return out
        }

        suspend fun screenshot(): Bitmap? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val s = require()
            return suspendCoroutine { cont ->
                s.takeScreenshot(
                    0,
                    { command -> Handler(Looper.getMainLooper()).post(command) },
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            cont.resume(Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace))
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "takeScreenshot failed: $errorCode")
                            cont.resume(null)
                        }
                    }
                )
            }
        }

        private fun walk(
            node: AccessibilityNodeInfo,
            path: String,
            depthLeft: Int,
            mapper: ObjectMapper,
            out: ArrayNode
        ) {
            if (depthLeft <= 0) return
            val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            val rid = node.viewIdResourceName?.takeIf { it.isNotBlank() }
            val clickable = node.isClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable
            // Only emit nodes the LLM can actually act on or learn from.
            if (text != null || desc != null || rid != null || clickable || editable || scrollable) {
                val n = mapper.createObjectNode()
                n.put("path", path)
                if (text != null) n.put("text", text.take(200))
                if (desc != null) n.put("desc", desc.take(200))
                if (rid != null) n.put("id", rid)
                val cls = node.className?.toString()
                if (cls != null) n.put("class", cls.substringAfterLast('.'))
                if (clickable) n.put("clickable", true)
                if (editable) n.put("editable", true)
                if (scrollable) n.put("scrollable", true)
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty) {
                    val b = mapper.createObjectNode()
                    b.put("l", rect.left); b.put("t", rect.top)
                    b.put("r", rect.right); b.put("b", rect.bottom)
                    n.set<ObjectNode>("bounds", b)
                }
                out.add(n)
            }
            val n = node.childCount
            for (i in 0 until n) {
                val child = node.getChild(i) ?: continue
                try {
                    walk(child, if (path.isEmpty()) "$i" else "$path.$i", depthLeft - 1, mapper, out)
                } finally {
                    child.recycle()
                }
            }
        }

        private fun trySetText(node: AccessibilityNodeInfo, text: String): UiTypeTextResult? {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                UiTypeTextResult(typed = true, method = "accessibility_set_text")
            } else {
                null
            }
        }

        private fun tryPasteText(
            service: UiAccessibilityService,
            node: AccessibilityNodeInfo,
            text: String
        ): UiTypeTextResult? {
            return runCatching {
                val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Agent Platform", text))
                val currentLength = node.text?.length ?: 0
                if (currentLength > 0) {
                    val selectionArgs = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentLength)
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    UiTypeTextResult(typed = true, method = "clipboard_paste")
                } else {
                    null
                }
            }.getOrNull()
        }

        private fun tryInputConnectionText(
            service: UiAccessibilityService,
            text: String
        ): UiTypeTextResult? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            return runCatching {
                val inputMethod = service.inputMethod ?: return null
                if (!inputMethod.currentInputStarted) return null
                val connection = inputMethod.currentInputConnection ?: return null
                val surrounding = connection.getSurroundingText(100_000, 100_000, 0)
                if (surrounding != null) {
                    val existingText = surrounding.text ?: ""
                    val start = surrounding.offset
                    val end = start + existingText.length
                    connection.setSelection(start, end)
                }
                connection.commitText(text, 1, null)
                UiTypeTextResult(typed = true, method = "accessibility_input_connection")
            }.getOrNull()
        }

        private fun findEditableNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
            if (root == null) return emptyList()
            val out = mutableListOf<AccessibilityNodeInfo>()
            try {
                collectEditableNodes(root, out)
            } finally {
                root.recycle()
            }
            return out
        }

        private fun collectEditableNodes(
            node: AccessibilityNodeInfo,
            out: MutableList<AccessibilityNodeInfo>
        ) {
            if (node.isVisibleToUser && node.isEnabled && node.isEditable) {
                out.add(AccessibilityNodeInfo.obtain(node))
            }
            val n = node.childCount
            for (i in 0 until n) {
                val child = node.getChild(i) ?: continue
                try {
                    collectEditableNodes(child, out)
                } finally {
                    child.recycle()
                }
            }
        }

        private fun shouldAutoApproveMediaStoreConfirmation(event: AccessibilityEvent?): Boolean {
            if (mediaStoreConfirmAutoApproveUntilMs < System.currentTimeMillis()) return false
            val packageName = event?.packageName?.toString() ?: currentPackageName
            if (packageName !in mediaStoreConfirmPackages) return false
            val root = instance?.rootInActiveWindow ?: return false
            return try {
                treeContainsAny(root, mediaStoreConfirmTextHints)
            } finally {
                root.recycle()
            }
        }

        private fun tryAutoApproveMediaStoreConfirmation(): Boolean {
            val s = instance ?: return false
            val root = s.rootInActiveWindow ?: return false
            return try {
                val button = findClickableByText(root, mediaStoreApproveTextHints)
                if (button != null) {
                    try {
                        button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } finally {
                        button.recycle()
                    }
                } else {
                    false
                }
            } finally {
                root.recycle()
            }
        }

        private fun treeContainsAny(node: AccessibilityNodeInfo, hints: List<String>): Boolean {
            if (nodeMatchesAny(node, hints)) return true
            val n = node.childCount
            for (i in 0 until n) {
                val child = node.getChild(i) ?: continue
                try {
                    if (treeContainsAny(child, hints)) return true
                } finally {
                    child.recycle()
                }
            }
            return false
        }

        private fun findClickableByText(
            node: AccessibilityNodeInfo,
            hints: List<String>
        ): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.isEnabled && node.isClickable && nodeMatchesAny(node, hints)) {
                return AccessibilityNodeInfo.obtain(node)
            }
            if (node.isVisibleToUser && node.isEnabled && nodeMatchesAny(node, hints)) {
                nearestClickable(node)?.let { return it }
            }
            val n = node.childCount
            for (i in 0 until n) {
                val child = node.getChild(i) ?: continue
                try {
                    findClickableByText(child, hints)?.let { return it }
                } finally {
                    child.recycle()
                }
            }
            return null
        }

        private fun nearestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var parent = node.parent
            while (parent != null) {
                if (parent.isVisibleToUser && parent.isEnabled && parent.isClickable) {
                    return parent
                }
                val next = parent.parent
                parent.recycle()
                parent = next
            }
            return null
        }

        private fun nodeMatchesAny(node: AccessibilityNodeInfo, hints: List<String>): Boolean {
            val values = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString()
            )
            return values.any { value -> hints.any { hint -> value.contains(hint, ignoreCase = true) } }
        }
    }

    private suspend fun dispatchGestureAwait(gesture: GestureDescription): Boolean =
        suspendCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { cont.resume(true) }
                override fun onCancelled(g: GestureDescription) { cont.resume(false) }
            }
            val ok = dispatchGesture(gesture, callback, null)
            if (!ok) cont.resume(false)
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected — agent UI control online")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        currentPackageName = ""
        currentPackageUpdatedAtMs = 0L
        Log.i(TAG, "AccessibilityService unbound — agent UI control offline")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString()
        if (!packageName.isNullOrBlank()) {
            currentPackageName = packageName
            currentPackageUpdatedAtMs = System.currentTimeMillis()
        }
        if (shouldAutoApproveMediaStoreConfirmation(event) && tryAutoApproveMediaStoreConfirmation()) {
            mediaStoreConfirmAutoApproveUntilMs = 0L
            Log.i(TAG, "Auto-approved Android MediaStore confirmation")
        }
    }

    override fun onInterrupt() {
        // Required by the abstract class. No long-running work to interrupt.
    }
}
