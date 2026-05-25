package com.agentplatform.android.tools.photos

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import android.util.Log
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetch several display-sized photo assets in one device round trip. The
 * server-side agent can attach all returned image_url entries from this single
 * tool result to one vision message.
 */
class PhotosGetFullBatchTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {

    override val name: String = "photos.get_full_batch"

    override val description: String = """
        Fetch multiple photos as display-sized original JPEG asset URLs in one
        call. Use this when a search or list tool returned several photo ids
        and you need to visually inspect them together. Pass at most 8 ids.
        Do not echo binary data in replies.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "ids": {
              "type": "array",
              "items": { "type": "string" },
              "minItems": 1,
              "maxItems": 8,
              "description": "Photo ids from photos.semantic_search / photos.list_recent / photos.list_by_album."
            },
            "max_dim": {
              "type": "integer",
              "minimum": 512,
              "maximum": 2048,
              "default": 1024
            }
          },
          "required": ["ids"]
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        val idsNode = args.path("ids")
        require(idsNode.isArray && idsNode.size() > 0) { "ids must be a non-empty array" }
        require(idsNode.size() <= MAX_BATCH) { "at most $MAX_BATCH photos can be fetched at once" }

        val requestedIds = idsNode.mapNotNull { it.asText("").trim().takeIf(String::isNotBlank) }
            .distinct()
        require(requestedIds.isNotEmpty()) { "ids must contain at least one valid id" }
        require(requestedIds.size <= MAX_BATCH) { "at most $MAX_BATCH unique photos can be fetched at once" }

        val maxDim = args.path("max_dim").asInt(1024).coerceIn(512, 2048)
        val uploader = PhotoAssetUploader(context, mapper)
        val photos = mapper.createArrayNode()
        var okCount = 0

        requestedIds.forEachIndexed { index, idStr ->
            val photo = fetchOne(
                idStr = idStr,
                index = index,
                maxDim = maxDim,
                uploader = uploader
            )
            if (!photo.has("error")) okCount++
            photos.add(photo)
        }

        val result = mapper.createObjectNode()
        result.set<ArrayNode>("photos", photos)
        result.set<ArrayNode>("items", photos.deepCopy())
        result.set<ArrayNode>("ids", mapper.valueToTree(requestedIds))
        result.put("requested_count", requestedIds.size)
        result.put("count", okCount)
        result.put("max_dim", maxDim)
        result.set<ArrayNode>("display_media", displayMediaFor(photos))
        result.set<ObjectNode>("summary", mapper.createObjectNode().apply {
            put("requested_count", requestedIds.size)
            put("count", okCount)
            put("max_dim", maxDim)
            put("has_images", okCount > 0)
        })
        ToolResultEnvelope.applyStandardFields(
            mapper = mapper,
            tool = this@PhotosGetFullBatchTool,
            result = result,
            ok = okCount == requestedIds.size,
            resultType = "confirmed",
            displayPolicy = "show_grid",
            request = args
        )
    }

    private fun displayMediaFor(photos: ArrayNode): ArrayNode {
        val media = mapper.createArrayNode()
        photos.forEach { node ->
            if (!node.isObject || node.has("error")) return@forEach
            val photo = node as ObjectNode
            media.add(mapper.createObjectNode().apply {
                put("kind", "image")
                put("id", photo.path("id").asText(""))
                put("media_store_id", photo.path("id").asText(""))
                if (photo.hasNonNull("name")) set<JsonNode>("name", photo.get("name"))
                if (photo.hasNonNull("image_url")) set<JsonNode>("image_url", photo.get("image_url"))
                if (photo.hasNonNull("thumb_url")) set<JsonNode>("preview_url", photo.get("thumb_url"))
                if (photo.hasNonNull("source_width")) set<JsonNode>("width", photo.get("source_width"))
                if (photo.hasNonNull("source_height")) set<JsonNode>("height", photo.get("source_height"))
                put("open_tool", "photos.get_full")
                set<ObjectNode>("open_args", mapper.createObjectNode().apply {
                    put("id", photo.path("id").asText(""))
                    put("max_dim", 2048)
                })
            })
        }
        return media
    }

    private fun fetchOne(
        idStr: String,
        index: Int,
        maxDim: Int,
        uploader: PhotoAssetUploader
    ): ObjectNode {
        val result = mapper.createObjectNode()
        result.put("id", idStr)
        result.put("index", index)

        val id = idStr.toLongOrNull()
        if (id == null) {
            return result.withError("invalid id: $idStr", "invalid_id")
        }

        val metadata = readPhotoMetadata(id, result)
        return uploadPhoto(id, maxDim, metadata, uploader, result)
    }

    private fun readPhotoMetadata(id: Long, result: ObjectNode): PhotoMetadata {
        var metadata = PhotoMetadata.default(id)
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        context.contentResolver.query(uri, PHOTO_PROJECTION, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) metadata = cursor.readPhotoMetadata(id, result)
        }
        return metadata
    }

    private fun Cursor.readPhotoMetadata(id: Long, result: ObjectNode): PhotoMetadata {
        val name = putStringField(MediaStore.Images.Media.DISPLAY_NAME, "name", result) ?: "image_$id"
        val modifiedSec = putLongField(MediaStore.Images.Media.DATE_MODIFIED, "date_modified_sec", result)
        val sizeBytes = putLongField(MediaStore.Images.Media.SIZE, "size_bytes", result)
        putIntField(MediaStore.Images.Media.WIDTH, "source_width", result)
        putIntField(MediaStore.Images.Media.HEIGHT, "source_height", result)
        return PhotoMetadata(
            name = name,
            modifiedSec = modifiedSec ?: 0L,
            sizeBytes = sizeBytes ?: 0L
        )
    }

    private fun Cursor.putStringField(column: String, field: String, result: ObjectNode): String? =
        columnIndex(column).takeIf { it >= 0 }
            ?.let { getString(it) }
            ?.also { result.put(field, it) }

    private fun Cursor.putLongField(column: String, field: String, result: ObjectNode): Long? =
        columnIndex(column).takeIf { it >= 0 && !isNull(it) }
            ?.let { getLong(it) }
            ?.also { result.put(field, it) }

    private fun Cursor.putIntField(column: String, field: String, result: ObjectNode) {
        columnIndex(column).takeIf { it >= 0 && !isNull(it) }
            ?.let { result.put(field, getInt(it)) }
    }

    private fun Cursor.columnIndex(column: String): Int = getColumnIndex(column)

    private fun uploadPhoto(
        id: Long,
        maxDim: Int,
        metadata: PhotoMetadata,
        uploader: PhotoAssetUploader,
        result: ObjectNode
    ): ObjectNode {
        try {
            val image = PhotoToolUtils.encodedDisplayPhoto(
                context = context,
                id = id,
                maxDim = maxDim,
                quality = 85,
                sourceModifiedSec = metadata.modifiedSec,
                sourceSizeBytes = metadata.sizeBytes
            )
            val upload = uploader.uploadDisplayJpeg(id, metadata.name, image)
            PhotoAssetUploader.putUploadFields(result, upload, image)
        } catch (e: Exception) {
            Log.w(TAG, "photo batch fetch failed for id=$id: ${e.message}", e)
            return result.withError(e.message ?: "decode failed", "photo_decode_failed")
        }
        return result
    }

    private fun ObjectNode.withError(message: String, code: String): ObjectNode {
        put("error", message)
        set<ObjectNode>("error_detail", mapper.createObjectNode().apply {
            put("code", code)
            put("message", message)
            put("retryable", code == "photo_decode_failed")
        })
        return this
    }

    private data class PhotoMetadata(
        val name: String,
        val modifiedSec: Long,
        val sizeBytes: Long
    ) {
        companion object {
            fun default(id: Long): PhotoMetadata = PhotoMetadata(
                name = "image_$id",
                modifiedSec = 0L,
                sizeBytes = 0L
            )
        }
    }

    companion object {
        private const val TAG = "PhotosGetFullBatchTool"
        private const val MAX_BATCH = 8
        private val PHOTO_PROJECTION = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
    }
}
