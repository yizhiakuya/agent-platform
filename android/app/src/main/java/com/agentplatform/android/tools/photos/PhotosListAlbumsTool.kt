package com.agentplatform.android.tools.photos

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.agentplatform.android.core.tool.Tool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists buckets (albums) on the device — Camera, Screenshots, WeChat, etc. —
 * with photo count and a cached display-sized original cover image. Pairs with
 * {@code photos.list_by_album} for "show me what's in album X".
 */
class PhotosListAlbumsTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {

    override val name: String = "photos.list_albums"

    override val description: String = """
        List device photo albums/buckets. Each album includes its latest photo
        as a cached display-sized original JPEG cover asset URL, not a thumbnail.
        Return bucket_id values for photos.list_by_album. Default limit is 30;
        cap is 100.
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

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        val uploader = PhotoAssetUploader(context, mapper)
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
            var coverSizeBytes: Long,
            var coverModifiedSec: Long,
        )
        val byBucket = LinkedHashMap<String, Album>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE
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
            val sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE)
            while (c.moveToNext()) {
                if (bIdIdx < 0 || c.isNull(bIdIdx)) continue
                val bucketId = c.getString(bIdIdx) ?: continue
                val bucketName = if (bNameIdx >= 0) c.getString(bNameIdx) ?: "(未命名)" else "(未命名)"
                val date = if (dateIdx >= 0 && !c.isNull(dateIdx)) c.getLong(dateIdx) else 0L
                val modSec = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else 0L
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                val id = c.getLong(idIdx)

                val existing = byBucket[bucketId]
                if (existing == null) {
                    byBucket[bucketId] = Album(
                        bucketId = bucketId,
                        name = bucketName,
                        coverId = id,
                        coverDate = date,
                        latestDate = maxOf(date, modSec * 1000L),
                        count = 1,
                        coverSizeBytes = size,
                        coverModifiedSec = modSec
                    )
                } else {
                    existing.count += 1
                    val lm = maxOf(date, modSec * 1000L)
                    if (lm > existing.latestDate) existing.latestDate = lm
                    if (date > existing.coverDate) {
                        existing.coverId = id
                        existing.coverDate = date
                        existing.coverSizeBytes = size
                        existing.coverModifiedSec = modSec
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

            val cover = try {
                PhotoToolUtils.encodedDisplayPhoto(
                    context = context,
                    id = a.coverId,
                    maxDim = 2048,
                    quality = 85,
                    sourceModifiedSec = a.coverModifiedSec,
                    sourceSizeBytes = a.coverSizeBytes
                )
            } catch (e: Exception) {
                Log.w(TAG, "cover image failed for bucket=${a.bucketId}: ${e.message}")
                null
            }
            if (cover != null) {
                val upload = uploader.uploadDisplayJpeg(a.coverId, a.name, cover)
                PhotoAssetUploader.putUploadFields(
                    obj,
                    upload,
                    cover,
                    imageUrlField = "cover_image_url",
                    assetIdField = "cover_asset_id",
                    contentTypeField = "cover_content_type",
                    bytesField = "cover_image_bytes",
                    widthField = "cover_image_width",
                    heightField = "cover_image_height",
                    cacheHitField = "cover_image_cache_hit",
                    assetCacheHitField = "cover_asset_cache_hit",
                    errorField = "cover_upload_error"
                )
            }
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
