package com.agentplatform.android.tools.photos

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.media.MediaStoreRequestBridge
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotosFavoriteTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {
    override val name: String = "photos.favorite"

    override val description: String = """
        Mark existing gallery photos as favorite or remove them from favorites.
        Pass `id` for one photo, `ids` for a batch, or `selection_id` from
        media.selection.create for a previously reviewed set. Requires Android
        11+ and may trigger Android's system media confirmation UI.
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
            },
            "favorite": {
              "type": "boolean",
              "default": true,
              "description": "true to favorite, false to unfavorite."
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

    override val confirmRequired: Boolean = true

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw UnsupportedOperationException("favorite requires Android 11 or newer")
        }
        val ids = PhotoMutationHelpers.parseIds(context, mapper, args)
        val favorite = if (args.has("favorite")) args.path("favorite").asBoolean() else true
        val uris = ids.map { PhotoMutationHelpers.photoUri(it) }
        val approved = MediaStoreRequestBridge.request(
            context,
            MediaStore.createFavoriteRequest(context.contentResolver, uris, favorite)
        )
        if (!approved) throw SecurityException("Android media favorite confirmation rejected")

        val result = mapper.createObjectNode().apply {
            set<JsonNode>("ids", PhotoMutationHelpers.idsArray(mapper, ids))
            put("favorite", favorite)
            put("affected_count", ids.size)
            set<JsonNode>("summary", mapper.createObjectNode().apply {
                put("affected_count", ids.size)
                put("favorite", favorite)
            })
        }
        ToolResultEnvelope.applyStandardFields(mapper, this@PhotosFavoriteTool, result, ok = true, request = args)
    }
}
