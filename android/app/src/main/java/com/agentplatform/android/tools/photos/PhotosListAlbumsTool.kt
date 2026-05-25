package com.agentplatform.android.tools.photos

import android.content.Context
import android.database.Cursor
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
        val albums = albumArray(loadAlbums(limit), uploader)

        val result: ObjectNode = mapper.createObjectNode()
        result.set<JsonNode>("albums", albums)
        result.put("count", albums.size())
        result
    }

    private fun loadAlbums(limit: Int): List<Album> {
        val byBucket = LinkedHashMap<String, Album>()
        queryAlbums()?.use { cursor ->
            val columns = albumColumns(cursor)
            while (cursor.moveToNext()) {
                readAlbumRow(cursor, columns)?.let { mergeAlbumRow(byBucket, it) }
            }
        }
        return byBucket.values.sortedByDescending { it.latestDate }.take(limit)
    }

    private fun queryAlbums(): Cursor? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE
        )
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )
    }

    private fun albumColumns(cursor: Cursor) = AlbumColumns(
        id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID),
        bucketId = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID),
        bucketName = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
        dateTaken = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN),
        dateModified = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED),
        size = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
    )

    private fun readAlbumRow(cursor: Cursor, columns: AlbumColumns): AlbumRow? {
        val bucketId = stringOrNull(cursor, columns.bucketId) ?: return null
        return AlbumRow(
            bucketId = bucketId,
            name = stringOrNull(cursor, columns.bucketName) ?: "(未命名)",
            cover = CoverPhoto(
                id = cursor.getLong(columns.id),
                date = longOrZero(cursor, columns.dateTaken),
                sizeBytes = longOrZero(cursor, columns.size),
                modifiedSec = longOrZero(cursor, columns.dateModified)
            )
        )
    }

    private fun mergeAlbumRow(albums: MutableMap<String, Album>, row: AlbumRow) {
        val existing = albums[row.bucketId]
        if (existing == null) {
            albums[row.bucketId] = row.toAlbum()
        } else {
            existing.add(row)
        }
    }

    private fun albumArray(albums: List<Album>, uploader: PhotoAssetUploader): ArrayNode {
        val array = mapper.createArrayNode()
        albums.forEach { array.add(albumNode(it, uploader)) }
        return array
    }

    private fun albumNode(album: Album, uploader: PhotoAssetUploader): ObjectNode {
        return mapper.createObjectNode().apply {
            put("bucket_id", album.bucketId)
            put("name", album.name)
            put("photo_count", album.count)
            put("latest_date_ms", album.latestDate)
            addCoverFields(this, album, uploader)
        }
    }

    private fun addCoverFields(node: ObjectNode, album: Album, uploader: PhotoAssetUploader) {
        val cover = loadCover(album) ?: return
        val upload = uploader.uploadDisplayJpeg(album.cover.id, album.name, cover)
        PhotoAssetUploader.putUploadFields(
            node,
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

    private fun loadCover(album: Album): PhotoToolUtils.EncodedPhoto? {
        return try {
            PhotoToolUtils.encodedDisplayPhoto(
                context = context,
                id = album.cover.id,
                maxDim = 2048,
                quality = 85,
                sourceModifiedSec = album.cover.modifiedSec,
                sourceSizeBytes = album.cover.sizeBytes
            )
        } catch (e: Exception) {
            Log.w(TAG, "cover image failed for bucket=${album.bucketId}: ${e.message}")
            null
        }
    }

    private fun stringOrNull(cursor: Cursor, index: Int): String? {
        return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
    }

    private fun longOrZero(cursor: Cursor, index: Int): Long {
        return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
    }

    private data class AlbumColumns(
        val id: Int,
        val bucketId: Int,
        val bucketName: Int,
        val dateTaken: Int,
        val dateModified: Int,
        val size: Int
    )

    private data class CoverPhoto(
        val id: Long,
        val date: Long,
        val sizeBytes: Long,
        val modifiedSec: Long
    ) {
        val latestDate: Long
            get() = maxOf(date, modifiedSec * 1000L)
    }

    private data class AlbumRow(
        val bucketId: String,
        val name: String,
        val cover: CoverPhoto
    ) {
        fun toAlbum() = Album(
            bucketId = bucketId,
            name = name,
            cover = cover,
            latestDate = cover.latestDate,
            count = 1
        )
    }

    private data class Album(
        val bucketId: String,
        val name: String,
        var cover: CoverPhoto,
        var latestDate: Long,
        var count: Int
    ) {
        fun add(row: AlbumRow) {
            count += 1
            latestDate = maxOf(latestDate, row.cover.latestDate)
            if (row.cover.date > cover.date) {
                cover = row.cover
            }
        }
    }

    companion object {
        private const val TAG = "PhotosListAlbumsTool"
    }
}
