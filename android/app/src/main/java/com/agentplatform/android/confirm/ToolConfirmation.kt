package com.agentplatform.android.confirm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.agentplatform.android.AgentApplication
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * In-process rendezvous between AgentForegroundService and ConfirmActivity.
 * Sensitive tools execute only after the user approves this prompt.
 */
object ToolConfirmation {
    const val TIMEOUT_MS = 60_000L

    const val ACTION_APPROVE = "com.agentplatform.android.confirm.APPROVE"
    const val ACTION_DENY = "com.agentplatform.android.confirm.DENY"
    const val EXTRA_REQUEST_ID = "requestId"
    const val EXTRA_TOOL_NAME = "toolName"
    const val EXTRA_SUMMARY = "summary"
    const val EXTRA_NOTIFICATION_ID = "notificationId"

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val requestMutex = Mutex()

    suspend fun request(context: Context, toolName: String, args: JsonNode): Boolean = requestMutex.withLock {
        val id = UUID.randomUUID().toString()
        val notificationId = id.hashCode() and Int.MAX_VALUE
        val deferred = CompletableDeferred<Boolean>()

        pending[id] = deferred

        val intent = Intent(context, ConfirmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_REQUEST_ID, id)
            putExtra(EXTRA_TOOL_NAME, toolName)
            putExtra(EXTRA_SUMMARY, summarize(toolName, args))
        }
        notify(context, id, notificationId, toolName, summarize(toolName, args), intent)
        runCatching { context.startActivity(intent) }

        val approved = withTimeoutOrNull(TIMEOUT_MS) { deferred.await() } == true
        pending.remove(id)
        cancelNotification(context, notificationId)
        return approved
    }

    fun resolve(requestId: String?, approved: Boolean) {
        if (requestId.isNullOrBlank()) return
        pending.remove(requestId)?.complete(approved)
    }

    private fun summarize(toolName: String, args: JsonNode): String {
        val body = when (toolName) {
            "ui.tap" -> "点击 x=${args.path("x").asText("?")}, y=${args.path("y").asText("?")}"
            "ui.swipe" -> "滑动 (${args.path("x1").asText("?")}, ${args.path("y1").asText("?")}) 到 (${args.path("x2").asText("?")}, ${args.path("y2").asText("?")})"
            "ui.type_text" -> "输入文本长度=${args.path("text").asText("").length}"
            "ui.global" -> "系统操作 ${args.path("action").asText("?")}"
            else -> args.toString().take(500)
        }
        return body.take(500)
    }

    private fun notify(
        context: Context,
        requestId: String,
        notificationId: Int,
        toolName: String,
        summary: String,
        intent: Intent
    ) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val approvePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            decisionIntent(context, ACTION_APPROVE, requestId, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val denyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId xor 0x40000000,
            decisionIntent(context, ACTION_DENY, requestId, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AgentApplication.CONFIRM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("需要批准: $toolName")
            .setContentText(summary)
            .setSubText(toolName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(pendingIntent)
            .setDeleteIntent(denyPendingIntent)
            .addAction(android.R.drawable.ic_delete, "拒绝", denyPendingIntent)
            .addAction(android.R.drawable.ic_input_add, "批准", approvePendingIntent)
            .setAutoCancel(false)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(pendingIntent, true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private fun decisionIntent(
        context: Context,
        action: String,
        requestId: String,
        notificationId: Int
    ): Intent = Intent(context, ToolConfirmationReceiver::class.java).apply {
        this.action = action
        putExtra(EXTRA_REQUEST_ID, requestId)
        putExtra(EXTRA_NOTIFICATION_ID, notificationId)
    }

    private fun cancelNotification(context: Context, notificationId: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId)
    }
}
