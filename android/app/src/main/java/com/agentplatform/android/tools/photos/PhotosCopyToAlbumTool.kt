package com.agentplatform.android.tools.photos

import android.content.ContentUris
import android.content.Context
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotosCopyToAlbumTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {
    override val name: String = "photos.copy_to_album"

    override val description: String = """
        Copy existing gallery photos into an album/folder under Pictures without
        removing the originals. Pass `id` for one photo, `ids` for a batch, or
        `selection_id` from media.selection.create for a previously reviewed
        set. This creates new gallery media and can be used to create a
        non-empty album from existing photos.
    """.trimIndent()

    override val schema: JsonNode = PhotoMutationHelpers.mediaSelectionSchema(
        mapper = mapper,
        idDescription = "Single source photo id to copy.",
        idsDescription = "Source photo ids to copy. Maximum 100.",
        additionalProperties = listOf(
            "album" to PhotoMutationHelpers.stringProperty(mapper, "Album/folder name under Pictures."),
            "filename" to PhotoMutationHelpers.stringProperty(
                mapper,
                "Optional filename for single-photo copy. Ignored for batch copies."
            )
        ),
        required = listOf("album")
    )

    override val confirmRequired: Boolean = true

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        val ids = PhotoMutationHelpers.parseIds(context, mapper, args)
        val album = PhotoMutationHelpers.sanitizeAlbum(args.path("album").asText(""))
        val requestedFilename = args.path("filename").asText("").trim().takeIf { ids.size == 1 && it.isNotBlank() }
        val copied = mapper.createArrayNode()
        val failures = PhotoMutationHelpers.failuresArray(mapper)

        for (id in ids) {
            try {
                val source = PhotoMutationHelpers.queryPhoto(context, id)
                val target = PhotoMutationHelpers.copyIntoAlbum(context, source, album, requestedFilename)
                copied.addObject()
                    .put("source_id", id.toString())
                    .put("new_id", ContentUris.parseId(target).toString())
                    .put("uri", target.toString())
            } catch (e: Exception) {
                PhotoMutationHelpers.addFailure(failures, id, e)
            }
        }

        val result = mapper.createObjectNode().apply {
            set<JsonNode>("ids", PhotoMutationHelpers.idsArray(mapper, ids))
            put("album", album)
            set<JsonNode>("copied", copied)
            put("copied_count", copied.size())
            set<JsonNode>("failures", failures)
            set<JsonNode>("summary", mapper.createObjectNode().apply {
                put("copied_count", copied.size())
                put("failure_count", failures.size())
                put("album", album)
            })
        }
        ToolResultEnvelope.applyStandardFields(
            mapper,
            this@PhotosCopyToAlbumTool,
            result,
            ok = failures.size() == 0,
            request = args
        )
    }
}
