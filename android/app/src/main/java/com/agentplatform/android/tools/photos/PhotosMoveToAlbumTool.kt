package com.agentplatform.android.tools.photos

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.agentplatform.android.core.tool.Tool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotosMoveToAlbumTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {
    override val name: String = "photos.move_to_album"

    override val description: String = """
        Move existing gallery photos into an album/folder under Pictures.
        Pass `id` for one photo or `ids` for a batch. On Android 10+ this uses
        MediaStore RELATIVE_PATH and may trigger Android's system media
        confirmation UI.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "id": {
              "type": "string",
              "description": "Single photo id to move."
            },
            "ids": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Photo ids to move. Maximum 100."
            },
            "album": {
              "type": "string",
              "description": "Album/folder name under Pictures. Created by the gallery when the first image is moved there."
            }
          },
          "required": ["album"],
          "anyOf": [
            { "required": ["id"] },
            { "required": ["ids"] }
          ]
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = true

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException("moving photos by album requires Android 10 or newer")
        }
        val ids = PhotoMutationHelpers.parseIds(args)
        val album = PhotoMutationHelpers.sanitizeAlbum(args.path("album").asText(""))
        val relativePath = PhotoMutationHelpers.relativePicturesPath(album)
        val failures = PhotoMutationHelpers.failuresArray(mapper)
        var updatedCount = 0

        PhotoMutationHelpers.ensureWriteAccess(context, ids)
        for (id in ids) {
            try {
                val updated = PhotoMutationHelpers.updateWithRecovery(
                    context,
                    PhotoMutationHelpers.photoUri(id),
                    ContentValues().apply {
                        put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    }
                )
                if (updated > 0) updatedCount += updated else failures.addObject()
                    .put("id", id.toString())
                    .put("error", "photo not found or not updated")
            } catch (e: Exception) {
                PhotoMutationHelpers.addFailure(failures, id, e)
            }
        }

        mapper.createObjectNode().apply {
            put("ok", failures.size() == 0)
            set<JsonNode>("ids", PhotoMutationHelpers.idsArray(mapper, ids))
            put("album", album)
            put("relative_path", relativePath)
            put("updated_count", updatedCount)
            set<JsonNode>("failures", failures)
        }
    }
}
