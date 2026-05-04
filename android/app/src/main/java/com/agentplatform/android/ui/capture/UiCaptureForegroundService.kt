package com.agentplatform.android.ui.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.agentplatform.android.R
import com.agentplatform.android.ui.MainActivity

/**
 * Mandatory foreground service for MediaProjection. From Android 14 (API 34)
 * MediaProjection MUST run inside an FGS of type {@code mediaProjection} —
 * trying to call {@code MediaProjectionManager.getMediaProjection} from a
 * plain background context throws SecurityException.
 *
 * Lifecycle:
 *   - MainActivity gets the consent intent result, sends it here as an
 *     Intent extra, we startForeground + UiCaptureManager.start.
 *   - "Stop" notification action calls UiCaptureManager.stop and stops self.
 *   - System callback (user dismissed the screencast indicator) → manager
 *     stops itself, we observe and self-stop too.
 */
class UiCaptureForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            UiCaptureManager.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        if (intent?.action == ACTION_START && UiCaptureManager.isReady().not()) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
            if (data != null) {
                UiCaptureManager.start(this, resultCode, data)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        UiCaptureManager.stop()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent screen capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "在屏幕识别期间持续运行" }
        nm.createNotificationChannel(channel)

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, UiCaptureForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ui_capture_running))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: must specify FGS type that matches the manifest declaration.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "ui.capture.START"
        const val ACTION_STOP = "ui.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        private const val CHANNEL_ID = "ui-capture"
        private const val NOTIFICATION_ID = 0xC4 // 196

        fun startWithGrantedProjection(ctx: Context, resultCode: Int, data: Intent) {
            val intent = Intent(ctx, UiCaptureForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            ctx.startForegroundService(intent)
        }

        fun stopService(ctx: Context) {
            val intent = Intent(ctx, UiCaptureForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            ctx.startService(intent)
        }
    }
}
