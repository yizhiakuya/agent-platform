package com.agentplatform.android.tools.photos

import android.content.ContentUris
import android.content.Context
import com.agentplatform.android.core.tool.Tool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotosCopyToAlbumTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {
    override val name: String = "photos.copy_to_album"

    override val description: String = """
        Copy existing gallery photos into an album/folder under Pictures without
        removing the originals. Pass `id` for one photo or `ids` for a batch.
        This creates new gallery media and can be used to create a non-empty
        album from existing photos.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "id": {
              "type": "string",
              "description": "Single source photo id to copy."
            },
            "ids": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Source photo ids to copy. Maximum 100."
            },
            "album": {
              "type": "string",
              "description": "Album/folder name under Pictures."
            },
            "filename": {
              "type": "string",
              "description": "Optional filename for single-photo copy. Ignored for batch copies."
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
        val ids = PhotoMutationHelpers.parseIds(args)
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

        mapper.createObjectNode().apply {
            put("ok", failures.size() == 0)
            put("album", album)
            set<JsonNode>("copied", copied)
            put("copied_count", copied.size())
            set<JsonNode>("failures", failures)
        }
    }
}
