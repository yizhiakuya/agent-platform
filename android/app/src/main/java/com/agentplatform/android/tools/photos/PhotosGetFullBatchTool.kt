package com.agentplatform.android.tools.photos

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetch several display-sized photo assets in one device round trip. The
 * server-side agent can attach all returned image_url entries from this single
 * tool result to one vision message.
 */
class PhotosGetFullBatchTool(
    private val context: Context,
    private val mapper: ObjectMapper
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

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
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

        var modifiedSec = 0L
        var sizeBytes = 0L
        var name = "image_$id"
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )

        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME).takeIf { it >= 0 }
                    ?.let {
                        name = c.getString(it) ?: "image_$id"
                        result.put("name", name)
                    }
                c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let {
                        modifiedSec = c.getLong(it)
                        result.put("date_modified_sec", modifiedSec)
                    }
                c.getColumnIndex(MediaStore.Images.Media.SIZE).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let {
                        sizeBytes = c.getLong(it)
                        result.put("size_bytes", sizeBytes)
                    }
                c.getColumnIndex(MediaStore.Images.Media.WIDTH).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { result.put("source_width", c.getInt(it)) }
                c.getColumnIndex(MediaStore.Images.Media.HEIGHT).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { result.put("source_height", c.getInt(it)) }
            }
        }

        try {
            val image = PhotoToolUtils.encodedDisplayPhoto(
                context = context,
                id = id,
                maxDim = maxDim,
                quality = 85,
                sourceModifiedSec = modifiedSec,
                sourceSizeBytes = sizeBytes
            )
            val upload = uploader.uploadDisplayJpeg(id, name, image)
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

    companion object {
        private const val TAG = "PhotosGetFullBatchTool"
        private const val MAX_BATCH = 8
    }
}
