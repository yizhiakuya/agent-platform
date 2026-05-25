package com.agentplatform.android.tools.photos

import android.content.Context
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

    override val schema: JsonNode = PhotoMutationHelpers.specialListSchema(
        mapper,
        "Max favorite photos to return."
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        PhotoMutationHelpers.requireAndroidR("listing favorites")
        PhotoMutationHelpers.specialListResult(
            context = context,
            mapper = mapper,
            limit = args.path("limit").asInt(20).coerceIn(1, 50),
            matchArg = MediaStore.QUERY_ARG_MATCH_FAVORITE
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

    override val schema: JsonNode = PhotoMutationHelpers.specialListSchema(
        mapper,
        "Max trashed photos to return."
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        PhotoMutationHelpers.requireAndroidR("listing trash")
        PhotoMutationHelpers.specialListResult(
            context = context,
            mapper = mapper,
            limit = args.path("limit").asInt(20).coerceIn(1, 50),
            matchArg = MediaStore.QUERY_ARG_MATCH_TRASHED
        )
    }
}
