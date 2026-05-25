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

class PhotosRestoreTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {
    override val name: String = "photos.restore"

    override val description: String = """
        Restore photos from Android's trash/recycle bin. Pass `id` for one
        photo, `ids` for a batch, or `selection_id` from media.selection.create
        for a previously reviewed set. Requires Android 11+ and may trigger
        Android's system media confirmation UI.
    """.trimIndent()

    override val schema: JsonNode = PhotoMutationHelpers.mediaSelectionSchema(
        mapper = mapper,
        idDescription = "Single trashed photo id.",
        idsDescription = "Trashed photo ids. Maximum 100."
    )

    override val confirmRequired: Boolean = true

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        PhotoMutationHelpers.requireAndroidR("restore")
        val ids = PhotoMutationHelpers.parseIds(context, mapper, args)
        val uris = ids.map { PhotoMutationHelpers.photoUri(it) }
        val result = PhotoMutationHelpers.confirmedMutationSuccess(
            context = context,
            mapper = mapper,
            ids = ids,
            spec = PhotoMutationHelpers.ConfirmedMutationSpec(
                pendingIntent = MediaStore.createTrashRequest(context.contentResolver, uris, false),
                rejectedMessage = "Android media restore confirmation rejected",
                rootFields = listOf("trashed" to false)
            )
        )
        ToolResultEnvelope.applyStandardFields(mapper, this@PhotosRestoreTool, result, ok = true, request = args)
    }
}
