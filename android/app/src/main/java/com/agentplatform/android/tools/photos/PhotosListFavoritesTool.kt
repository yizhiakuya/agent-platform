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
    context: Context,
    mapper: ObjectMapper,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PhotoSpecialListTool(
    context = context,
    mapper = mapper,
    ioDispatcher = ioDispatcher,
    action = "listing favorites",
    limitDescription = "Max favorite photos to return.",
    matchArg = MediaStore.QUERY_ARG_MATCH_FAVORITE
) {
    override val name: String = "photos.list_favorites"

    override val description: String = """
        List photos marked as favorite in the device gallery. Returns the most
        recent matching favorites first as cached display-sized image asset URLs.
        Use this to inspect or verify favorite photo operations.
    """.trimIndent()
}

class PhotosListTrashTool(
    context: Context,
    mapper: ObjectMapper,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PhotoSpecialListTool(
    context = context,
    mapper = mapper,
    ioDispatcher = ioDispatcher,
    action = "listing trash",
    limitDescription = "Max trashed photos to return.",
    matchArg = MediaStore.QUERY_ARG_MATCH_TRASHED
) {
    override val name: String = "photos.list_trash"

    override val description: String = """
        List photos currently in Android's trash/recycle bin. Returns the most
        recent trashed photos first. Use this before photos.restore when the
        user asks to recover deleted photos.
    """.trimIndent()
}

abstract class PhotoSpecialListTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher,
    private val action: String,
    limitDescription: String,
    private val matchArg: String
) : Tool {
    override val schema: JsonNode = PhotoMutationHelpers.specialListSchema(mapper, limitDescription)
    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        PhotoMutationHelpers.requireAndroidR(action)
        PhotoMutationHelpers.specialListResult(
            context = context,
            mapper = mapper,
            limit = args.path("limit").asInt(20).coerceIn(1, 50),
            matchArg = matchArg
        )
    }
}
