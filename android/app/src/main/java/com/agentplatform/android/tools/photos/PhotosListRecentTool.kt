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
 * Returns the N most-recent photos from the device gallery as cached,
 * uploaded display-sized original JPEG assets. Used by the LLM to answer
 * "show me my recent photos".
 *
 * Permission: requires READ_MEDIA_IMAGES on Android 13+ or legacy
 * READ_EXTERNAL_STORAGE on older versions. If permission is missing, the
 * MediaStore cursor is null and the tool returns an empty array.
 */
class PhotosListRecentTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {

    override val name: String = "photos.list_recent"

    override val description: String = """
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
    """.trimIndent()

    override val schema: JsonNode = PhotoListQueryHelper.gridSchema(
        mapper = mapper,
        itemLabel = "photos",
        additionalProperties = listOf(
            "name_contains" to PhotoListQueryHelper.stringProperty(
                mapper,
                "Case-insensitive substring of the filename. Useful for filtering Android screenshots by source app (e.g. 'com.max.xiaoheihe')."
            )
        ) + PhotoListQueryHelper.dateRangeProperties(mapper),
        required = listOf("limit")
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        val nameContains = PhotoListQueryHelper.optionalText(args, "name_contains")
        val dateRange = PhotoListQueryHelper.dateRange(args)
        val result = PhotoListQueryHelper.filteredGridResult(
            context = context,
            mapper = mapper,
            args = args,
            tag = TAG,
            selectionFields = listOf(
                MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?" to nameContains?.let { "%$it%" },
            ) + PhotoListQueryHelper.dateRangeSelection(dateRange),
            nextArgFields = { request, nextOffset ->
                listOf(
                    "limit" to request.limit,
                    "offset" to nextOffset,
                    "max_dim" to request.maxDim,
                    "name_contains" to nameContains
                ) + dateRange.fields
            },
            summaryFields = listOf(
                "name_contains" to nameContains,
            ) + dateRange.fields
        )
        ToolResultEnvelope.applyStandardFields(
            mapper = mapper,
            tool = this@PhotosListRecentTool,
            result = result,
            ok = true,
            resultType = "results",
            displayPolicy = "show_grid",
            request = args
        )
    }

    companion object {
        private const val TAG = "PhotosListRecentTool"
    }
}
