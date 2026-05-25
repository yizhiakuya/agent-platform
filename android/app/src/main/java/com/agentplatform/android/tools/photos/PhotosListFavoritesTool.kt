package com.agentplatform.android.tools.photos

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.agentplatform.android.core.tool.Tool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotosListFavoritesTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {
    override val name: String = "photos.list_favorites"

    override val description: String = """
        List photos marked as favorite in the device gallery. Returns the most
        recent matching favorites first as cached display-sized image asset URLs.
        Use this to inspect or verify favorite photo operations.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "limit": {
              "type": "integer",
              "minimum": 1,
              "maximum": 50,
              "default": 20,
              "description": "Max favorite photos to return."
            }
          }
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw UnsupportedOperationException("listing favorites requires Android 11 or newer")
        }
        listSpecialPhotos(
            context = context,
            mapper = mapper,
            limit = args.path("limit").asInt(20).coerceIn(1, 50),
            matchArg = MediaStore.QUERY_ARG_MATCH_FAVORITE,
            resultKey = "photos"
        )
    }
}

class PhotosListTrashTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {
    override val name: String = "photos.list_trash"

    override val description: String = """
        List photos currently in Android's trash/recycle bin. Returns the most
        recent trashed photos first. Use this before photos.restore when the
        user asks to recover deleted photos.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "limit": {
              "type": "integer",
              "minimum": 1,
              "maximum": 50,
              "default": 20,
              "description": "Max trashed photos to return."
            }
          }
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw UnsupportedOperationException("listing trash requires Android 11 or newer")
        }
        listSpecialPhotos(
            context = context,
            mapper = mapper,
            limit = args.path("limit").asInt(20).coerceIn(1, 50),
            matchArg = MediaStore.QUERY_ARG_MATCH_TRASHED,
            resultKey = "photos"
        )
    }
}

private fun listSpecialPhotos(
    context: Context,
    mapper: ObjectMapper,
    limit: Int,
    matchArg: String,
    resultKey: String
): JsonNode {
    val photos = PhotoListQueryHelper.querySpecialImages(context, mapper, limit, matchArg, "PhotosListSpecial")
    return mapper.createObjectNode().apply {
        set<JsonNode>(resultKey, photos)
        put("count", photos.size())
    }
}
