package com.agentplatform.android.tools.photos

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
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

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "limit": {
              "type": "integer",
              "minimum": 1,
              "default": 12,
              "description": "Number of photos the agent wants in this call. Choose the count that matches the task."
            },
            "offset": {
              "type": "integer",
              "minimum": 0,
              "default": 0,
              "description": "Number of matching photos to skip. Use next_args.offset from a previous result to fetch the next page."
            },
            "max_dim": {
              "type": "integer",
              "minimum": 512,
              "maximum": 2048,
              "default": 1024,
              "description": "Long-edge size for returned display images. Use 512-768 for many photos; use 1024-2048 for detail."
            },
            "name_contains": {
              "type": "string",
              "description": "Case-insensitive substring of the filename. Useful for filtering Android screenshots by source app (e.g. 'com.max.xiaoheihe')."
            },
            "date_after": {
              "type": "integer",
              "description": "Only return photos taken on/after this UNIX millisecond timestamp (UTC)."
            },
            "date_before": {
              "type": "integer",
              "description": "Only return photos taken on/before this UNIX millisecond timestamp (UTC)."
            }
          },
          "required": ["limit"]
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        val uploader = PhotoAssetUploader(context, mapper)
        val requestedLimit = args.path("limit").asInt(DEFAULT_LIMIT).coerceAtLeast(1)
        val limit = requestedLimit.coerceAtMost(MAX_RESULTS_PER_CALL)
        val offset = args.path("offset").asInt(0).coerceAtLeast(0)
        val maxDim = args.path("max_dim").asInt(DEFAULT_MAX_DIM).coerceIn(MIN_MAX_DIM, MAX_MAX_DIM)
        val nameContains = args.path("name_contains").asText("").trim().takeIf { it.isNotEmpty() }
        val dateAfter = args.path("date_after").let { if (it.isNumber) it.asLong() else null }
        val dateBefore = args.path("date_before").let { if (it.isNumber) it.asLong() else null }
        val queryLimit = (limit + 1).coerceAtMost(MAX_RESULTS_PER_CALL + 1)

        // Build SQL-like selection from the optional filters.
        val clauses = mutableListOf<String>()
        val params = mutableListOf<String>()
        if (nameContains != null) {
            // SQLite LIKE is case-insensitive for ASCII by default.
            clauses += "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            params += "%$nameContains%"
        }
        if (dateAfter != null) {
            clauses += "${MediaStore.Images.Media.DATE_TAKEN} >= ?"
            params += dateAfter.toString()
        }
        if (dateBefore != null) {
            clauses += "${MediaStore.Images.Media.DATE_TAKEN} <= ?"
            params += dateBefore.toString()
        }
        val selection = clauses.joinToString(" AND ").ifEmpty { null }
        val selectionArgs = if (params.isEmpty()) null else params.toTypedArray()

        val photos: ArrayNode = mapper.createArrayNode()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE
        )
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val args2 = Bundle().apply {
                putStringArray(
                    android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.Images.Media.DATE_TAKEN)
                )
                putInt(
                    android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                )
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, queryLimit)
                putInt(android.content.ContentResolver.QUERY_ARG_OFFSET, offset)
                if (selection != null) {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                }
            }
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, args2, null
            )
        } else {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC LIMIT $queryLimit OFFSET $offset"
            )
        }

        var matchedCount = 0
        var hasMore = false
        cursor?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val modifiedIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE)
            while (c.moveToNext()) {
                matchedCount += 1
                if (photos.size() >= limit) {
                    hasMore = true
                    break
                }
                val id = c.getLong(idIdx)
                val name = c.getString(nameIdx) ?: "image_$id"
                val date = if (dateIdx >= 0 && !c.isNull(dateIdx)) c.getLong(dateIdx) else 0L
                val modified = if (modifiedIdx >= 0 && !c.isNull(modifiedIdx)) c.getLong(modifiedIdx) else 0L
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L

                val image = try {
                    PhotoToolUtils.encodedDisplayPhoto(
                        context = context,
                        id = id,
                        maxDim = maxDim,
                        quality = 85,
                        sourceModifiedSec = modified,
                        sourceSizeBytes = size
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "image encode failed for id=$id: ${e.message}")
                    null
                }

                val photo: ObjectNode = mapper.createObjectNode()
                photo.put("id", id.toString())
                photo.put("name", name)
                photo.put("date_taken_ms", date)
                photo.put("size_bytes", size)
                photo.put("date_modified_sec", modified)
                if (image != null) {
                    val upload = uploader.uploadDisplayJpeg(id, name, image)
                    PhotoAssetUploader.putUploadFields(photo, upload, image)
                }
                photos.add(photo)
            }
        }

        val result: ObjectNode = mapper.createObjectNode()
        result.set<JsonNode>("photos", photos)
        result.put("count", photos.size())
        result.put("requested_limit", requestedLimit)
        result.put("limit", limit)
        result.put("offset", offset)
        result.put("max_dim", maxDim)
        result.put("has_more", hasMore)
        result.put("truncated_by_safety_cap", requestedLimit > limit)
        if (requestedLimit > limit) result.put("safety_cap", MAX_RESULTS_PER_CALL)
        result.put("matched_count_in_window", matchedCount)
        if (hasMore) {
            result.put("next_offset", offset + photos.size())
            result.set<ObjectNode>("next_args", nextArgs(limit, offset + photos.size(), maxDim, nameContains, dateAfter, dateBefore))
        }
        result.set<ObjectNode>("pagination", mapper.createObjectNode().apply {
            put("offset", offset)
            put("requested_limit", requestedLimit)
            put("limit", limit)
            put("max_dim", maxDim)
            put("returned_count", photos.size())
            put("start_index", if (photos.size() == 0) 0 else offset + 1)
            put("end_index", offset + photos.size())
            put("has_more", hasMore)
            put("truncated_by_safety_cap", requestedLimit > limit)
            if (requestedLimit > limit) put("safety_cap", MAX_RESULTS_PER_CALL)
            if (hasMore) {
                put("next_offset", offset + photos.size())
                set<ObjectNode>("next_args", nextArgs(limit, offset + photos.size(), maxDim, nameContains, dateAfter, dateBefore))
            }
        })
        result.set<ObjectNode>("display", mapper.createObjectNode().apply {
            put("policy", "show_grid")
            put("page", if (photos.size() == 0) "0" else "${offset + 1}-${offset + photos.size()}")
            put("has_more", hasMore)
            if (hasMore) put("next_offset", offset + photos.size())
        })
        result.set<ObjectNode>("summary", mapper.createObjectNode().apply {
            put("count", photos.size())
            put("requested_limit", requestedLimit)
            put("limit", limit)
            put("max_dim", maxDim)
            put("offset", offset)
            put("has_more", hasMore)
            put("truncated_by_safety_cap", requestedLimit > limit)
            if (requestedLimit > limit) put("safety_cap", MAX_RESULTS_PER_CALL)
            if (hasMore) put("next_offset", offset + photos.size())
            if (nameContains != null) put("name_contains", nameContains)
            if (dateAfter != null) put("date_after", dateAfter)
            if (dateBefore != null) put("date_before", dateBefore)
        })
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

    private fun nextArgs(
        limit: Int,
        offset: Int,
        maxDim: Int,
        nameContains: String?,
        dateAfter: Long?,
        dateBefore: Long?
    ): ObjectNode {
        return mapper.createObjectNode().apply {
            put("limit", limit)
            put("offset", offset)
            put("max_dim", maxDim)
            if (nameContains != null) put("name_contains", nameContains)
            if (dateAfter != null) put("date_after", dateAfter)
            if (dateBefore != null) put("date_before", dateBefore)
        }
    }

    companion object {
        private const val TAG = "PhotosListRecentTool"
        private const val DEFAULT_LIMIT = 12
        private const val MAX_RESULTS_PER_CALL = 200
        private const val DEFAULT_MAX_DIM = 1024
        private const val MIN_MAX_DIM = 512
        private const val MAX_MAX_DIM = 2048
    }
}
