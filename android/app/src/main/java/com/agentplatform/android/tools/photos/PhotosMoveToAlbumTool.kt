package com.agentplatform.android.tools.photos

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotosMoveToAlbumTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {
    override val name: String = "photos.move_to_album"

    override val description: String = """
        Move existing gallery photos into an album/folder under Pictures.
        Pass `id` for one photo, `ids` for a batch, or `selection_id` from
        media.selection.create for a previously reviewed set. On Android 10+
        this uses MediaStore RELATIVE_PATH and may trigger Android's system
        media confirmation UI.
    """.trimIndent()

    override val schema: JsonNode = PhotoMutationHelpers.mediaSelectionSchema(
        mapper = mapper,
        idDescription = "Single photo id to move.",
        idsDescription = "Photo ids to move. Maximum 100.",
        additionalProperties = listOf(
            "album" to PhotoMutationHelpers.stringProperty(
                mapper,
                "Album/folder name under Pictures. Created by the gallery when the first image is moved there."
            )
        ),
        required = listOf("album")
    )

    override val confirmRequired: Boolean = true

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException("moving photos by album requires Android 10 or newer")
        }
        val ids = PhotoMutationHelpers.parseIds(context, mapper, args)
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

        val result = mapper.createObjectNode().apply {
            set<JsonNode>("ids", PhotoMutationHelpers.idsArray(mapper, ids))
            put("album", album)
            put("relative_path", relativePath)
            put("updated_count", updatedCount)
            set<JsonNode>("failures", failures)
            set<JsonNode>("summary", mapper.createObjectNode().apply {
                put("updated_count", updatedCount)
                put("failure_count", failures.size())
                put("album", album)
            })
        }
        ToolResultEnvelope.applyStandardFields(
            mapper,
            this@PhotosMoveToAlbumTool,
            result,
            ok = failures.size() == 0,
            request = args
        )
    }
}
