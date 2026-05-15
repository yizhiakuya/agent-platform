package com.agentplatform.android.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.agentplatform.android.ui.accessibility.UiAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process rendezvous for Android MediaStore write/delete/favorite/trash
 * confirmation flows.
 */
object MediaStoreRequestBridge {
    const val EXTRA_REQUEST_ID = "requestId"
    const val EXTRA_PENDING_INTENT = "pendingIntent"
    const val EXTRA_AUTO_APPROVE = "autoApprove"

    private const val TIMEOUT_MS = 24_000L
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    suspend fun request(
        context: Context,
        pendingIntent: PendingIntent,
        autoApprove: Boolean = false
    ): Boolean {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = deferred

        val intent = Intent(context, MediaStoreRequestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_REQUEST_ID, id)
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            putExtra(EXTRA_AUTO_APPROVE, autoApprove)
        }

        return try {
            if (autoApprove) {
                UiAccessibilityService.armMediaStoreConfirmationAutoApprove()
            }
            context.startActivity(intent)
            withTimeoutOrNull(TIMEOUT_MS) { deferred.await() } == true
        } finally {
            if (autoApprove) {
                UiAccessibilityService.disarmMediaStoreConfirmationAutoApprove()
            }
            pending.remove(id)
        }
    }

    fun resolve(requestId: String?, approved: Boolean) {
        if (requestId.isNullOrBlank()) return
        pending.remove(requestId)?.complete(approved)
    }
}
