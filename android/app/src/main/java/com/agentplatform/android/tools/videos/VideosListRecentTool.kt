package com.agentplatform.android.tools.videos

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
 * Lists recent videos as 256x256 first-frame thumbnails plus duration / size.
 * Sibling of {@link com.agentplatform.android.tools.photos.PhotosListRecentTool}
 * but for {@code MediaStore.Video}.
 *
 * <p>Permission: requires {@code READ_MEDIA_VIDEO} (Android 13+) — distinct
 * from {@code READ_MEDIA_IMAGES}, so the user must grant it separately.
 */
class VideosListRecentTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {

    override val name: String = "videos.list_recent"

    override val description: String = """
        列出最近的视频(MP4/MOV 等)缩略图(从视频首帧抽帧),含时长、文件大小。
        当用户问"我录了什么视频/最近的视频/最近的录屏"等场景使用。

        默认 limit=10,最多 30。可选 name_contains / date_after / date_before 同 photos.list_recent。
        缩略图比照片少一些是因为视频本身更大,LLM 只看缩略图判断。要看原视频本身目前还没工具。
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "limit": {
              "type": "integer",
              "minimum": 1,
              "maximum": 30,
              "default": 10,
              "description": "Max videos to return."
            },
            "name_contains": {
              "type": "string",
              "description": "Case-insensitive substring of the filename."
            },
            "date_after": {
              "type": "integer",
              "description": "Only return videos taken on/after this UNIX millisecond timestamp (UTC)."
            },
            "date_before": {
              "type": "integer",
              "description": "Only return videos taken on/before this UNIX millisecond timestamp (UTC)."
            }
          },
          "required": ["limit"]
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        val limit = args.path("limit").asInt(10).coerceIn(1, 30)
        val nameContains = args.path("name_contains").asText("").trim().takeIf { it.isNotEmpty() }
        val dateAfter = args.path("date_after").let { if (it.isNumber) it.asLong() else null }
        val dateBefore = args.path("date_before").let { if (it.isNumber) it.asLong() else null }

        val clauses = mutableListOf<String>()
        val params = mutableListOf<String>()
        if (nameContains != null) {
            clauses += "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
            params += "%$nameContains%"
        }
        if (dateAfter != null) {
            clauses += "${MediaStore.Video.Media.DATE_TAKEN} >= ?"
            params += dateAfter.toString()
        }
        if (dateBefore != null) {
            clauses += "${MediaStore.Video.Media.DATE_TAKEN} <= ?"
            params += dateBefore.toString()
        }
        val selection = clauses.joinToString(" AND ").ifEmpty { null }
        val selectionArgs = if (params.isEmpty()) null else params.toTypedArray()

        val videos: ArrayNode = mapper.createArrayNode()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION
        )
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val args2 = Bundle().apply {
                putStringArray(
                    android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.Video.Media.DATE_TAKEN)
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
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, args2, null
            )
        } else {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs,
                "${MediaStore.Video.Media.DATE_TAKEN} DESC"
            )
        }

        cursor?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateIdx = c.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
            val sizeIdx = c.getColumnIndex(MediaStore.Video.Media.SIZE)
            val durIdx = c.getColumnIndex(MediaStore.Video.Media.DURATION)
            while (c.moveToNext() && videos.size() < limit) {
                val id = c.getLong(idIdx)
                val name = c.getString(nameIdx) ?: "video_$id"
                val date = if (dateIdx >= 0 && !c.isNull(dateIdx)) c.getLong(dateIdx) else 0L
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                val duration = if (durIdx >= 0 && !c.isNull(durIdx)) c.getLong(durIdx) else 0L

                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                val thumbB64: String = try {
                    val bmp: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Video.Thumbnails.getThumbnail(
                            context.contentResolver, id,
                            MediaStore.Video.Thumbnails.MINI_KIND, null
                        ) ?: throw RuntimeException("no thumb")
                    }
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                    Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                } catch (e: Exception) {
                    Log.w(TAG, "video thumbnail generation failed for id=$id: ${e.message}")
                    ""
                }

                val video: ObjectNode = mapper.createObjectNode()
                video.put("id", id.toString())
                video.put("name", name)
                video.put("date_taken_ms", date)
                video.put("size_bytes", size)
                video.put("duration_ms", duration)
                video.put("thumb_b64", thumbB64)
                videos.add(video)
            }
        }

        val result: ObjectNode = mapper.createObjectNode()
        result.set<JsonNode>("videos", videos)
        result.put("count", videos.size())
        result
    }

    companion object {
        private const val TAG = "VideosListRecentTool"
    }
}
