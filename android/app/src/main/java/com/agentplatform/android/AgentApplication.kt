package com.agentplatform.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class AgentApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createForegroundChannel()
    }

    private fun createForegroundChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "Agent connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification while the agent is connected to the server."
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)

            val confirm = NotificationChannel(
                CONFIRM_CHANNEL_ID,
                "Sensitive tool approval",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications asking you to approve a sensitive tool call."
            }
            nm.createNotificationChannel(confirm)
        }
    }

    companion object {
        const val FOREGROUND_CHANNEL_ID = "agent.foreground"
        const val CONFIRM_CHANNEL_ID = "agent.confirm"
    }
}
