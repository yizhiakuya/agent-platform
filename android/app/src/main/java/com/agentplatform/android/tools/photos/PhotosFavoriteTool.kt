package com.agentplatform.android.tools.photos

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.media.MediaStoreRequestBridge
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotosFavoriteTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {
    override val name: String = "photos.favorite"

    override val description: String = """
        Mark existing gallery photos as favorite or remove them from favorites.
        Pass `id` for one photo or `ids` for a batch. Requires Android 11+ and
        may trigger Android's system media confirmation UI.
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
            "favorite": {
              "type": "boolean",
              "default": true,
              "description": "true to favorite, false to unfavorite."
            }
          },
          "anyOf": [
            { "required": ["id"] },
            { "required": ["ids"] }
          ]
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = true

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw UnsupportedOperationException("favorite requires Android 11 or newer")
        }
        val ids = PhotoMutationHelpers.parseIds(args)
        val favorite = if (args.has("favorite")) args.path("favorite").asBoolean() else true
        val uris = ids.map { PhotoMutationHelpers.photoUri(it) }
        val approved = MediaStoreRequestBridge.request(
            context,
            MediaStore.createFavoriteRequest(context.contentResolver, uris, favorite)
        )
        if (!approved) throw SecurityException("Android media favorite confirmation rejected")

        mapper.createObjectNode().apply {
            put("ok", true)
            set<JsonNode>("ids", PhotoMutationHelpers.idsArray(mapper, ids))
            put("favorite", favorite)
            put("affected_count", ids.size)
        }
    }
}
