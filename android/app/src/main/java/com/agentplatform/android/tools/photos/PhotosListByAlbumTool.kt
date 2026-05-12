package com.agentplatform.android.tools.photos

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.agentplatform.android.core.tool.Tool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists cached display-sized original images inside one specific album bucket.
 */
class PhotosListByAlbumTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {

    override val name: String = "photos.list_by_album"

    override val description: String = """
        List photos inside one specific album bucket as cached display-sized
        original JPEG images (base64, up to 2048px long edge). `bucket_id`
        comes from photos.list_albums. Optional date_after/date_before filter
        the album by UNIX milliseconds. Default limit is 6; cap is 50.
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
              "maximum": 50,
              "default": 6,
              "description": "Max photos to return."
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
        val bucketId = args.path("bucket_id").asText("").trim()
        require(bucketId.isNotEmpty()) { "bucket_id is required" }
        val limit = args.path("limit").asInt(6).coerceIn(1, 50)
        val dateAfter = args.path("date_after").let { if (it.isNumber) it.asLong() else null }
        val dateBefore = args.path("date_before").let { if (it.isNumber) it.asLong() else null }

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
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
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
                "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            )
        }

        cursor?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val modifiedIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE)
            while (c.moveToNext() && photos.size() < limit) {
                val id = c.getLong(idIdx)
                val name = c.getString(nameIdx) ?: "image_$id"
                val date = if (dateIdx >= 0 && !c.isNull(dateIdx)) c.getLong(dateIdx) else 0L
                val modified = if (modifiedIdx >= 0 && !c.isNull(modifiedIdx)) c.getLong(modifiedIdx) else 0L
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L

                val image = try {
                    PhotoToolUtils.encodedDisplayPhoto(
                        context = context,
                        id = id,
                        maxDim = 2048,
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
                    photo.put("image_b64", image.b64)
                    photo.put("image_bytes", image.bytes)
                    photo.put("image_width", image.width)
                    photo.put("image_height", image.height)
                    photo.put("image_cache_hit", image.cacheHit)
                }
                photos.add(photo)
            }
        }

        val result: ObjectNode = mapper.createObjectNode()
        result.put("bucket_id", bucketId)
        result.set<JsonNode>("photos", photos)
        result.put("count", photos.size())
        result
    }

    companion object {
        private const val TAG = "PhotosListByAlbumTool"
    }
}
