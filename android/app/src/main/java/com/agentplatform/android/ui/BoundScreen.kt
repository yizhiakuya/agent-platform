package com.agentplatform.android.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.agentplatform.android.data.AppPrefs
import com.agentplatform.android.service.AgentForegroundService

/**
 * Status / management screen shown after the device is bound. Shows live WS
 * status from {@link AgentForegroundService#status}, server / device IDs, and
 * one-tap shortcuts to permission settings + unbind.
 */
@Composable
fun BoundScreen(prefs: AppPrefs, onUnbind: () -> Unit) {
    val ctx = LocalContext.current
    val status by AgentForegroundService.status.collectAsState()

    val notifGranted = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    } else true
    val photosGranted = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED
    } else true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Agent Platform", style = MaterialTheme.typography.headlineSmall)

        StatusCard(status = status)

        InfoCard(
            label = "服务器",
            value = prefs.serverUrl ?: "(未知)"
        )
        InfoCard(
            label = "设备 ID",
            value = prefs.deviceId ?: "(未知)"
        )

        if (!notifGranted) {
            PermissionCard(
                title = "通知权限",
                desc = "Android 13+ 必须开通知权限,前台服务才能保持运行。",
                actionLabel = "打开设置"
            ) { openAppSettings(ctx as Activity) }
        }
        if (!photosGranted) {
            PermissionCard(
                title = "媒体读取权限",
                desc = "photos.list_recent 工具需要此权限,不给将返回空列表。",
                actionLabel = "打开设置"
            ) { openAppSettings(ctx as Activity) }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { openBatterySettings(ctx as Activity) },
                modifier = Modifier.fillMaxWidth().padding(end = 0.dp)
            ) { Text("电池白名单") }
        }

        Button(
            onClick = onUnbind,
            modifier = Modifier.fillMaxWidth()
        ) { Text("解绑设备") }

        Text(
            "解绑会停止 WebSocket 服务并清除本地 token,重新绑定需要在网页端生成新的二维码。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusCard(status: String) {
    val tint = when {
        status.startsWith("已收到") || status == "已连接" ->
            MaterialTheme.colorScheme.primary
        status.startsWith("连接已关闭") || status.startsWith("收到但解析失败") ->
            MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("WebSocket 连接状态", style = MaterialTheme.typography.labelMedium)
            Text(status, style = MaterialTheme.typography.titleMedium, color = tint)
        }
    }
}

@Composable
private fun InfoCard(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    desc: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

private fun openAppSettings(activity: Activity) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", activity.packageName, null)
    }
    activity.startActivity(intent)
}

private fun openBatterySettings(activity: Activity) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${activity.packageName}")
    }
    runCatching { activity.startActivity(intent) }
        .onFailure {
            // Fallback to general battery optimization list when manufacturer
            // disables the per-app deep link (some MIUI / EMUI builds).
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
}
