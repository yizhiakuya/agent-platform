package com.agentplatform.android.tools.photos

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.agentplatform.android.core.tool.Tool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotosRenameTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {
    override val name: String = "photos.rename"

    override val description: String = """
        Rename one existing photo in the device gallery. Use `id` from
        photos.list_recent, photos.list_by_album, or photos.semantic_candidates.
        This edits the gallery and may trigger Android's system media
        confirmation UI.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "id": {
              "type": "string",
              "description": "Photo id to rename."
            },
            "filename": {
              "type": "string",
              "description": "New display filename. If no extension is present, the original photo MIME type is used."
            }
          },
          "required": ["id", "filename"]
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = true

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        val id = PhotoMutationHelpers.parseIds(args).single()
        val requestedName = args.path("filename").asText("").trim()
        require(requestedName.isNotBlank()) { "filename is required" }
        val record = PhotoMutationHelpers.queryPhoto(context, id)
        val filename = PhotoMutationHelpers.sanitizeFilename(requestedName, record.name, record.mimeType)

        PhotoMutationHelpers.ensureWriteAccess(context, listOf(id))
        val updated = PhotoMutationHelpers.updateWithRecovery(
            context,
            record.uri,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            }
        )

        mapper.createObjectNode().apply {
            put("ok", updated > 0)
            put("id", id.toString())
            put("old_filename", record.name)
            put("filename", filename)
            put("updated_count", updated)
        }
    }
}
