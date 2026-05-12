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
 * Lists recent screenshots, matching either the typical filename pattern
 * (Screenshot_*) or the Screenshots / 截图 bucket — both because Chinese ROMs
 * use varied conventions. Returns cached display-sized original images.
 */
class PhotosRecentScreenshotsTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {

    override val name: String = "photos.recent_screenshots"

    override val description: String = """
        List recent screenshots as cached display-sized original JPEG images
        (base64, up to 2048px long edge). Matches common Screenshot filenames
        and Screenshots albums across Android ROMs. Optional name_contains
        narrows within screenshots. Default limit is 6; cap is 50.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "limit": {
              "type": "integer",
              "minimum": 1,
              "maximum": 50,
              "default": 6,
              "description": "Max screenshots to return."
            },
            "name_contains": {
              "type": "string",
              "description": "Optional secondary filter on the filename within screenshots."
            }
          }
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        val limit = args.path("limit").asInt(6).coerceIn(1, 50)
        val nameContains = args.path("name_contains").asText("").trim().takeIf { it.isNotEmpty() }

        // Three-pronged screenshot heuristic for cross-vendor coverage:
        //   1. DISPLAY_NAME starts with Screenshot
        //   2. BUCKET_DISPLAY_NAME contains Screenshot (English ROM)
        //   3. BUCKET_DISPLAY_NAME contains 截图 (Chinese ROM)
        // Then optionally AND a user-supplied substring.
        val baseClause =
            "(${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? " +
            "OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ? " +
            "OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?)"
        val params = mutableListOf("Screenshot%", "%Screenshot%", "%截图%")
        var selection = baseClause
        if (nameContains != null) {
            selection = "$baseClause AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            params += "%$nameContains%"
        }
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
        result.set<JsonNode>("photos", photos)
        result.put("count", photos.size())
        result
    }

    companion object {
        private const val TAG = "PhotosRecentScreenshotsTool"
    }
}
