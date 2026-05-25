package com.agentplatform.android.tools.privileges

import android.content.Context
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.privilege.PrivilegeManager
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PrivilegesConfigureTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {
    override val name: String = "privileges.configure"

    override val description: String = """
        Configure whitelisted Android high-privilege appops through Shizuku.
        This tool does not expose raw shell. Use target=manage_media to try to
        allow MediaStore management for Agent Platform, target=xiaomi_background
        to apply known HyperOS/MIUI background operation appops, target=battery
        to add Agent Platform to deviceidle whitelist, target=standard_appops
        to allow common notification/media/camera/foreground-service appops, or
        target=accessibility to re-enable Agent Platform's accessibility service
        after reinstall/update when Shizuku is authorized.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "target": {
              "type": "string",
              "enum": ["manage_media", "xiaomi_background", "battery", "standard_appops", "accessibility"],
              "description": "Whitelisted privilege configuration to apply."
            }
          },
          "required": ["target"]
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = true
    override val toolClass: String = "act"
    override val safetyLevel: String = "sensitive_action"
    override val defaultDisplayPolicy: String = "debug_only"

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        when (args.path("target").asText("")) {
            "manage_media" -> configureManageMedia(args)
            "xiaomi_background" -> configureXiaomiBackground(args)
            "battery" -> configureBattery(args)
            "standard_appops" -> configureStandardAppOps(args)
            "accessibility" -> configureAccessibility(args)
            else -> ToolResultEnvelope.error(
                mapper = mapper,
                tool = this@PrivilegesConfigureTool,
                code = "invalid_target",
                message = "target must be manage_media, xiaomi_background, battery, standard_appops, or accessibility",
                request = args
            )
        }
    }

    private fun configureManageMedia(args: JsonNode): JsonNode {
        val before = PrivilegeManager.status(context)
        val command = runCatching { PrivilegeManager.configureManageMedia(context) }
            .getOrElse {
                return ToolResultEnvelope.error(
                    mapper = mapper,
                    tool = this@PrivilegesConfigureTool,
                    code = "shizuku_unavailable",
                    message = it.message ?: "Shizuku is not available or not authorized",
                    retryable = true,
                    hint = "Start Shizuku, authorize Agent Platform, then retry.",
                    request = args
                )
            }
        val after = PrivilegeManager.status(context)
        val ok = command.ok && after.manageMediaGranted
        val result = mapper.createObjectNode().apply {
            put("target", "manage_media")
            put("changed", !before.manageMediaGranted && after.manageMediaGranted)
            put("manage_media_granted", after.manageMediaGranted)
            put("command_exit_code", command.exitCode)
            put("command_timed_out", command.timedOut)
            if (command.stdout.isNotBlank()) put("stdout", command.stdout.take(2000))
            if (command.stderr.isNotBlank()) put("stderr", command.stderr.take(2000))
            set<JsonNode>("summary", mapper.createObjectNode().apply {
                put("ok", ok)
                put("manage_media_granted", after.manageMediaGranted)
                if (!after.manageMediaGranted) {
                    put("hint", "Open Android media management settings and allow Agent Platform if appops did not apply on this ROM.")
                }
            })
        }
        return ToolResultEnvelope.applyStandardFields(
            mapper,
            this@PrivilegesConfigureTool,
            result,
            ok = ok,
            request = args
        )
    }

    private fun configureXiaomiBackground(args: JsonNode): JsonNode {
        val commands = runCatching { PrivilegeManager.configureXiaomiBackgroundOps(context) }
            .getOrElse {
                return ToolResultEnvelope.error(
                    mapper = mapper,
                    tool = this@PrivilegesConfigureTool,
                    code = "shizuku_unavailable",
                    message = it.message ?: "Shizuku is not available or not authorized",
                    retryable = true,
                    hint = "Start Shizuku, authorize Agent Platform, then retry.",
                    request = args
                )
            }
        val results = mapper.createArrayNode()
        var successCount = 0
        for ((command, result) in commands) {
            if (result.ok) successCount++
            results.addObject().apply {
                put("command", command)
                put("exit_code", result.exitCode)
                put("timed_out", result.timedOut)
                put("ok", result.ok)
                if (result.stdout.isNotBlank()) put("stdout", result.stdout.take(1000))
                if (result.stderr.isNotBlank()) put("stderr", result.stderr.take(1000))
            }
        }
        val ok = successCount == commands.size
        val out = mapper.createObjectNode().apply {
            put("target", "xiaomi_background")
            put("success_count", successCount)
            put("total_count", commands.size)
            set<JsonNode>("commands", results)
            set<JsonNode>("summary", mapper.createObjectNode().apply {
                put("ok", ok)
                put("success_count", successCount)
                put("total_count", commands.size)
            })
        }
        return ToolResultEnvelope.applyStandardFields(
            mapper,
            this@PrivilegesConfigureTool,
            out,
            ok = ok,
            request = args
        )
    }

    private fun configureBattery(args: JsonNode): JsonNode {
        val command = runCatching { PrivilegeManager.configureBatteryWhitelist(context) }
            .getOrElse {
                return ToolResultEnvelope.error(
                    mapper = mapper,
                    tool = this@PrivilegesConfigureTool,
                    code = "shizuku_unavailable",
                    message = it.message ?: "Shizuku is not available or not authorized",
                    retryable = true,
                    hint = "Start Shizuku, authorize Agent Platform, then retry.",
                    request = args
                )
            }
        val unrestricted = PrivilegeManager.batteryUnrestricted(context)
        val ok = command.ok && unrestricted
        val out = mapper.createObjectNode().apply {
            put("target", "battery")
            put("battery_unrestricted", unrestricted)
            put("command_exit_code", command.exitCode)
            put("command_timed_out", command.timedOut)
            if (command.stdout.isNotBlank()) put("stdout", command.stdout.take(2000))
            if (command.stderr.isNotBlank()) put("stderr", command.stderr.take(2000))
            set<JsonNode>("summary", mapper.createObjectNode().apply {
                put("ok", ok)
                put("battery_unrestricted", unrestricted)
            })
        }
        return ToolResultEnvelope.applyStandardFields(
            mapper,
            this@PrivilegesConfigureTool,
            out,
            ok = ok,
            request = args
        )
    }

    private fun configureStandardAppOps(args: JsonNode): JsonNode {
        val commands = runCatching { PrivilegeManager.configureStandardAppOps(context) }
            .getOrElse {
                return ToolResultEnvelope.error(
                    mapper = mapper,
                    tool = this@PrivilegesConfigureTool,
                    code = "shizuku_unavailable",
                    message = it.message ?: "Shizuku is not available or not authorized",
                    retryable = true,
                    hint = "Start Shizuku, authorize Agent Platform, then retry.",
                    request = args
                )
            }
        val results = mapper.createArrayNode()
        var successCount = 0
        for ((command, result) in commands) {
            if (result.ok) successCount++
            results.addObject().apply {
                put("command", command)
                put("exit_code", result.exitCode)
                put("timed_out", result.timedOut)
                put("ok", result.ok)
                if (result.stdout.isNotBlank()) put("stdout", result.stdout.take(1000))
                if (result.stderr.isNotBlank()) put("stderr", result.stderr.take(1000))
            }
        }
        val ok = successCount >= commands.size / 2
        val out = mapper.createObjectNode().apply {
            put("target", "standard_appops")
            put("success_count", successCount)
            put("total_count", commands.size)
            set<JsonNode>("commands", results)
            set<JsonNode>("summary", mapper.createObjectNode().apply {
                put("ok", ok)
                put("success_count", successCount)
                put("total_count", commands.size)
            })
        }
        return ToolResultEnvelope.applyStandardFields(
            mapper,
            this@PrivilegesConfigureTool,
            out,
            ok = ok,
            request = args
        )
    }

    private fun configureAccessibility(args: JsonNode): JsonNode {
        val configured = runCatching { PrivilegeManager.configureAccessibilityService(context) }
            .getOrElse {
                return ToolResultEnvelope.error(
                    mapper = mapper,
                    tool = this@PrivilegesConfigureTool,
                    code = "shizuku_unavailable",
                    message = it.message ?: "Shizuku is not available or not authorized",
                    retryable = true,
                    hint = "Start Shizuku, authorize Agent Platform, then retry.",
                    request = args
                )
            }
        val ok = configured.putServices.ok &&
            configured.putEnabled.ok &&
            configured.after.enabledInSettings &&
            configured.after.accessibilityEnabledFlag
        val out = mapper.createObjectNode().apply {
            put("target", "accessibility")
            put("component", configured.after.component)
            put("changed", !configured.before.enabledInSettings && configured.after.enabledInSettings)
            put("enabled_in_settings", configured.after.enabledInSettings)
            put("accessibility_enabled_flag", configured.after.accessibilityEnabledFlag)
            put("put_services_exit_code", configured.putServices.exitCode)
            put("put_enabled_exit_code", configured.putEnabled.exitCode)
            put("put_services_timed_out", configured.putServices.timedOut)
            put("put_enabled_timed_out", configured.putEnabled.timedOut)
            if (configured.putServices.stderr.isNotBlank()) {
                put("put_services_stderr", configured.putServices.stderr.take(1000))
            }
            if (configured.putEnabled.stderr.isNotBlank()) {
                put("put_enabled_stderr", configured.putEnabled.stderr.take(1000))
            }
            set<JsonNode>("enabled_services", mapper.createArrayNode().apply {
                configured.after.enabledServices.forEach { add(it) }
            })
            set<JsonNode>("summary", mapper.createObjectNode().apply {
                put("ok", ok)
                put("enabled_in_settings", configured.after.enabledInSettings)
                put("accessibility_enabled_flag", configured.after.accessibilityEnabledFlag)
                put("component", configured.after.component)
                if (!ok) {
                    put("hint", "Android/ROM blocked secure accessibility settings; open Accessibility settings manually and enable Agent Platform.")
                }
            })
        }
        return ToolResultEnvelope.applyStandardFields(
            mapper,
            this@PrivilegesConfigureTool,
            out,
            ok = ok,
            request = args
        )
    }
}
