package com.agentplatform.android.privilege

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object PrivilegeManager {
    private const val TAG = "PrivilegeManager"
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 4108
    private val XIAOMI_BACKGROUND_OPS = listOf(10004, 10017, 10018, 10020, 10021, 10022, 10045)

    data class Status(
        val shizukuInstalled: Boolean,
        val shizukuRunning: Boolean,
        val shizukuPermissionGranted: Boolean,
        val shizukuUid: Int?,
        val manageMediaDeclared: Boolean,
        val manageMediaGranted: Boolean,
        val canRequestManageMedia: Boolean,
        val shellAvailable: Boolean
    )

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean
    ) {
        val ok: Boolean
            get() = !timedOut && exitCode == 0
    }

    data class AppOpState(
        val op: Int,
        val allowed: Boolean,
        val raw: String
    )

    data class AppOpsStatus(
        val checked: Boolean,
        val successCount: Int,
        val totalCount: Int,
        val ops: List<AppOpState>,
        val error: String? = null
    ) {
        val allAllowed: Boolean
            get() = checked && totalCount > 0 && successCount == totalCount
    }

    data class MediaCommandResult(
        val id: Long,
        val command: String,
        val result: CommandResult
    )

    data class AccessibilitySettingsStatus(
        val checked: Boolean,
        val component: String,
        val enabledInSettings: Boolean,
        val accessibilityEnabledFlag: Boolean,
        val enabledServices: List<String>,
        val rawEnabledServices: String,
        val error: String? = null
    )

    data class AccessibilityConfigureResult(
        val before: AccessibilitySettingsStatus,
        val putServices: CommandResult,
        val putEnabled: CommandResult,
        val after: AccessibilitySettingsStatus
    )

    data class AppLaunchShellResult(
        val strategy: String,
        val command: String,
        val result: CommandResult
    ) {
        val ok: Boolean
            get() = result.ok
    }

    fun status(context: Context): Status {
        val running = isShizukuRunning()
        val granted = running && hasShizukuPermission()
        return Status(
            shizukuInstalled = isPackageInstalled(context, "moe.shizuku.privileged.api"),
            shizukuRunning = running,
            shizukuPermissionGranted = granted,
            shizukuUid = if (running) runCatching { Shizuku.getUid() }.getOrNull() else null,
            manageMediaDeclared = isPermissionDeclared(context, Manifest.permission.MANAGE_MEDIA),
            manageMediaGranted = canManageMedia(context),
            canRequestManageMedia = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            shellAvailable = granted
        )
    }

    fun canManageMedia(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching {
            MediaStore.canManageMedia(context)
        }.getOrDefault(false)

    fun requestShizukuPermission(): Boolean {
        if (!isShizukuRunning()) return false
        if (hasShizukuPermission()) return true
        return runCatching {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            true
        }.getOrDefault(false)
    }

    fun manageMediaSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun configureManageMedia(context: Context): CommandResult {
        requireUsableShizuku()
        val uid = context.applicationInfo.uid
        val packageName = context.packageName
        val commands = buildList {
            add("appops set --uid $packageName MANAGE_MEDIA allow")
            add("appops set $packageName MANAGE_MEDIA allow")
            add("cmd appops set $packageName MANAGE_MEDIA allow")
            add("cmd appops set --uid $uid MANAGE_MEDIA allow")
        }
        var last = CommandResult(1, "", "no command executed", false)
        for (command in commands) {
            last = execShell(command)
            if (last.ok) break
        }
        return last
    }

    fun configureXiaomiBackgroundOps(context: Context): List<Pair<String, CommandResult>> {
        requireUsableShizuku()
        val packageName = context.packageName
        return XIAOMI_BACKGROUND_OPS.flatMap { op ->
            listOf(
                "appops set --user 0 $packageName $op allow",
                "cmd appops set --user 0 $packageName $op allow"
            ).map { command ->
                command to execShell(command)
            }
        }
    }

    fun xiaomiBackgroundOpsStatus(context: Context): AppOpsStatus {
        return runCatching {
            requireUsableShizuku()
            val packageName = context.packageName
            val states = XIAOMI_BACKGROUND_OPS.map { op ->
                val result = execShell("appops get --user 0 $packageName $op", timeoutSeconds = 4)
                val raw = listOf(result.stdout, result.stderr).filter { it.isNotBlank() }.joinToString("\n")
                AppOpState(
                    op = op,
                    allowed = result.ok && raw.contains("allow", ignoreCase = true),
                    raw = raw.take(500)
                )
            }
            AppOpsStatus(
                checked = true,
                successCount = states.count { it.allowed },
                totalCount = states.size,
                ops = states
            )
        }.getOrElse { error ->
            AppOpsStatus(
                checked = false,
                successCount = 0,
                totalCount = XIAOMI_BACKGROUND_OPS.size,
                ops = emptyList(),
                error = error.message ?: "Unable to read appops"
            )
        }
    }

    fun configureBatteryWhitelist(context: Context): CommandResult {
        requireUsableShizuku()
        return execShell("cmd deviceidle whitelist +${context.packageName}")
    }

    fun configureStandardAppOps(context: Context): List<Pair<String, CommandResult>> {
        requireUsableShizuku()
        val packageName = context.packageName
        val ops = listOf(
            "POST_NOTIFICATION",
            "CAMERA",
            "READ_MEDIA_IMAGES",
            "READ_MEDIA_VIDEO",
            "READ_MEDIA_VISUAL_USER_SELECTED",
            "SYSTEM_ALERT_WINDOW",
            "USE_FULL_SCREEN_INTENT",
            "START_FOREGROUND",
            "WAKE_LOCK"
        )
        return ops.map { op ->
            val command = "appops set --user 0 $packageName $op allow"
            command to execShell(command)
        }
    }

    fun accessibilitySettingsStatus(context: Context): AccessibilitySettingsStatus {
        val component = accessibilityServiceComponent(context)
        return runCatching {
            requireUsableShizuku()
            val servicesResult = execShell(
                "settings get secure enabled_accessibility_services",
                timeoutSeconds = 4
            )
            val enabledResult = execShell(
                "settings get secure accessibility_enabled",
                timeoutSeconds = 4
            )
            val rawServices = if (servicesResult.ok) {
                servicesResult.stdout.trim().takeUnless { it == "null" } ?: ""
            } else {
                ""
            }
            val services = parseAccessibilityServices(rawServices)
            AccessibilitySettingsStatus(
                checked = servicesResult.ok && enabledResult.ok,
                component = component,
                enabledInSettings = services.any { accessibilityServiceMatches(it, component) },
                accessibilityEnabledFlag = enabledResult.stdout.trim() == "1",
                enabledServices = services,
                rawEnabledServices = rawServices,
                error = listOf(servicesResult.stderr, enabledResult.stderr)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                    .takeIf { it.isNotBlank() }
            )
        }.getOrElse { error ->
            AccessibilitySettingsStatus(
                checked = false,
                component = component,
                enabledInSettings = false,
                accessibilityEnabledFlag = false,
                enabledServices = emptyList(),
                rawEnabledServices = "",
                error = error.message ?: "Unable to read accessibility settings"
            )
        }
    }

    fun configureAccessibilityService(context: Context): AccessibilityConfigureResult {
        requireUsableShizuku()
        val component = accessibilityServiceComponent(context)
        val before = accessibilitySettingsStatus(context)
        val mergedServices = before.enabledServices
            .filterNot { accessibilityServiceMatches(it, component) }
            .plus(component)
            .distinct()
        val servicesValue = mergedServices.joinToString(":")
        val putServices = execShell(
            "settings put secure enabled_accessibility_services ${shellQuote(servicesValue)}",
            timeoutSeconds = 4
        )
        val putEnabled = execShell(
            "settings put secure accessibility_enabled 1",
            timeoutSeconds = 4
        )
        val after = accessibilitySettingsStatus(context)
        return AccessibilityConfigureResult(
            before = before,
            putServices = putServices,
            putEnabled = putEnabled,
            after = after
        )
    }

    fun launchApp(packageName: String, activityName: String?, timeoutSeconds: Long = 6): List<AppLaunchShellResult> {
        require(packageName.matches(PACKAGE_NAME_REGEX)) { "Invalid package name" }
        if (!activityName.isNullOrBlank()) {
            require(activityName.matches(ACTIVITY_NAME_REGEX)) { "Invalid activity name" }
        }
        requireUsableShizuku()

        val commands = buildList {
            if (!activityName.isNullOrBlank()) {
                val component = "$packageName/$activityName"
                add("am_start_component" to "am start --user 0 -n ${shellQuote(component)}")
            }
            add("monkey_launcher" to "monkey -p ${shellQuote(packageName)} -c android.intent.category.LAUNCHER 1")
        }
        val results = mutableListOf<AppLaunchShellResult>()
        for ((strategy, command) in commands) {
            val result = execShell(command, timeoutSeconds = timeoutSeconds)
            results += AppLaunchShellResult(strategy, command, result)
            if (result.ok) break
        }
        return results
    }

    fun setImagesTrashed(ids: List<Long>, trashed: Boolean): List<MediaCommandResult> {
        requireUsableShizuku()
        val value = if (trashed) 1 else 0
        return ids.map { id ->
            val command = "content update --uri ${imageContentUri(id)} --bind is_trashed:i:$value"
            MediaCommandResult(id, command, execShell(command))
        }
    }

    fun setVideosTrashed(ids: List<Long>, trashed: Boolean): List<MediaCommandResult> {
        requireUsableShizuku()
        val value = if (trashed) 1 else 0
        return ids.map { id ->
            val command = "content update --uri ${videoContentUri(id)} --bind is_trashed:i:$value"
            MediaCommandResult(id, command, execShell(command))
        }
    }

    fun deleteImages(ids: List<Long>): List<MediaCommandResult> {
        requireUsableShizuku()
        return ids.map { id ->
            val command = "content delete --uri ${imageContentUri(id)}"
            MediaCommandResult(id, command, execShell(command))
        }
    }

    fun deleteVideos(ids: List<Long>): List<MediaCommandResult> {
        requireUsableShizuku()
        return ids.map { id ->
            val command = "content delete --uri ${videoContentUri(id)}"
            MediaCommandResult(id, command, execShell(command))
        }
    }

    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun notificationsEnabled(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
    }

    fun overlaysEnabled(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun batteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun notificationChannelEnabled(context: Context, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = manager.getNotificationChannel(channelId) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun execShell(command: String, timeoutSeconds: Long = 12): CommandResult {
        requireUsableShizuku()
        val process = shizukuNewProcess(arrayOf("sh", "-c", command))
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outDone = CountDownLatch(1)
        val errDone = CountDownLatch(1)
        val outThread = Thread {
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { stdout.appendLine(it) }
            }
            outDone.countDown()
        }
        val errThread = Thread {
            BufferedReader(InputStreamReader(process.errorStream)).useLines { lines ->
                lines.forEach { stderr.appendLine(it) }
            }
            errDone.countDown()
        }
        outThread.start()
        errThread.start()
        val finished = if (process is rikka.shizuku.ShizukuRemoteProcess) {
            process.waitForTimeout(timeoutSeconds, TimeUnit.SECONDS)
        } else {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        }
        if (!finished) {
            process.destroy()
        }
        if (!outDone.await(500, TimeUnit.MILLISECONDS)) {
            Log.d(TAG, "Timed out waiting for Shizuku stdout reader")
        }
        if (!errDone.await(500, TimeUnit.MILLISECONDS)) {
            Log.d(TAG, "Timed out waiting for Shizuku stderr reader")
        }
        return CommandResult(
            exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(0) else -1,
            stdout = stdout.toString().trim(),
            stderr = stderr.toString().trim(),
            timedOut = !finished
        )
    }

    private fun isShizukuRunning(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun hasShizukuPermission(): Boolean {
        return runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    private fun requireUsableShizuku() {
        require(isShizukuRunning()) { "Shizuku is not running" }
        require(hasShizukuPermission()) { "Agent Platform is not authorized in Shizuku" }
    }

    private fun imageContentUri(id: Long): String =
        "content://media/external/images/media/$id"

    private fun videoContentUri(id: Long): String =
        "content://media/external/video/media/$id"

    private fun accessibilityServiceComponent(context: Context): String =
        "${context.packageName}/${context.packageName}.ui.accessibility.UiAccessibilityService"

    private fun parseAccessibilityServices(raw: String): List<String> =
        raw.split(':')
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "null" }

    private fun accessibilityServiceMatches(value: String, component: String): Boolean =
        value == component || value.endsWith("/.ui.accessibility.UiAccessibilityService")

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun shizukuNewProcess(commands: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, commands, null, null) as Process
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    private fun isPermissionDeclared(context: Context, permission: String): Boolean =
        runCatching {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS
            )
            info.requestedPermissions?.contains(permission) == true
        }.getOrDefault(false)

    private val PACKAGE_NAME_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val ACTIVITY_NAME_REGEX = Regex("(\\.[A-Za-z0-9_.$]+)|([A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_.$]+)+)")
}
