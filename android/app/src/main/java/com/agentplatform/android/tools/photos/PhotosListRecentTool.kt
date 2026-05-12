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
    private val mapper: ObjectMapper
) : Tool {

    override val name: String = "photos.list_recent"

    override val description: String = """
        List photos from the device's gallery as cached display-sized original
        JPEG asset URLs (up to 2048px long edge). Returns the most-recent
        matching photos first.

        Always pass the narrowest filter you can — pulling 20+ unfiltered photos
        wastes the user's mobile upload bandwidth. In particular:

        - For "photos from app X" / "screenshots of X": pass `name_contains` with
          a substring of X (Android screenshots include the source package in
          the filename, e.g. "Screenshot_..._com.example.app.jpg").
        - For "photos from yesterday / last week / a date": pass `date_after` and
          optionally `date_before` (UNIX millis, UTC).
        - Only use a bare `limit` with no filters when the user explicitly asks
          for "recent photos" with no qualifier.

        `limit` caps the result count regardless of filters; cap is 50.
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
              "description": "Max photos to return."
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

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        val uploader = PhotoAssetUploader(context, mapper)
        val limit = (args.path("limit").asInt(6)).coerceIn(1, 50)
        val nameContains = args.path("name_contains").asText("").trim().takeIf { it.isNotEmpty() }
        val dateAfter = args.path("date_after").let { if (it.isNumber) it.asLong() else null }
        val dateBefore = args.path("date_before").let { if (it.isNumber) it.asLong() else null }

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
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
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
                    val upload = uploader.uploadDisplayJpeg(id, name, image)
                    PhotoAssetUploader.putUploadFields(photo, upload, image)
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
        private const val TAG = "PhotosListRecentTool"
    }
}
