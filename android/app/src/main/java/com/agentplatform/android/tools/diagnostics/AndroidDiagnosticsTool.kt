package com.agentplatform.android.tools.diagnostics

import android.Manifest
import android.content.Context
import android.os.Build
import com.agentplatform.android.AgentApplication
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.data.AppPrefs
import com.agentplatform.android.privilege.PrivilegeManager
import com.agentplatform.android.service.AgentForegroundService
import com.agentplatform.android.ui.accessibility.UiAccessibilityService
import com.agentplatform.android.ui.capture.UiCaptureManager
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidDiagnosticsTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {
    override val name: String = "android.diagnostics"

    override val description: String = """
        Return a read-only structured diagnostics report for the Android agent
        runtime. Use this before retrying failed phone tools, when the device
        seems offline, or when permissions/background/app automation behave
        unexpectedly. This tool never changes device settings.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "include_recommendations": {
              "type": "boolean",
              "default": true,
              "description": "Include next-step recommendations for the agent."
            }
          }
        }
        """.trimIndent()
    )

    override val toolClass: String = "verify"
    override val defaultDisplayPolicy: String = "debug_only"
    override val resultType: String = "state"

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        val includeRecommendations = args.path("include_recommendations").asBoolean(true)
        val prefs = AppPrefs(context)
        val privilege = PrivilegeManager.status(context)
        val accessibilitySettings = PrivilegeManager.accessibilitySettingsStatus(context)
        val mediaWithoutSystemConfirmation = privilege.manageMediaGranted || privilege.shellAvailable
        val xiaomiLike = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
            || Build.BRAND.equals("Xiaomi", ignoreCase = true)
            || Build.DISPLAY.contains("HyperOS", ignoreCase = true)
        val xiaomiBackgroundOps = if (xiaomiLike && privilege.shellAvailable) {
            PrivilegeManager.xiaomiBackgroundOpsStatus(context)
        } else {
            null
        }
        val recommendations = mapper.createArrayNode()

        fun recommend(action: String, reason: String, mode: String = "ordinary_or_privileged") {
            if (!includeRecommendations) return
            recommendations.addObject().apply {
                put("action", action)
                put("reason", reason)
                put("mode", mode)
            }
        }

        val notifications = PrivilegeManager.notificationsEnabled(context)
        val mediaImages = if (Build.VERSION.SDK_INT >= 33) {
            PrivilegeManager.hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            PrivilegeManager.hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val mediaVideos = Build.VERSION.SDK_INT < 33
            || PrivilegeManager.hasPermission(context, Manifest.permission.READ_MEDIA_VIDEO)
            || (Build.VERSION.SDK_INT >= 34
                && PrivilegeManager.hasPermission(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))
        val overlay = PrivilegeManager.overlaysEnabled(context)
        val battery = PrivilegeManager.batteryUnrestricted(context)
        val accessibility = UiAccessibilityService.isAvailable()
        val capture = UiCaptureManager.isReady()
        val connected = AgentForegroundService.status.value.startsWith("宸叉敹鍒?")
            || AgentForegroundService.status.value == "宸茶繛鎺?"
            || AgentForegroundService.status.value == "已连接"
            || AgentForegroundService.status.value.startsWith("已收到")
            || AgentForegroundService.status.value.startsWith("recv")
            || AgentForegroundService.status.value.contains("Connected", ignoreCase = true)

        if (!prefs.isBound()) {
            recommend("bind_device", "Agent Platform is not bound to a server yet.", "ordinary")
        }
        if (!notifications) {
            recommend("open_app_notification_settings", "Notifications are not granted; foreground service visibility and confirmations may fail.", "ordinary")
        }
        if (!mediaImages) {
            recommend("open_app_media_permissions", "Image media permission is missing; photo search/list tools may return empty results.", "ordinary")
        }
        if (!accessibility) {
            if (privilege.shellAvailable) {
                recommend("privileges.configure:accessibility", "Accessibility is not bound; Shizuku can re-enable Agent Platform accessibility after reinstall/update.", "privileged")
            } else {
                recommend("open_accessibility_settings", "Accessibility is not bound; UI tools cannot inspect or operate third-party app screens.", "ordinary")
            }
        }
        if (!overlay) {
            recommend("open_overlay_or_background_popup_settings", "Overlay/background popup permission is missing; background app launch may be blocked.", "ordinary")
        }
        if (!battery) {
            recommend("privileges.configure:battery", "Battery optimization is not ignored; the foreground service may be killed.", "privileged")
        }
        if (!privilege.manageMediaGranted && privilege.shellAvailable) {
            recommend("privileges.configure:manage_media", "Shizuku is ready; granting MANAGE_MEDIA is optional hardening for ROMs where shell media commands are restricted.", "privileged")
        } else if (!privilege.manageMediaGranted && privilege.canRequestManageMedia) {
            recommend("open_manage_media_settings", "Media management is not granted; photo trash/delete will be blocked instead of opening Android system confirmation.", "ordinary")
        }
        if (xiaomiLike && privilege.shellAvailable && xiaomiBackgroundOps?.allAllowed != true) {
            recommend("privileges.configure:xiaomi_background", "Xiaomi/HyperOS devices often need private appops for background launch and keep-alive.", "privileged")
        }

        val result = mapper.createObjectNode().apply {
            set<JsonNode>("device", mapper.createObjectNode().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("brand", Build.BRAND)
                put("model", Build.MODEL)
                put("display", Build.DISPLAY)
                put("android_release", Build.VERSION.RELEASE)
                put("android_sdk", Build.VERSION.SDK_INT)
                put("xiaomi_like", xiaomiLike)
            })
            set<JsonNode>("binding", mapper.createObjectNode().apply {
                put("bound", prefs.isBound())
                put("server_url_present", !prefs.serverUrl.isNullOrBlank())
                put("device_id_present", !prefs.deviceId.isNullOrBlank())
                put("token_present", !prefs.token.isNullOrBlank())
            })
            set<JsonNode>("connection", mapper.createObjectNode().apply {
                put("status", AgentForegroundService.status.value)
                put("looks_connected", connected)
            })
            set<JsonNode>("permissions", mapper.createObjectNode().apply {
                put("post_notifications", notifications)
                put("read_media_images", mediaImages)
                put("read_media_video_or_partial", mediaVideos)
                put("camera", PrivilegeManager.hasPermission(context, Manifest.permission.CAMERA))
                put("overlay", overlay)
                put("battery_unrestricted", battery)
                put("manage_media", privilege.manageMediaGranted)
            })
            set<JsonNode>("channels", mapper.createObjectNode().apply {
                put("foreground_channel_enabled", PrivilegeManager.notificationChannelEnabled(context, AgentApplication.FOREGROUND_CHANNEL_ID))
                put("confirmation_channel_enabled", PrivilegeManager.notificationChannelEnabled(context, AgentApplication.CONFIRM_CHANNEL_ID))
            })
            set<JsonNode>("automation", mapper.createObjectNode().apply {
                put("accessibility_bound", accessibility)
                put("accessibility_enabled_in_settings", accessibilitySettings.enabledInSettings)
                put("accessibility_settings_checked", accessibilitySettings.checked)
                put("screen_capture_ready", capture)
                put("ui_tools_ready", accessibility)
                set<JsonNode>("enabled_accessibility_services", mapper.createArrayNode().apply {
                    accessibilitySettings.enabledServices.forEach { add(it) }
                })
            })
            set<JsonNode>("privileges", mapper.createObjectNode().apply {
                put("shizuku_installed", privilege.shizukuInstalled)
                put("shizuku_running", privilege.shizukuRunning)
                put("shizuku_permission_granted", privilege.shizukuPermissionGranted)
                if (privilege.shizukuUid != null) put("shizuku_uid", privilege.shizukuUid)
                put("shell_available", privilege.shellAvailable)
                put("manage_media_declared", privilege.manageMediaDeclared)
                put("manage_media_granted", privilege.manageMediaGranted)
                put("can_request_manage_media", privilege.canRequestManageMedia)
            })
            if (xiaomiBackgroundOps != null) {
                set<JsonNode>("xiaomi_background", mapper.createObjectNode().apply {
                    put("checked", xiaomiBackgroundOps.checked)
                    put("configured", xiaomiBackgroundOps.allAllowed)
                    put("success_count", xiaomiBackgroundOps.successCount)
                    put("total_count", xiaomiBackgroundOps.totalCount)
                    if (!xiaomiBackgroundOps.error.isNullOrBlank()) {
                        put("error", xiaomiBackgroundOps.error)
                    }
                    val ops = mapper.createArrayNode()
                    xiaomiBackgroundOps.ops.forEach { state ->
                        ops.addObject().apply {
                            put("op", state.op)
                            put("allowed", state.allowed)
                            if (state.raw.isNotBlank()) put("raw", state.raw)
                        }
                    }
                    set<JsonNode>("ops", ops)
                })
            }
            set<JsonNode>("media", mapper.createObjectNode().apply {
                put("can_read_images", mediaImages)
                put("can_manage_media", privilege.manageMediaGranted)
                put("can_mutate_without_system_confirmation", mediaWithoutSystemConfirmation)
                put("trash_delete_needs_system_confirmation", !mediaWithoutSystemConfirmation)
            })
            if (includeRecommendations) {
                set<JsonNode>("recommendations", recommendations)
            }
            set<JsonNode>("summary", mapper.createObjectNode().apply {
                put("ordinary_mode_available", prefs.isBound())
                put("privileged_mode_available", privilege.shellAvailable || privilege.manageMediaGranted)
                put("media_without_system_confirmation", mediaWithoutSystemConfirmation)
                put("ui_automation_available", accessibility)
                put("accessibility_enabled_in_settings", accessibilitySettings.enabledInSettings)
                put("xiaomi_background_configured", xiaomiBackgroundOps?.allAllowed ?: false)
                put("recommendation_count", recommendations.size())
            })
        }
        ToolResultEnvelope.applyStandardFields(mapper, this@AndroidDiagnosticsTool, result, ok = true, request = args)
    }
}
