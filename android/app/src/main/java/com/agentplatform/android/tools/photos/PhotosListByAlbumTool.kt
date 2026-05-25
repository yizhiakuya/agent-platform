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

/**
 * Lists cached, uploaded display-sized original image assets inside one
 * specific album bucket.
 */
class PhotosListByAlbumTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {

    override val name: String = "photos.list_by_album"

    override val description: String = """
        List photos inside one specific album bucket as cached display-sized
        original JPEG asset URLs (up to 2048px long edge). `bucket_id`
        comes from photos.list_albums. Optional date_after/date_before filter
        the album by UNIX milliseconds.
        Choose `limit` from the user's actual request. If you are continuing
        after a previous result, reuse `next_args` or pass `offset`. Use smaller
        `max_dim` (512-768) when browsing many photos.
    """.trimIndent()

    override val schema: JsonNode = PhotoListQueryHelper.gridSchema(
        mapper = mapper,
        itemLabel = "album photos",
        additionalProperties = listOf(
            "bucket_id" to PhotoListQueryHelper.stringProperty(
                mapper,
                "Album bucket id from photos.list_albums."
            ),
            "date_after" to PhotoListQueryHelper.integerProperty(
                mapper,
                "Only return photos taken on/after this UNIX millisecond timestamp (UTC)."
            ),
            "date_before" to PhotoListQueryHelper.integerProperty(
                mapper,
                "Only return photos taken on/before this UNIX millisecond timestamp (UTC)."
            )
        ),
        required = listOf("bucket_id")
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        val bucketId = args.path("bucket_id").asText("").trim()
        require(bucketId.isNotEmpty()) { "bucket_id is required" }
        val request = PhotoListQueryHelper.pageRequest(args)
        val dateAfter = PhotoListQueryHelper.optionalLong(args, "date_after")
        val dateBefore = PhotoListQueryHelper.optionalLong(args, "date_before")
        val selection = PhotoListQueryHelper.selection(
            listOf(
                "${MediaStore.Images.Media.BUCKET_ID} = ?" to bucketId,
                "${MediaStore.Images.Media.DATE_TAKEN} >= ?" to dateAfter?.toString(),
                "${MediaStore.Images.Media.DATE_TAKEN} <= ?" to dateBefore?.toString()
            )
        )
        val page = PhotoListQueryHelper.queryImages(context, mapper, request, selection, TAG)
        val nextArgs = if (page.hasMore) nextArgs(bucketId, request, page.photos.size(), dateAfter, dateBefore) else null
        val result = PhotoListQueryHelper.gridResult(
            mapper = mapper,
            page = page,
            request = request,
            nextArgs = nextArgs,
            rootFields = listOf("bucket_id" to bucketId),
            summaryFields = listOf("bucket_id" to bucketId, "date_after" to dateAfter, "date_before" to dateBefore)
        )
        ToolResultEnvelope.applyStandardFields(
            mapper = mapper,
            tool = this@PhotosListByAlbumTool,
            result = result,
            ok = true,
            resultType = "results",
            displayPolicy = "show_grid",
            request = args
        )
    }

    private fun nextArgs(
        bucketId: String,
        request: PhotoPageRequest,
        nextOffset: Int,
        dateAfter: Long?,
        dateBefore: Long?
    ) = PhotoListQueryHelper.nextArgs(
        mapper,
        listOf(
            "bucket_id" to bucketId,
            "limit" to request.limit,
            "offset" to request.offset + nextOffset,
            "max_dim" to request.maxDim,
            "date_after" to dateAfter,
            "date_before" to dateBefore
        )
    )

    companion object {
        private const val TAG = "PhotosListByAlbumTool"
    }
}
