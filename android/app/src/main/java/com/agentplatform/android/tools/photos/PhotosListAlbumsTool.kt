package com.agentplatform.android.tools.photos

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
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
 * Lists buckets (albums) on the device — Camera, Screenshots, WeChat, etc. —
 * with photo count and a 256x256 cover thumbnail per bucket. Pairs with
 * {@code photos.list_by_album} for "show me what's in album X".
 */
class PhotosListAlbumsTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {

    override val name: String = "photos.list_albums"

    override val description: String = """
        列出设备相册分组(BUCKET_DISPLAY_NAME 去重),按最近修改排序,每个相册附最近一张图的缩略图作为封面。
        当用户问"我有哪些相册/微信相册有多少张/截图相册"等分组级问题时使用。

        返回 bucket_id 可以传给 photos.list_by_album 拉具体相册的图。
        默认 limit=30,最多 100。
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "limit": {
              "type": "integer",
              "minimum": 1,
              "maximum": 100,
              "default": 30,
              "description": "Max albums to return."
            }
          }
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        val limit = args.path("limit").asInt(30).coerceIn(1, 100)

        // Aggregate per bucket — MediaStore has no real GROUP BY surface, so we
        // walk DATE_TAKEN-DESC and accumulate the first hit per bucket as cover,
        // counting hits per bucket along the way.
        data class Album(
            val bucketId: String,
            var name: String,
            var coverId: Long,
            var coverDate: Long,
            var latestDate: Long,
            var count: Int,
        )
        val byBucket = LinkedHashMap<String, Album>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )

        cursor?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bIdIdx = c.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
            val bNameIdx = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val modIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                if (bIdIdx < 0 || c.isNull(bIdIdx)) continue
                val bucketId = c.getString(bIdIdx) ?: continue
                val bucketName = if (bNameIdx >= 0) c.getString(bNameIdx) ?: "(未命名)" else "(未命名)"
                val date = if (dateIdx >= 0 && !c.isNull(dateIdx)) c.getLong(dateIdx) else 0L
                val modSec = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else 0L
                val id = c.getLong(idIdx)

                val existing = byBucket[bucketId]
                if (existing == null) {
                    byBucket[bucketId] = Album(
                        bucketId = bucketId,
                        name = bucketName,
                        coverId = id,
                        coverDate = date,
                        latestDate = maxOf(date, modSec * 1000L),
                        count = 1
                    )
                } else {
                    existing.count += 1
                    val lm = maxOf(date, modSec * 1000L)
                    if (lm > existing.latestDate) existing.latestDate = lm
                    if (date > existing.coverDate) {
                        existing.coverId = id
                        existing.coverDate = date
                    }
                }
            }
        }

        // Sort by latestDate DESC, take limit.
        val ordered = byBucket.values.sortedByDescending { it.latestDate }.take(limit)

        val albums: ArrayNode = mapper.createArrayNode()
        for (a in ordered) {
            val obj: ObjectNode = mapper.createObjectNode()
            obj.put("bucket_id", a.bucketId)
            obj.put("name", a.name)
            obj.put("photo_count", a.count)
            obj.put("latest_date_ms", a.latestDate)

            val coverUri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, a.coverId
            )
            val coverB64: String = try {
                val bmp: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(coverUri, Size(256, 256), null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Thumbnails.getThumbnail(
                        context.contentResolver, a.coverId,
                        MediaStore.Images.Thumbnails.MINI_KIND, null
                    ) ?: throw RuntimeException("no thumb")
                }
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.w(TAG, "cover thumb failed for bucket=${a.bucketId}: ${e.message}")
                ""
            }
            obj.put("cover_thumb_b64", coverB64)
            albums.add(obj)
        }

        val result: ObjectNode = mapper.createObjectNode()
        result.set<JsonNode>("albums", albums)
        result.put("count", albums.size())
        result
    }

    companion object {
        private const val TAG = "PhotosListAlbumsTool"
    }
}
