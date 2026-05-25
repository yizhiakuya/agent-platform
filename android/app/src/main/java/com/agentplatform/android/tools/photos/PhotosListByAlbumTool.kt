package com.agentplatform.android.tools.photos

import android.content.Context
import android.provider.MediaStore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Lists cached, uploaded display-sized original image assets inside one
 * specific album bucket.
 */
class PhotosListByAlbumTool(
    context: Context,
    mapper: ObjectMapper,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PhotoGridTool(context, mapper, ioDispatcher) {

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
            )
        ) + PhotoListQueryHelper.dateRangeProperties(mapper),
        required = listOf("bucket_id")
    )

    override fun gridResult(args: JsonNode): ObjectNode {
        val bucketId = args.path("bucket_id").asText("").trim()
        require(bucketId.isNotEmpty()) { "bucket_id is required" }
        val dateRange = PhotoListQueryHelper.dateRange(args)
        return PhotoListQueryHelper.filteredGridResult(
            PhotoFilteredGridQuery(
                context = context,
                mapper = mapper,
                args = args,
                tag = TAG,
                selectionFields = listOf(
                    "${MediaStore.Images.Media.BUCKET_ID} = ?" to bucketId,
                ) + PhotoListQueryHelper.dateRangeSelection(dateRange),
                nextArgFields = { request, nextOffset ->
                    listOf(
                        "bucket_id" to bucketId,
                        "limit" to request.limit,
                        "offset" to nextOffset,
                        "max_dim" to request.maxDim,
                    ) + dateRange.fields
                },
                rootFields = listOf("bucket_id" to bucketId),
                summaryFields = listOf("bucket_id" to bucketId) + dateRange.fields
            )
        )
    }

    companion object {
        private const val TAG = "PhotosListByAlbumTool"
    }
}
