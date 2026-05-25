package com.agentplatform.android.tools.photos

import android.content.Context
import android.provider.MediaStore
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
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

    override val schema: JsonNode = PhotoMutationHelpers.mediaSelectionSchema(
        mapper = mapper,
        idDescription = "Single photo id.",
        idsDescription = "Photo ids. Maximum 100.",
        additionalProperties = listOf(
            "favorite" to mapper.createObjectNode().apply {
                put("type", "boolean")
                put("default", true)
                put("description", "true to favorite, false to unfavorite.")
            }
        )
    )

    override val confirmRequired: Boolean = true

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        PhotoMutationHelpers.requireAndroidR("favorite")
        val ids = PhotoMutationHelpers.parseIds(context, mapper, args)
        val favorite = args.path("favorite").asBoolean(true)
        val uris = ids.map { PhotoMutationHelpers.photoUri(it) }
        val result = PhotoMutationHelpers.confirmedMutationSuccess(
            context = context,
            mapper = mapper,
            ids = ids,
            spec = PhotoMutationHelpers.ConfirmedMutationSpec(
                pendingIntent = MediaStore.createFavoriteRequest(context.contentResolver, uris, favorite),
                rejectedMessage = "Android media favorite confirmation rejected",
                rootFields = listOf("favorite" to favorite)
            )
        )
        ToolResultEnvelope.applyStandardFields(mapper, this@PhotosFavoriteTool, result, ok = true, request = args)
    }
}
