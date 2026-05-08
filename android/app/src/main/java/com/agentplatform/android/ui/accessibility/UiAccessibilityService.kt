package com.agentplatform.android.ui.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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

        @Volatile
        private var instance: UiAccessibilityService? = null

        /** True when the user has enabled the service and the system has
         *  bound to it (onServiceConnected fired). */
        fun isAvailable(): Boolean = instance != null

        fun canTakeScreenshots(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && instance != null

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
         * Type [text] into the currently focused editable node. Returns false
         * if no editable node has focus (LLM should usually call [tap] on the
         * input field first to focus it).
         */
        fun typeText(text: String): Boolean {
            val s = require()
            val focused = s.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: return false
            return try {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } finally {
                focused.recycle()
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
                out.put("package", root.packageName?.toString() ?: "")
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
        Log.i(TAG, "AccessibilityService unbound — agent UI control offline")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't subscribe to event observers right now — tools query state
        // on demand. Future: expose a tool that watches for specific events
        // (e.g. "wait until app X opens") via a subscription registry here.
    }

    override fun onInterrupt() {
        // Required by the abstract class. No long-running work to interrupt.
    }
}
