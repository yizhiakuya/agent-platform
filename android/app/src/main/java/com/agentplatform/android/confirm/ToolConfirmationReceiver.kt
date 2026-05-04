package com.agentplatform.android.confirm

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ToolConfirmationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(ToolConfirmation.EXTRA_REQUEST_ID)
        val approved = when (intent.action) {
            ToolConfirmation.ACTION_APPROVE -> true
            ToolConfirmation.ACTION_DENY -> false
            else -> return
        }
        ToolConfirmation.resolve(requestId, approved)

        val notificationId = intent.getIntExtra(ToolConfirmation.EXTRA_NOTIFICATION_ID, -1)
        if (notificationId >= 0) {
            context.getSystemService(NotificationManager::class.java).cancel(notificationId)
        }
    }
}
