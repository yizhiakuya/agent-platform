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
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidDiagnosticsTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        val includeRecommendations = args.path("include_recommendations").asBoolean(true)
        val state = collectState()
        val recommendations = buildRecommendations(state, includeRecommendations)
        val result = buildResult(state, recommendations, includeRecommendations)
        ToolResultEnvelope.applyStandardFields(mapper, this@AndroidDiagnosticsTool, result, ok = true, request = args)
    }

    private fun collectState(): DiagnosticsState {
        val prefs = AppPrefs(context)
        val privilege = PrivilegeManager.status(context)
        val accessibilitySettings = PrivilegeManager.accessibilitySettingsStatus(context)
        val xiaomiLike = isXiaomiLike()
        return DiagnosticsState(
            prefs = prefs,
            privilege = privilege,
            accessibilitySettings = accessibilitySettings,
            xiaomiBackgroundOps = xiaomiBackgroundOps(xiaomiLike, privilege),
            permissions = collectPermissions(),
            connected = looksConnected(AgentForegroundService.status.value),
            xiaomiLike = xiaomiLike
        )
    }

    private fun collectPermissions() = PermissionState(
        notifications = PrivilegeManager.notificationsEnabled(context),
        mediaImages = canReadImages(),
        mediaVideos = canReadVideosOrPartial(),
        overlay = PrivilegeManager.overlaysEnabled(context),
        battery = PrivilegeManager.batteryUnrestricted(context),
        accessibility = UiAccessibilityService.isAvailable(),
        capture = UiCaptureManager.isReady()
    )

    private fun canReadImages(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            PrivilegeManager.hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            PrivilegeManager.hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun canReadVideosOrPartial(): Boolean {
        return Build.VERSION.SDK_INT < 33
            || PrivilegeManager.hasPermission(context, Manifest.permission.READ_MEDIA_VIDEO)
            || (Build.VERSION.SDK_INT >= 34
                && PrivilegeManager.hasPermission(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))
    }

    private fun isXiaomiLike(): Boolean {
        return Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
            || Build.BRAND.equals("Xiaomi", ignoreCase = true)
            || Build.DISPLAY.contains("HyperOS", ignoreCase = true)
    }

    private fun xiaomiBackgroundOps(
        xiaomiLike: Boolean,
        privilege: PrivilegeManager.Status
    ): PrivilegeManager.AppOpsStatus? {
        return if (xiaomiLike && privilege.shellAvailable) {
            PrivilegeManager.xiaomiBackgroundOpsStatus(context)
        } else {
            null
        }
    }

    private fun looksConnected(status: String): Boolean {
        val connectedValues = setOf("宸茶繛鎺?", "已连接")
        val connectedPrefixes = listOf("宸叉敹鍒?", "已收到", "recv")
        return status in connectedValues
            || connectedPrefixes.any { status.startsWith(it) }
            || status.contains("Connected", ignoreCase = true)
    }

    private fun buildRecommendations(state: DiagnosticsState, include: Boolean): ArrayNode {
        val recommendations = mapper.createArrayNode()
        if (!include) return recommendations
        addBindingRecommendations(recommendations, state)
        addPermissionRecommendations(recommendations, state)
        addPrivilegeRecommendations(recommendations, state)
        return recommendations
    }

    private fun addBindingRecommendations(recommendations: ArrayNode, state: DiagnosticsState) {
        if (!state.prefs.isBound()) {
            recommend(recommendations, "bind_device", "Agent Platform is not bound to a server yet.", "ordinary")
        }
    }

    private fun addPermissionRecommendations(recommendations: ArrayNode, state: DiagnosticsState) {
        val permissions = state.permissions
        val privilege = state.privilege
        if (!permissions.notifications) {
            recommend(
                recommendations,
                "open_app_notification_settings",
                "Notifications are not granted; foreground service visibility and confirmations may fail.",
                "ordinary"
            )
        }
        if (!permissions.mediaImages) {
            recommend(
                recommendations,
                "open_app_media_permissions",
                "Image media permission is missing; photo search/list tools may return empty results.",
                "ordinary"
            )
        }
        if (!permissions.accessibility) {
            if (privilege.shellAvailable) {
                recommend(
                    recommendations,
                    "privileges.configure:accessibility",
                    "Accessibility is not bound; Shizuku can re-enable Agent Platform accessibility after reinstall/update.",
                    "privileged"
                )
            } else {
                recommend(
                    recommendations,
                    "open_accessibility_settings",
                    "Accessibility is not bound; UI tools cannot inspect or operate third-party app screens.",
                    "ordinary"
                )
            }
        }
        if (!permissions.overlay) {
            recommend(
                recommendations,
                "open_overlay_or_background_popup_settings",
                "Overlay/background popup permission is missing; background app launch may be blocked.",
                "ordinary"
            )
        }
        if (!permissions.battery) {
            recommend(
                recommendations,
                "privileges.configure:battery",
                "Battery optimization is not ignored; the foreground service may be killed.",
                "privileged"
            )
        }
    }

    private fun addPrivilegeRecommendations(recommendations: ArrayNode, state: DiagnosticsState) {
        val privilege = state.privilege
        if (!privilege.manageMediaGranted && privilege.shellAvailable) {
            recommend(
                recommendations,
                "privileges.configure:manage_media",
                "Shizuku is ready; granting MANAGE_MEDIA is optional hardening for ROMs where shell media commands are restricted.",
                "privileged"
            )
        } else if (!privilege.manageMediaGranted && privilege.canRequestManageMedia) {
            recommend(
                recommendations,
                "open_manage_media_settings",
                "Media management is not granted; photo trash/delete will be blocked instead of opening Android system confirmation.",
                "ordinary"
            )
        }
        if (state.xiaomiLike && privilege.shellAvailable && state.xiaomiBackgroundOps?.allAllowed != true) {
            recommend(
                recommendations,
                "privileges.configure:xiaomi_background",
                "Xiaomi/HyperOS devices often need private appops for background launch and keep-alive.",
                "privileged"
            )
        }
    }

    private fun recommend(recommendations: ArrayNode, action: String, reason: String, mode: String) {
        recommendations.addObject().apply {
            put("action", action)
            put("reason", reason)
            put("mode", mode)
        }
    }

    private fun buildResult(
        state: DiagnosticsState,
        recommendations: ArrayNode,
        includeRecommendations: Boolean
    ): ObjectNode = mapper.createObjectNode().apply {
        set<JsonNode>("device", deviceNode(state))
        set<JsonNode>("binding", bindingNode(state))
        set<JsonNode>("connection", connectionNode(state))
        set<JsonNode>("permissions", permissionsNode(state))
        set<JsonNode>("channels", channelsNode())
        set<JsonNode>("automation", automationNode(state))
        set<JsonNode>("privileges", privilegesNode(state))
        state.xiaomiBackgroundOps?.let { set<JsonNode>("xiaomi_background", xiaomiBackgroundNode(it)) }
        set<JsonNode>("media", mediaNode(state))
        if (includeRecommendations) {
            set<JsonNode>("recommendations", recommendations)
        }
        set<JsonNode>("summary", summaryNode(state, recommendations))
    }

    private fun deviceNode(state: DiagnosticsState): ObjectNode = mapper.createObjectNode().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("display", Build.DISPLAY)
        put("android_release", Build.VERSION.RELEASE)
        put("android_sdk", Build.VERSION.SDK_INT)
        put("xiaomi_like", state.xiaomiLike)
    }

    private fun bindingNode(state: DiagnosticsState): ObjectNode = mapper.createObjectNode().apply {
        put("bound", state.prefs.isBound())
        put("server_url_present", !state.prefs.serverUrl.isNullOrBlank())
        put("device_id_present", !state.prefs.deviceId.isNullOrBlank())
        put("token_present", !state.prefs.token.isNullOrBlank())
    }

    private fun connectionNode(state: DiagnosticsState): ObjectNode = mapper.createObjectNode().apply {
        put("status", AgentForegroundService.status.value)
        put("looks_connected", state.connected)
    }

    private fun permissionsNode(state: DiagnosticsState): ObjectNode = mapper.createObjectNode().apply {
        val permissions = state.permissions
        put("post_notifications", permissions.notifications)
        put("read_media_images", permissions.mediaImages)
        put("read_media_video_or_partial", permissions.mediaVideos)
        put("camera", PrivilegeManager.hasPermission(context, Manifest.permission.CAMERA))
        put("overlay", permissions.overlay)
        put("battery_unrestricted", permissions.battery)
        put("manage_media", state.privilege.manageMediaGranted)
    }

    private fun channelsNode(): ObjectNode = mapper.createObjectNode().apply {
        put("foreground_channel_enabled", PrivilegeManager.notificationChannelEnabled(context, AgentApplication.FOREGROUND_CHANNEL_ID))
        put("confirmation_channel_enabled", PrivilegeManager.notificationChannelEnabled(context, AgentApplication.CONFIRM_CHANNEL_ID))
    }

    private fun automationNode(state: DiagnosticsState): ObjectNode = mapper.createObjectNode().apply {
        val accessibilitySettings = state.accessibilitySettings
        val permissions = state.permissions
        put("accessibility_bound", permissions.accessibility)
        put("accessibility_enabled_in_settings", accessibilitySettings.enabledInSettings)
        put("accessibility_settings_checked", accessibilitySettings.checked)
        put("screen_capture_ready", permissions.capture)
        put("ui_tools_ready", permissions.accessibility)
        set<JsonNode>("enabled_accessibility_services", mapper.createArrayNode().apply {
            accessibilitySettings.enabledServices.forEach { add(it) }
        })
    }

    private fun privilegesNode(state: DiagnosticsState): ObjectNode = mapper.createObjectNode().apply {
        val privilege = state.privilege
        put("shizuku_installed", privilege.shizukuInstalled)
        put("shizuku_running", privilege.shizukuRunning)
        put("shizuku_permission_granted", privilege.shizukuPermissionGranted)
        if (privilege.shizukuUid != null) put("shizuku_uid", privilege.shizukuUid)
        put("shell_available", privilege.shellAvailable)
        put("manage_media_declared", privilege.manageMediaDeclared)
        put("manage_media_granted", privilege.manageMediaGranted)
        put("can_request_manage_media", privilege.canRequestManageMedia)
    }

    private fun xiaomiBackgroundNode(status: PrivilegeManager.AppOpsStatus): ObjectNode {
        val ops = mapper.createArrayNode()
        status.ops.forEach { state ->
            ops.addObject().apply {
                put("op", state.op)
                put("allowed", state.allowed)
                if (state.raw.isNotBlank()) put("raw", state.raw)
            }
        }
        return mapper.createObjectNode().apply {
            put("checked", status.checked)
            put("configured", status.allAllowed)
            put("success_count", status.successCount)
            put("total_count", status.totalCount)
            if (!status.error.isNullOrBlank()) put("error", status.error)
            set<JsonNode>("ops", ops)
        }
    }

    private fun mediaNode(state: DiagnosticsState): ObjectNode = mapper.createObjectNode().apply {
        put("can_read_images", state.permissions.mediaImages)
        put("can_manage_media", state.privilege.manageMediaGranted)
        put("can_mutate_without_system_confirmation", state.mediaWithoutSystemConfirmation)
        put("trash_delete_needs_system_confirmation", !state.mediaWithoutSystemConfirmation)
    }

    private fun summaryNode(state: DiagnosticsState, recommendations: ArrayNode): ObjectNode =
        mapper.createObjectNode().apply {
            put("ordinary_mode_available", state.prefs.isBound())
            put("privileged_mode_available", state.privilege.shellAvailable || state.privilege.manageMediaGranted)
            put("media_without_system_confirmation", state.mediaWithoutSystemConfirmation)
            put("ui_automation_available", state.permissions.accessibility)
            put("accessibility_enabled_in_settings", state.accessibilitySettings.enabledInSettings)
            put("xiaomi_background_configured", state.xiaomiBackgroundOps?.allAllowed ?: false)
            put("recommendation_count", recommendations.size())
        }

    private data class PermissionState(
        val notifications: Boolean,
        val mediaImages: Boolean,
        val mediaVideos: Boolean,
        val overlay: Boolean,
        val battery: Boolean,
        val accessibility: Boolean,
        val capture: Boolean
    )

    private data class DiagnosticsState(
        val prefs: AppPrefs,
        val privilege: PrivilegeManager.Status,
        val accessibilitySettings: PrivilegeManager.AccessibilitySettingsStatus,
        val xiaomiBackgroundOps: PrivilegeManager.AppOpsStatus?,
        val permissions: PermissionState,
        val connected: Boolean,
        val xiaomiLike: Boolean
    ) {
        val mediaWithoutSystemConfirmation: Boolean
            get() = privilege.manageMediaGranted || privilege.shellAvailable
    }
}
