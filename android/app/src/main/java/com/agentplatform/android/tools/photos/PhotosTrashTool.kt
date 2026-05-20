package com.agentplatform.android.tools.photos

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.privilege.PrivilegeManager
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotosTrashTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {
    override val name: String = "photos.trash"

    override val description: String = """
        Move existing gallery photos to Android's trash/recycle bin. Pass `id`
        for one photo, `ids` for a batch, or `selection_id` from
        media.selection.create for a previously reviewed set. Use
        photos.restore to restore trashed photos. Requires Android 11+.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "id": {
              "type": "string",
              "description": "Single photo id."
            },
            "ids": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Photo ids. Maximum 100."
            },
            "selection_id": {
              "type": "string",
              "description": "Reusable photo selection id from media.selection.create."
            }
          },
          "anyOf": [
            { "required": ["id"] },
            { "required": ["ids"] },
            { "required": ["selection_id"] }
          ]
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw UnsupportedOperationException("trash requires Android 11 or newer")
        }
        val ids = PhotoMutationHelpers.parseIds(context, mapper, args)
        val shellAvailable = PrivilegeManager.status(context).shellAvailable
        var usedPrivilegedShell = false
        var usedManageMedia = false
        var commandResults: List<PrivilegeManager.MediaCommandResult> = emptyList()
        if (shellAvailable) {
            commandResults = PrivilegeManager.setImagesTrashed(ids, trashed = true)
            if (commandResults.all { it.result.ok }) {
                usedPrivilegedShell = true
            }
        }
        if (!usedPrivilegedShell && PrivilegeManager.canManageMedia(context)) {
            usedManageMedia = true
            try {
                for (id in ids) {
                    context.contentResolver.update(
                        PhotoMutationHelpers.photoUri(id),
                        android.content.ContentValues().apply {
                            put(MediaStore.Images.Media.IS_TRASHED, 1)
                        },
                        null,
                        null
                    )
                }
            } catch (e: SecurityException) {
                val result = ToolResultEnvelope.error(
                    mapper = mapper,
                    tool = this@PhotosTrashTool,
                    code = "media_mutation_denied",
                    message = e.message ?: "Android denied MediaStore trash without foreground confirmation.",
                    retryable = true,
                    hint = "Verify Shizuku is running and authorized, then run privileges.configure target=manage_media. The tool suppressed the Android confirmation dialog.",
                    request = args
                )
                result.set<JsonNode>("ids", PhotoMutationHelpers.idsArray(mapper, ids))
                result.put("affected_count", 0)
                result.put("used_privileged_shell", false)
                result.put("used_manage_media", true)
                result.put("system_confirmation_required", true)
                result.put("system_confirmation_suppressed", true)
                if (commandResults.isNotEmpty()) {
                    result.set<JsonNode>("privileged_shell_results", PhotoMutationHelpers.mediaCommandResultsArray(mapper, commandResults))
                }
                result.set<JsonNode>("summary", mapper.createObjectNode().apply {
                    put("affected_count", 0)
                    put("trashed", false)
                    put("requires_privileged_media_access", true)
                    put("system_confirmation_suppressed", true)
                })
                return@withContext result
            }
        } else if (!usedPrivilegedShell) {
            val result = ToolResultEnvelope.error(
                mapper = mapper,
                tool = this@PhotosTrashTool,
                code = "privileged_media_access_required",
                message = "Moving photos to trash would require Android's foreground MediaStore confirmation on this device. Configure Shizuku/MANAGE_MEDIA first so the tool can run in the background without a system dialog.",
                retryable = true,
                hint = "Run privileges.status, then configure Shizuku and privileges.configure target=manage_media. The tool will not open the system confirmation dialog by default.",
                request = args
            )
            result.set<JsonNode>("ids", PhotoMutationHelpers.idsArray(mapper, ids))
            result.put("affected_count", 0)
            result.put("used_privileged_shell", false)
            result.put("used_manage_media", false)
            result.put("system_confirmation_required", true)
            result.put("system_confirmation_suppressed", true)
            if (commandResults.isNotEmpty()) {
                result.set<JsonNode>("privileged_shell_results", PhotoMutationHelpers.mediaCommandResultsArray(mapper, commandResults))
            }
            result.set<JsonNode>("summary", mapper.createObjectNode().apply {
                put("affected_count", 0)
                put("trashed", false)
                put("requires_privileged_media_access", true)
                put("system_confirmation_suppressed", true)
            })
            return@withContext result
        }

        val result = mapper.createObjectNode().apply {
            set<JsonNode>("ids", PhotoMutationHelpers.idsArray(mapper, ids))
            put("trashed", true)
            put("affected_count", ids.size)
            put("used_privileged_shell", usedPrivilegedShell)
            put("used_manage_media", usedManageMedia)
            put("system_confirmation_required", !usedPrivilegedShell && !usedManageMedia)
            put("system_confirmation_suppressed", false)
            if (commandResults.isNotEmpty()) {
                set<JsonNode>("privileged_shell_results", PhotoMutationHelpers.mediaCommandResultsArray(mapper, commandResults))
            }
            set<JsonNode>("summary", mapper.createObjectNode().apply {
                put("affected_count", ids.size)
                put("trashed", true)
                put("used_privileged_shell", usedPrivilegedShell)
                put("used_manage_media", usedManageMedia)
            })
        }
        ToolResultEnvelope.applyStandardFields(mapper, this@PhotosTrashTool, result, ok = true, request = args)
    }
}
