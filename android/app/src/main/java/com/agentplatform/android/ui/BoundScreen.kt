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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.agentplatform.android.data.AppPrefs
import com.agentplatform.android.privilege.PrivilegeManager
import com.agentplatform.android.service.AgentForegroundService
import com.agentplatform.android.ui.accessibility.UiAccessibilityService
import com.agentplatform.android.ui.capture.UiCaptureManager
import rikka.shizuku.Shizuku

/**
 * Status / management screen shown after the device is bound. Shows live WS
 * status from {@link AgentForegroundService#status}, server / device IDs, and
 * one-tap shortcuts to permission settings + unbind.
 */
@Composable
fun BoundScreen(
    prefs: AppPrefs,
    onRequestScreenCapture: () -> Unit,
    onUnbind: () -> Unit
) {
    val ctx = LocalContext.current
    val status by AgentForegroundService.status.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    fun isGranted(perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun checkNotif() = if (Build.VERSION.SDK_INT >= 33) isGranted(Manifest.permission.POST_NOTIFICATIONS) else true
    fun checkPhotos() = if (Build.VERSION.SDK_INT >= 33) isGranted(Manifest.permission.READ_MEDIA_IMAGES) else true
    // MIUI / HyperOS 把"照片和视频"合并成一个系统权限项,授权后通常两边都给,
    // 但有时只给 READ_MEDIA_VISUAL_USER_SELECTED(部分访问,Android 14+)。
    // 任一覆盖到视频的权限存在就算给了。
    fun checkVideos(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        if (isGranted(Manifest.permission.READ_MEDIA_VIDEO)) return true
        if (Build.VERSION.SDK_INT >= 34 && isGranted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")) return true
        return false
    }
    fun checkOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    val isXiaomiLike = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
        || Build.BRAND.equals("Xiaomi", ignoreCase = true)
        || Build.DISPLAY.contains("HyperOS", ignoreCase = true)

    var notifGranted by remember { mutableStateOf(checkNotif()) }
    var photosGranted by remember { mutableStateOf(checkPhotos()) }
    var videosGranted by remember { mutableStateOf(checkVideos()) }
    var overlayGranted by remember { mutableStateOf(checkOverlayPermission()) }
    var accessibilityReady by remember { mutableStateOf(UiAccessibilityService.isAvailable()) }
    var captureReady by remember { mutableStateOf(UiCaptureManager.isReady()) }
    var autoApproveUiTools by remember { mutableStateOf(prefs.autoApproveUiTools) }
    var privilegeStatus by remember { mutableStateOf(PrivilegeManager.status(ctx)) }

    // 用户从系统设置改完权限切回 app 时,Compose 默认不会重新检查 —
    // 监听 ON_RESUME 重算这三个状态,UI 自动重渲染。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifGranted = checkNotif()
                photosGranted = checkPhotos()
                videosGranted = checkVideos()
                overlayGranted = checkOverlayPermission()
                accessibilityReady = UiAccessibilityService.isAvailable()
                captureReady = UiCaptureManager.isReady()
                autoApproveUiTools = prefs.autoApproveUiTools
                privilegeStatus = PrivilegeManager.status(ctx)
            }
        }
        val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
            privilegeStatus = PrivilegeManager.status(ctx)
        }
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuListener) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { Shizuku.removeRequestPermissionResultListener(shizukuListener) }
        }
    }

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
        if (!videosGranted) {
            PermissionCard(
                title = "读取视频权限",
                desc = "videos.list_recent 工具需要,不给将看不到视频。",
                actionLabel = "打开设置"
            ) { openAppSettings(ctx as Activity) }
        }

        CapabilityCard(
            title = "屏幕操作",
            enabled = accessibilityReady,
            desc = if (accessibilityReady) {
                "已启用无障碍服务,agent 可以读取界面结构并执行点击、滑动、输入和系统返回等操作。"
            } else {
                "需要手动开启无障碍服务后,agent 才能操作其他应用。"
            },
            actionLabel = "打开无障碍设置"
        ) { openAccessibilitySettings(ctx as Activity) }

        CapabilityCard(
            title = "屏幕识别",
            enabled = accessibilityReady || captureReady,
            desc = if (accessibilityReady) {
                "已启用无障碍截图,agent 可按需获取当前屏幕截图用于视觉理解。"
            } else if (captureReady) {
                "已授权屏幕录制,agent 可按需获取当前屏幕截图用于视觉理解。"
            } else {
                "建议先开启无障碍服务；也可授权系统屏幕录制弹窗作为截图兜底。"
            },
            actionLabel = "允许屏幕识别"
        ) { onRequestScreenCapture() }

        CapabilityCard(
            title = "后台拉起应用",
            enabled = overlayGranted,
            desc = if (overlayGranted) {
                "已允许悬浮窗权限。小米/HyperOS 还需要在其他权限里允许后台弹出界面,用于 ui.open_app 直接把微信等应用拉到前台。"
            } else if (isXiaomiLike) {
                "小米/HyperOS 需要允许悬浮窗和后台弹出界面,否则 ui.open_app 会发出启动 intent,但目标应用可能停留在后台。"
            } else {
                "部分系统需要允许悬浮窗/后台弹出权限,否则后台服务不能直接把其他应用拉到前台。"
            },
            actionLabel = if (isXiaomiLike) "打开小米权限设置" else "打开悬浮窗设置"
        ) { openBackgroundLaunchSettings(ctx as Activity) }

        CapabilityCard(
            title = "高级权限通道",
            enabled = privilegeStatus.manageMediaGranted || privilegeStatus.shellAvailable,
            desc = when {
                privilegeStatus.manageMediaGranted ->
                    "已获得媒体管理权限，相册移入回收站/删除可以减少 Android 系统二次确认。"
                privilegeStatus.shellAvailable ->
                    "Shizuku 已运行并已授权，agent 可以使用白名单高级配置工具。"
                privilegeStatus.shizukuRunning ->
                    "Shizuku 已运行，但还没有授权 Agent Platform。"
                privilegeStatus.shizukuInstalled ->
                    "Shizuku 已安装但当前未运行。重启手机后需要重新用 Shizuku 启动服务。"
                else ->
                    "未检测到 Shizuku。高级权限是可选能力，普通相册和屏幕工具仍会继续工作。"
            },
            actionLabel = when {
                privilegeStatus.shizukuRunning && !privilegeStatus.shizukuPermissionGranted -> "授权 Shizuku"
                !privilegeStatus.manageMediaGranted && privilegeStatus.canRequestManageMedia -> "打开媒体管理权限"
                else -> "刷新状态"
            }
        ) {
            when {
                privilegeStatus.shizukuRunning && !privilegeStatus.shizukuPermissionGranted -> {
                    PrivilegeManager.requestShizukuPermission()
                    privilegeStatus = PrivilegeManager.status(ctx)
                }
                !privilegeStatus.manageMediaGranted && privilegeStatus.canRequestManageMedia -> {
                    runCatching { ctx.startActivity(PrivilegeManager.manageMediaSettingsIntent(ctx)) }
                    privilegeStatus = PrivilegeManager.status(ctx)
                }
                else -> privilegeStatus = PrivilegeManager.status(ctx)
            }
        }

        AutoApproveCard(
            enabled = autoApproveUiTools,
            onChange = {
                prefs.autoApproveUiTools = it
                autoApproveUiTools = it
            }
        )

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
private fun AutoApproveCard(
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    val container = if (enabled) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("自动批准屏幕操作", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (enabled) {
                        "已开启: ui.tap / ui.swipe / ui.type_text / ui.global 不再逐次弹确认。"
                    } else {
                        "未开启: 敏感屏幕操作会逐次弹出确认。"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = enabled, onCheckedChange = onChange)
        }
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

@Composable
private fun CapabilityCard(
    title: String,
    enabled: Boolean,
    desc: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    val container = if (enabled) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
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

private fun openAccessibilitySettings(activity: Activity) {
    activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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

private fun openBackgroundLaunchSettings(activity: Activity) {
    val packageName = activity.packageName
    val candidates = listOf(
        Intent(MIUI_APP_PERMISSION_EDITOR_ACTION)
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", packageName),
        Intent(MIUI_APP_PERMISSION_EDITOR_ACTION)
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
            .putExtra("extra_pkgname", packageName),
        Intent(MIUI_APP_PERMISSION_EDITOR_ACTION)
            .putExtra("extra_pkgname", packageName),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        },
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
    )
    val intent = candidates.firstOrNull { it.resolveActivity(activity.packageManager) != null }
        ?: candidates.last()
    runCatching { activity.startActivity(intent) }
        .onFailure { openAppSettings(activity) }
}

private const val MIUI_APP_PERMISSION_EDITOR_ACTION = "miui.intent.action.APP_PERM_EDITOR"
