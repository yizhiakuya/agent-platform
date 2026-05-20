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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists cached, uploaded display-sized original image assets inside one
 * specific album bucket.
 */
class PhotosListByAlbumTool(
    private val context: Context,
    private val mapper: ObjectMapper
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

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "bucket_id": {
              "type": "string",
              "description": "Album bucket id from photos.list_albums."
            },
            "limit": {
              "type": "integer",
              "minimum": 1,
              "default": 12,
              "description": "Number of album photos the agent wants in this call. Choose the count that matches the task."
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
            "date_after": {
              "type": "integer",
              "description": "Only return photos taken on/after this UNIX millisecond timestamp (UTC)."
            },
            "date_before": {
              "type": "integer",
              "description": "Only return photos taken on/before this UNIX millisecond timestamp (UTC)."
            }
          },
          "required": ["bucket_id"]
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        val uploader = PhotoAssetUploader(context, mapper)
        val bucketId = args.path("bucket_id").asText("").trim()
        require(bucketId.isNotEmpty()) { "bucket_id is required" }
        val requestedLimit = args.path("limit").asInt(DEFAULT_LIMIT).coerceAtLeast(1)
        val limit = requestedLimit.coerceAtMost(MAX_RESULTS_PER_CALL)
        val offset = args.path("offset").asInt(0).coerceAtLeast(0)
        val maxDim = args.path("max_dim").asInt(DEFAULT_MAX_DIM).coerceIn(MIN_MAX_DIM, MAX_MAX_DIM)
        val dateAfter = args.path("date_after").let { if (it.isNumber) it.asLong() else null }
        val dateBefore = args.path("date_before").let { if (it.isNumber) it.asLong() else null }
        val queryLimit = (limit + 1).coerceAtMost(MAX_RESULTS_PER_CALL + 1)

        val clauses = mutableListOf("${MediaStore.Images.Media.BUCKET_ID} = ?")
        val params = mutableListOf(bucketId)
        if (dateAfter != null) {
            clauses += "${MediaStore.Images.Media.DATE_TAKEN} >= ?"
            params += dateAfter.toString()
        }
        if (dateBefore != null) {
            clauses += "${MediaStore.Images.Media.DATE_TAKEN} <= ?"
            params += dateBefore.toString()
        }
        val selection = clauses.joinToString(" AND ")
        val selectionArgs = params.toTypedArray()

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
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
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
        result.put("bucket_id", bucketId)
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
            result.set<ObjectNode>("next_args", nextArgs(bucketId, limit, offset + photos.size(), maxDim, dateAfter, dateBefore))
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
                set<ObjectNode>("next_args", nextArgs(bucketId, limit, offset + photos.size(), maxDim, dateAfter, dateBefore))
            }
        })
        result.set<ObjectNode>("display", mapper.createObjectNode().apply {
            put("policy", "show_grid")
            put("page", if (photos.size() == 0) "0" else "${offset + 1}-${offset + photos.size()}")
            put("has_more", hasMore)
            if (hasMore) put("next_offset", offset + photos.size())
        })
        result.set<ObjectNode>("summary", mapper.createObjectNode().apply {
            put("bucket_id", bucketId)
            put("count", photos.size())
            put("requested_limit", requestedLimit)
            put("limit", limit)
            put("max_dim", maxDim)
            put("offset", offset)
            put("has_more", hasMore)
            put("truncated_by_safety_cap", requestedLimit > limit)
            if (requestedLimit > limit) put("safety_cap", MAX_RESULTS_PER_CALL)
            if (hasMore) put("next_offset", offset + photos.size())
            if (dateAfter != null) put("date_after", dateAfter)
            if (dateBefore != null) put("date_before", dateBefore)
        })
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
        limit: Int,
        offset: Int,
        maxDim: Int,
        dateAfter: Long?,
        dateBefore: Long?
    ): ObjectNode {
        return mapper.createObjectNode().apply {
            put("bucket_id", bucketId)
            put("limit", limit)
            put("offset", offset)
            put("max_dim", maxDim)
            if (dateAfter != null) put("date_after", dateAfter)
            if (dateBefore != null) put("date_before", dateBefore)
        }
    }

    companion object {
        private const val TAG = "PhotosListByAlbumTool"
        private const val DEFAULT_LIMIT = 12
        private const val MAX_RESULTS_PER_CALL = 200
        private const val DEFAULT_MAX_DIM = 1024
        private const val MIN_MAX_DIM = 512
        private const val MAX_MAX_DIM = 2048
    }
}
