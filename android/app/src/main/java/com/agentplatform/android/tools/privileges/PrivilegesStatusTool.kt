package com.agentplatform.android.tools.privileges

import android.content.Context
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.privilege.PrivilegeManager
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PrivilegesStatusTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {
    override val name: String = "privileges.status"

    override val description: String = """
        Report optional high-privilege Android channels for this device, including
        Shizuku and MANAGE_MEDIA. Use this before privilege configuration tools
        or when a media/system operation says it needs elevated permissions.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {}
        }
        """.trimIndent()
    )

    override val toolClass: String = "verify"
    override val defaultDisplayPolicy: String = "debug_only"
    override val resultType: String = "state"

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        val status = PrivilegeManager.status(context)
        val mediaWithoutSystemConfirmation = status.manageMediaGranted || status.shellAvailable
        val accessibilitySettings = PrivilegeManager.accessibilitySettingsStatus(context)
        val result = mapper.createObjectNode().apply {
            put("shizuku_installed", status.shizukuInstalled)
            put("shizuku_running", status.shizukuRunning)
            put("shizuku_permission_granted", status.shizukuPermissionGranted)
            if (status.shizukuUid != null) put("shizuku_uid", status.shizukuUid)
            put("manage_media_declared", status.manageMediaDeclared)
            put("manage_media_granted", status.manageMediaGranted)
            put("can_request_manage_media", status.canRequestManageMedia)
            put("shell_available", status.shellAvailable)
            set<JsonNode>("accessibility_settings", mapper.createObjectNode().apply {
                put("checked", accessibilitySettings.checked)
                put("component", accessibilitySettings.component)
                put("enabled_in_settings", accessibilitySettings.enabledInSettings)
                put("accessibility_enabled_flag", accessibilitySettings.accessibilityEnabledFlag)
                if (!accessibilitySettings.error.isNullOrBlank()) put("error", accessibilitySettings.error)
                set<JsonNode>("enabled_services", mapper.createArrayNode().apply {
                    accessibilitySettings.enabledServices.forEach { add(it) }
                })
            })
            set<JsonNode>("capabilities", mapper.createObjectNode().apply {
                put("media_without_system_confirmation", mediaWithoutSystemConfirmation)
                put("shizuku_appops_configuration", status.shellAvailable)
                put("shizuku_accessibility_configuration", status.shellAvailable)
            })
            set<JsonNode>("summary", mapper.createObjectNode().apply {
                put("ready", status.manageMediaGranted || status.shellAvailable)
                put("media_without_system_confirmation", mediaWithoutSystemConfirmation)
                put("shizuku_ready", status.shellAvailable)
                put("accessibility_enabled_in_settings", accessibilitySettings.enabledInSettings)
            })
        }
        ToolResultEnvelope.applyStandardFields(mapper, this@PrivilegesStatusTool, result, ok = true, request = args)
    }
}
