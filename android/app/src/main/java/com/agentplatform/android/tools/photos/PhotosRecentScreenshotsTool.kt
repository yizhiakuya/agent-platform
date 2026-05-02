package com.agentplatform.android.tools.photos

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import com.agentplatform.android.core.tool.Tool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Lists recent screenshots, matching either the typical filename pattern
 * (Screenshot_*) or the Screenshots / 截图 bucket — both because Chinese ROMs
 * use varied conventions. Returns the same shape as photos.list_recent.
 */
class PhotosRecentScreenshotsTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {

    override val name: String = "photos.recent_screenshots"

    override val description: String = """
        专门列最近截图(文件名以 Screenshot 开头或在 Screenshots 相册)。
        比通用 photos.list_recent 加 name_contains:"Screenshot" 更直观且可靠 —
        中文 ROM 文件名格式可能不一致,这里同时按文件名前缀和相册名兜底过滤。
        当用户提到"截图/Screenshot/小黑盒截图"等场景使用。

        `name_contains` 可选,在已经匹配截图基础上再做包含过滤(例如 "xiaoheihe" 找小黑盒的截图)。
        默认 limit=10,最多 50。
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
              "default": 10,
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
        val limit = args.path("limit").asInt(10).coerceIn(1, 50)
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
            val sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE)
            while (c.moveToNext() && photos.size() < limit) {
                val id = c.getLong(idIdx)
                val name = c.getString(nameIdx) ?: "image_$id"
                val date = if (dateIdx >= 0 && !c.isNull(dateIdx)) c.getLong(dateIdx) else 0L
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L

                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val thumbB64: String = try {
                    val bmp: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Thumbnails.getThumbnail(
                            context.contentResolver, id,
                            MediaStore.Images.Thumbnails.MINI_KIND, null
                        ) ?: throw RuntimeException("no thumb")
                    }
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                    Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                } catch (e: Exception) {
                    Log.w(TAG, "thumbnail generation failed for id=$id: ${e.message}")
                    ""
                }

                val photo: ObjectNode = mapper.createObjectNode()
                photo.put("id", id.toString())
                photo.put("name", name)
                photo.put("date_taken_ms", date)
                photo.put("size_bytes", size)
                photo.put("thumb_b64", thumbB64)
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
