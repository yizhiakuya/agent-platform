package com.agentplatform.android.tools.videos

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
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
import kotlinx.coroutines.CoroutineDispatcher
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
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        val request = parseRequest(args)
        val videos = queryVideos(request)
        resultNode(videos)
    }

    private fun parseRequest(args: JsonNode): VideoListRequest =
        VideoListRequest(
            limit = args.path("limit").asInt(10).coerceIn(1, 30),
            nameContains = args.path("name_contains").asText("").trim().takeIf { it.isNotEmpty() },
            dateAfter = positiveLong(args, "date_after"),
            dateBefore = positiveLong(args, "date_before")
        )

    private fun positiveLong(args: JsonNode, field: String): Long? =
        args.path(field).let { if (it.isNumber && it.asLong() > 0L) it.asLong() else null }

    private fun queryVideos(request: VideoListRequest): ArrayNode {
        val videos = mapper.createArrayNode()
        videoCursor(request, videoSelection(request))?.use { cursor ->
            val columns = VideoColumns.from(cursor)
            while (cursor.moveToNext() && videos.size() < request.limit) {
                videos.add(videoNode(VideoRow.from(cursor, columns)))
            }
        }
        return videos
    }

    private fun videoCursor(request: VideoListRequest, selection: VideoSelection): Cursor? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                VIDEO_PROJECTION,
                modernQueryArgs(request, selection),
                null
            )
        } else {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                VIDEO_PROJECTION,
                selection.sql,
                selection.args,
                "${MediaStore.Video.Media.DATE_TAKEN} DESC"
            )
        }
    }

    private fun modernQueryArgs(request: VideoListRequest, selection: VideoSelection): Bundle =
        Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Video.Media.DATE_TAKEN))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, request.limit)
            selection.sql?.let {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selection.args)
            }
        }

    private fun videoSelection(request: VideoListRequest): VideoSelection {
        val filters = listOf(
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?" to request.nameContains?.let { "%$it%" },
            "${MediaStore.Video.Media.DATE_TAKEN} >= ?" to request.dateAfter?.toString(),
            "${MediaStore.Video.Media.DATE_TAKEN} <= ?" to request.dateBefore?.toString()
        ).mapNotNull { (clause, value) -> value?.let { clause to it } }
        return VideoSelection(
            sql = filters.joinToString(" AND ") { it.first }.ifEmpty { null },
            args = filters.map { it.second }.takeIf { it.isNotEmpty() }?.toTypedArray()
        )
    }

    private fun videoNode(row: VideoRow): ObjectNode {
        val video = mapper.createObjectNode()
        video.put("id", row.id.toString())
        video.put("name", row.name)
        video.put("date_taken_ms", row.dateTakenMs)
        video.put("size_bytes", row.sizeBytes)
        video.put("duration_ms", row.durationMs)
        video.put("thumb_b64", thumbnailBase64(row.id))
        return video
    }

    private fun thumbnailBase64(id: Long): String =
        try {
            val bitmap = videoThumbnail(id)
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "video thumbnail generation failed for id=$id: ${e.message}")
            ""
        }

    private fun videoThumbnail(id: Long): Bitmap {
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver,
                id,
                MediaStore.Video.Thumbnails.MINI_KIND,
                null
            ) ?: throw RuntimeException("no thumb")
        }
    }

    private fun resultNode(videos: ArrayNode): ObjectNode {
        val result: ObjectNode = mapper.createObjectNode()
        result.set<JsonNode>("videos", videos)
        result.put("count", videos.size())
        return result
    }

    companion object {
        private const val TAG = "VideosListRecentTool"

        private val VIDEO_PROJECTION = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION
        )
    }

    private data class VideoListRequest(
        val limit: Int,
        val nameContains: String?,
        val dateAfter: Long?,
        val dateBefore: Long?
    )

    private class VideoSelection(
        val sql: String?,
        val args: Array<String>?
    )

    private data class VideoColumns(
        val id: Int,
        val name: Int,
        val date: Int,
        val size: Int,
        val duration: Int
    ) {
        companion object {
            fun from(cursor: Cursor): VideoColumns =
                VideoColumns(
                    id = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID),
                    name = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME),
                    date = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN),
                    size = cursor.getColumnIndex(MediaStore.Video.Media.SIZE),
                    duration = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                )
        }
    }

    private data class VideoRow(
        val id: Long,
        val name: String,
        val dateTakenMs: Long,
        val sizeBytes: Long,
        val durationMs: Long
    ) {
        companion object {
            fun from(cursor: Cursor, columns: VideoColumns): VideoRow {
                val id = cursor.getLong(columns.id)
                return VideoRow(
                    id = id,
                    name = cursor.getString(columns.name) ?: "video_$id",
                    dateTakenMs = optionalLong(cursor, columns.date),
                    sizeBytes = optionalLong(cursor, columns.size),
                    durationMs = optionalLong(cursor, columns.duration)
                )
            }

            private fun optionalLong(cursor: Cursor, column: Int): Long =
                if (column >= 0 && !cursor.isNull(column)) cursor.getLong(column) else 0L
        }
    }
}
