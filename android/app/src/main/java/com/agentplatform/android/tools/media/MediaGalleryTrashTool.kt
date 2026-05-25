package com.agentplatform.android.tools.media

import android.content.Context
import com.agentplatform.android.core.tool.Tool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

class MediaGalleryTrashTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {
    override val name: String = "media.gallery.trash"

    override val description: String = """
        Move selected phone-gallery media to Android's trash/recycle bin. Use
        this for user-confirmed delete actions from media.gallery.browse
        results. Supports photo and video MediaStore ids. Requires Android 11+
        and Shizuku or MANAGE_MEDIA to avoid foreground system confirmation.
    """.trimIndent()

    override val toolClass: String = "mutation"
    override val resultType: String = "confirmed"
    override val defaultDisplayPolicy: String = "debug_only"
    override val schema: JsonNode = mediaGalleryMutationSchema(mapper)
    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode =
        MediaGalleryMutationExecutor(context, mapper, this, MediaGalleryMutationAction.TRASH).execute(args)
}
