package com.agentplatform.android.tools.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.media.MediaStoreRequestBridge
import com.agentplatform.android.privilege.PrivilegeManager
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

internal enum class MediaGalleryMutationAction(
    val targetTrashed: Boolean,
    val unsupportedMessage: String,
    val deniedMessage: String,
    val batchVerb: String,
    val includeRestoredField: Boolean
) {
    TRASH(
        targetTrashed = true,
        unsupportedMessage = "media gallery trash requires Android 11 or newer",
        deniedMessage = "Android did not confirm that the selected media was moved to trash.",
        batchVerb = "trashed",
        includeRestoredField = false
    ),
    RESTORE(
        targetTrashed = false,
        unsupportedMessage = "media gallery restore requires Android 11 or newer",
        deniedMessage = "Android did not confirm that the selected media was restored from trash.",
        batchVerb = "restored",
        includeRestoredField = true
    )
}

internal fun mediaGalleryMutationSchema(mapper: ObjectMapper): JsonNode = mapper.readTree(
    """
    {
      "type": "object",
      "properties": {
        "items": {
          "type": "array",
          "minItems": 1,
          "maxItems": 100,
          "items": {
            "type": "object",
            "properties": {
              "media_type": {
                "type": "string",
                "enum": ["photo", "video"],
                "description": "Media type from the gallery item."
              },
              "id": {
                "type": "string",
                "description": "MediaStore id from the gallery item."
              },
              "media_ref": {
                "type": "string",
                "description": "Optional media://photo/{id} or media://video/{id} reference."
              }
            },
            "required": ["id"]
          }
        },
        "id": {
          "type": "string",
          "description": "Single media id for convenience."
        },
        "media_type": {
          "type": "string",
          "enum": ["photo", "video"],
          "default": "photo",
          "description": "Type used with top-level id."
        }
      },
      "anyOf": [
        { "required": ["items"] },
        { "required": ["id"] }
      ]
    }
    """.trimIndent()
)

internal class MediaGalleryMutationExecutor(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val tool: Tool,
    private val action: MediaGalleryMutationAction
) {
    suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw UnsupportedOperationException(action.unsupportedMessage)
        }

        val items = parseItems(args)
        val photos = items.filter { it.mediaType == "photo" }.map { it.id }
        val videos = items.filter { it.mediaType == "video" }.map { it.id }
        val missing = items.filter { queryTrashState(it) == null }
        if (missing.isNotEmpty()) {
            return@withContext mediaAccessError(
                args = args,
                items = items,
                commandResults = emptyList(),
                code = "media_not_found",
                message = "Some selected media items no longer exist in MediaStore.",
                usedManageMedia = false,
                usedSystemConfirmation = false
            ).apply {
                set<ArrayNode>("missing_items", itemsArray(missing))
            }
        }

        val shellAvailable = PrivilegeManager.status(context).shellAvailable
        var usedPrivilegedShell = false
        var usedManageMedia = false
        var usedSystemConfirmation = false
        var commandResults: List<PrivilegeManager.MediaCommandResult> = emptyList()

        if (shellAvailable) {
            commandResults = PrivilegeManager.setImagesTrashed(photos, trashed = action.targetTrashed) +
                PrivilegeManager.setVideosTrashed(videos, trashed = action.targetTrashed)
            if (commandResults.all { it.result.ok } && verifyItemsState(items)) {
                usedPrivilegedShell = true
            }
        }

        if (!usedPrivilegedShell && PrivilegeManager.canManageMedia(context)) {
            try {
                var updatedCount = 0
                items.forEach { item ->
                    val updated = context.contentResolver.update(
                        item.uri(),
                        ContentValues().apply { put("is_trashed", if (action.targetTrashed) 1 else 0) },
                        null,
                        null
                    )
                    if (updated > 0) updatedCount += 1
                }
                if (updatedCount == items.size && verifyItemsState(items)) {
                    usedManageMedia = true
                }
            } catch (e: SecurityException) {
                usedManageMedia = false
            }
        }

        if (!usedPrivilegedShell && !usedManageMedia) {
            usedSystemConfirmation = requestSystemMutation(items)
        }

        if (!usedPrivilegedShell && !usedManageMedia && !usedSystemConfirmation) {
            return@withContext mediaAccessError(
                args = args,
                items = items,
                commandResults = commandResults,
                code = "media_mutation_denied",
                message = action.deniedMessage,
                usedManageMedia = false,
                usedSystemConfirmation = usedSystemConfirmation
            )
        }

        val result = mapper.createObjectNode().apply {
            set<ArrayNode>("items", itemsArray(items))
            put("trashed", action.targetTrashed)
            if (action.includeRestoredField) put("restored", true)
            put("affected_count", items.size)
            put("used_privileged_shell", usedPrivilegedShell)
            put("used_manage_media", usedManageMedia)
            put("used_system_confirmation", usedSystemConfirmation)
            put("system_confirmation_required", usedSystemConfirmation)
            put("system_confirmation_suppressed", false)
            if (commandResults.isNotEmpty()) {
                set<ArrayNode>("privileged_shell_results", commandResultsArray(commandResults))
            }
            set<JsonNode>("summary", mapper.createObjectNode().apply {
                put("affected_count", items.size)
                put("photo_count", photos.size)
                put("video_count", videos.size)
                put("trashed", action.targetTrashed)
                if (action.includeRestoredField) put("restored", true)
                put("used_privileged_shell", usedPrivilegedShell)
                put("used_manage_media", usedManageMedia)
                put("used_system_confirmation", usedSystemConfirmation)
            })
        }
        ToolResultEnvelope.applyStandardFields(
            mapper = mapper,
            tool = tool,
            result = result,
            ok = true,
            resultType = "confirmed",
            displayPolicy = "debug_only",
            request = args
        )
    }

    private suspend fun requestSystemMutation(items: List<GalleryMediaItem>): Boolean {
        val uris = items.map { it.uri() }
        val approved = MediaStoreRequestBridge.request(
            context,
            MediaStore.createTrashRequest(context.contentResolver, uris, action.targetTrashed),
            autoApprove = false
        )
        return approved && verifyItemsState(items)
    }

    private fun verifyItemsState(items: List<GalleryMediaItem>): Boolean =
        items.all { item -> queryTrashState(item) == action.targetTrashed }

    private fun queryTrashState(item: GalleryMediaItem): Boolean? {
        return context.contentResolver.query(
            item.uri(),
            arrayOf(MediaStore.MediaColumns._ID, "is_trashed"),
            Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            },
            null
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val idx = c.getColumnIndex("is_trashed")
            idx >= 0 && !c.isNull(idx) && c.getInt(idx) == 1
        }
    }

    private fun mediaAccessError(
        args: JsonNode,
        items: List<GalleryMediaItem>,
        commandResults: List<PrivilegeManager.MediaCommandResult>,
        code: String,
        message: String,
        usedManageMedia: Boolean,
        usedSystemConfirmation: Boolean
    ): ObjectNode {
        val result = ToolResultEnvelope.error(
            mapper = mapper,
            tool = tool,
            code = code,
            message = message,
            retryable = true,
            hint = "Approve the Android media confirmation prompt, or run privileges.status and configure Shizuku/MANAGE_MEDIA for background operation.",
            request = args
        )
        result.set<ArrayNode>("items", itemsArray(items))
        result.put("affected_count", 0)
        result.put("used_privileged_shell", false)
        result.put("used_manage_media", usedManageMedia)
        result.put("used_system_confirmation", usedSystemConfirmation)
        result.put("system_confirmation_required", true)
        result.put("system_confirmation_suppressed", false)
        if (commandResults.isNotEmpty()) {
            result.set<ArrayNode>("privileged_shell_results", commandResultsArray(commandResults))
        }
        result.set<JsonNode>("summary", mapper.createObjectNode().apply {
            put("affected_count", 0)
            put("trashed", !action.targetTrashed)
            if (action.includeRestoredField) put("restored", false)
            put("requires_privileged_media_access", true)
            put("used_system_confirmation", usedSystemConfirmation)
            put("system_confirmation_suppressed", false)
        })
        return result
    }

    private fun parseItems(args: JsonNode): List<GalleryMediaItem> {
        val raw = mutableListOf<GalleryMediaItem>()
        val items = args.path("items")
        if (items.isArray) {
            items.forEachIndexed { index, item ->
                raw += parseItem(item, "items[$index]")
            }
        }
        val topId = args.path("id").asText("").trim()
        if (topId.isNotBlank()) {
            raw += parseItem(args, "id")
        }
        val distinct = raw.distinctBy { "${it.mediaType}:${it.id}" }
        require(distinct.isNotEmpty()) { "items or id is required" }
        require(distinct.size <= MAX_BATCH) { "at most $MAX_BATCH media items can be ${action.batchVerb} at once" }
        return distinct
    }

    private fun parseItem(item: JsonNode, path: String): GalleryMediaItem {
        val parsedRef = parseMediaRef(item.path("media_ref").asText(""))
        val type = item.path("media_type").asText(parsedRef?.first ?: "photo")
            .lowercase(Locale.ROOT)
            .trim()
        require(type == "photo" || type == "video") { "$path.media_type must be photo or video" }
        val idText = item.path("id").asText(parsedRef?.second ?: "").trim()
        val id = idText.toLongOrNull()
            ?: throw IllegalArgumentException("$path.id must be a numeric MediaStore id")
        return GalleryMediaItem(type, id)
    }

    private fun parseMediaRef(value: String): Pair<String, String>? {
        val clean = value.trim()
        val match = MEDIA_REF_REGEX.matchEntire(clean) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun itemsArray(items: List<GalleryMediaItem>): ArrayNode =
        mapper.createArrayNode().apply {
            items.forEach { item ->
                addObject()
                    .put("media_type", item.mediaType)
                    .put("id", item.id.toString())
                    .put("media_ref", "media://${item.mediaType}/${item.id}")
            }
        }

    private fun commandResultsArray(commands: List<PrivilegeManager.MediaCommandResult>): ArrayNode =
        mapper.createArrayNode().apply {
            commands.forEach { command ->
                addObject().apply {
                    put("id", command.id.toString())
                    put("ok", command.result.ok)
                    put("exit_code", command.result.exitCode)
                    put("timed_out", command.result.timedOut)
                    if (command.result.stdout.isNotBlank()) put("stdout", command.result.stdout.take(1000))
                    if (command.result.stderr.isNotBlank()) put("stderr", command.result.stderr.take(1000))
                }
            }
        }

    private data class GalleryMediaItem(
        val mediaType: String,
        val id: Long
    ) {
        fun uri(): Uri {
            val base = if (mediaType == "video") {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            return ContentUris.withAppendedId(base, id)
        }
    }

    private companion object {
        private const val MAX_BATCH = 100
        private val MEDIA_REF_REGEX = Regex("^media://(photo|video)/(\\d+)$")
    }
}
