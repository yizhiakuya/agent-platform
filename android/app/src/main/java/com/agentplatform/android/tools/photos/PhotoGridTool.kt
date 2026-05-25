package com.agentplatform.android.tools.photos

import android.content.Context
import android.provider.MediaStore
import com.agentplatform.android.core.tool.Tool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

abstract class PhotoGridTool(
    private val definition: PhotoGridDefinition,
    protected val context: Context,
    protected val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher
) : Tool {

    final override val name: String = definition.name
    final override val description: String = definition.description
    final override val schema: JsonNode = definition.schema(mapper)
    final override val confirmRequired: Boolean = false

    final override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        PhotoListQueryHelper.standardGridEnvelope(
            mapper,
            this@PhotoGridTool,
            definition.gridResult(context, mapper, args),
            args
        )
    }
}

class PhotoGridDefinition(
    val name: String,
    val description: String,
    private val tag: String,
    private val itemLabel: String,
    private val filter: PhotoGridFilter,
    private val required: List<String>
) {
    fun schema(mapper: ObjectMapper): ObjectNode =
        PhotoListQueryHelper.gridSchema(
            mapper = mapper,
            itemLabel = itemLabel,
            additionalProperties = listOf(filter.fieldName to filter.property(mapper)) +
                    PhotoListQueryHelper.dateRangeProperties(mapper),
            required = required
        )

    fun gridResult(context: Context, mapper: ObjectMapper, args: JsonNode): ObjectNode {
        val filterValue = filter.value(args)
        val dateRange = PhotoListQueryHelper.dateRange(args)
        return PhotoListQueryHelper.filteredGridResult(
            PhotoFilteredGridQuery(
                context = context,
                mapper = mapper,
                args = args,
                tag = tag,
                selectionFields = listOf(filter.selection to filter.selectionValue(filterValue)) +
                        PhotoListQueryHelper.dateRangeSelection(dateRange),
                nextArgFields = { request, nextOffset ->
                    nextArgFields(filterValue, request, nextOffset, dateRange)
                },
                rootFields = rootFields(filterValue),
                summaryFields = summaryFields(filterValue, dateRange)
            )
        )
    }

    private fun nextArgFields(
        filterValue: String?,
        request: PhotoPageRequest,
        nextOffset: Int,
        dateRange: PhotoDateRange
    ): List<Pair<String, Any?>> =
        filter.nextArgFields(filterValue) + listOf(
            "limit" to request.limit,
            "offset" to nextOffset,
            "max_dim" to request.maxDim
        ) + dateRange.fields

    private fun rootFields(filterValue: String?): List<Pair<String, Any?>> =
        filter.rootFieldName?.let { listOf(it to filterValue) } ?: emptyList()

    private fun summaryFields(filterValue: String?, dateRange: PhotoDateRange): List<Pair<String, Any?>> =
        filter.summaryFields(filterValue) + dateRange.fields
}

class PhotoGridFilter(
    val fieldName: String,
    private val propertyDescription: String,
    val selection: String,
    val rootFieldName: String? = null,
    private val requireValue: Boolean = false,
    private val likeSelection: Boolean = false
) {
    fun property(mapper: ObjectMapper): ObjectNode =
        PhotoListQueryHelper.stringProperty(mapper, propertyDescription)

    fun value(args: JsonNode): String? {
        val value = PhotoListQueryHelper.optionalText(args, fieldName)
        require(!requireValue || value != null) { "$fieldName is required" }
        return value
    }

    fun selectionValue(value: String?): String? =
        if (likeSelection) value?.let { "%$it%" } else value

    fun nextArgFields(value: String?): List<Pair<String, Any?>> =
        listOf(fieldName to value)

    fun summaryFields(value: String?): List<Pair<String, Any?>> =
        listOf(fieldName to value)
}

object PhotoGridDefinitions {
    val recent = PhotoGridDefinition(
        name = "photos.list_recent",
        description = """
            List photos from the device's gallery as cached display-sized original
            JPEG asset URLs (up to 2048px long edge). Returns the most-recent
            matching photos first.

            Choose `limit` from the user's actual request: ask for 8, 30, 100,
            etc. in one call when that is what you need. Use narrow filters when
            possible because every returned item is uploaded as a display image.
            Use smaller `max_dim` (512-768) when browsing many photos, and larger
            `max_dim` (1024-2048) only when detail matters. In particular:

            - For "photos from app X" / "screenshots of X": pass `name_contains` with
              a substring of X (Android screenshots include the source package in
              the filename, e.g. "Screenshot_..._com.example.app.jpg").
            - For "photos from yesterday / last week / a date": pass `date_after` and
              optionally `date_before` (UNIX millis, UTC).
            - Only use a bare `limit` with no filters when the user explicitly asks
              for "recent photos" with no qualifier.

            `limit` is the number of photos you want back in this call. If you are
            continuing after a previous result, reuse `next_args` or pass `offset`
            so you do not repeat the same first page.
        """.trimIndent(),
        tag = "PhotosListRecentTool",
        itemLabel = "photos",
        filter = PhotoGridFilter(
            fieldName = "name_contains",
            propertyDescription = "Case-insensitive substring of the filename. Useful for filtering Android screenshots by source app (e.g. 'com.max.xiaoheihe').",
            selection = MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?",
            likeSelection = true
        ),
        required = listOf("limit")
    )

    val byAlbum = PhotoGridDefinition(
        name = "photos.list_by_album",
        description = """
            List photos inside one specific album bucket as cached display-sized
            original JPEG asset URLs (up to 2048px long edge). `bucket_id`
            comes from photos.list_albums. Optional date_after/date_before filter
            the album by UNIX milliseconds.
            Choose `limit` from the user's actual request. If you are continuing
            after a previous result, reuse `next_args` or pass `offset`. Use smaller
            `max_dim` (512-768) when browsing many photos.
        """.trimIndent(),
        tag = "PhotosListByAlbumTool",
        itemLabel = "album photos",
        filter = PhotoGridFilter(
            fieldName = "bucket_id",
            propertyDescription = "Album bucket id from photos.list_albums.",
            selection = "${MediaStore.Images.Media.BUCKET_ID} = ?",
            rootFieldName = "bucket_id",
            requireValue = true
        ),
        required = listOf("bucket_id")
    )
}
