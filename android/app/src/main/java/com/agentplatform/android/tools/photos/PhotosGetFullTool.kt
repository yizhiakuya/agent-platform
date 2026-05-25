package com.agentplatform.android.tools.photos

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetch one photo as a display-sized original-image JPEG asset. The encoded
 * image is cached locally, so repeated access to the same MediaStore item
 * avoids another original decode and JPEG encode.
 */
class PhotosGetFullTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {

    override val name: String = "photos.get_full"

    override val description: String = """
        Fetch one photo as a display-sized original JPEG asset URL (up to 2048px
        on the long edge), using the phone-side cache when available. Use after
        a list-style tool gave you a specific `id`. Returns image_url metadata
        for the non-thumbnail image. Do not echo binary data in replies.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "id": {
              "type": "string",
              "description": "Photo id from photos.list_recent / photos.list_by_album / photos.recent_screenshots."
            },
            "max_dim": {
              "type": "integer",
              "minimum": 512,
              "maximum": 2048,
              "default": 2048
            }
          },
          "required": ["id"]
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        val uploader = PhotoAssetUploader(context, mapper)
        val idStr = args.path("id").asText("").trim()
        val id = idStr.toLongOrNull()
            ?: throw IllegalArgumentException("invalid id: $idStr")
        val maxDim = args.path("max_dim").asInt(2048).coerceIn(512, 2048)
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

        val result: ObjectNode = mapper.createObjectNode()
        result.put("id", idStr)

        var modifiedSec = 0L
        var sizeBytes = 0L
        var name = "image_$id"
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
            Log.w(TAG, "photo fetch failed for id=$id: ${e.message}", e)
            result.put("error", e.message ?: "decode failed")
            result.set<ObjectNode>("error_detail", mapper.createObjectNode().apply {
                put("code", "photo_decode_failed")
                put("message", e.message ?: "decode failed")
                put("retryable", true)
            })
        }
        result.set<ObjectNode>("summary", mapper.createObjectNode().apply {
            put("id", idStr)
            put("max_dim", maxDim)
            put("has_image", result.has("image_url") || result.has("image_b64") || result.has("vision_b64"))
        })
        ToolResultEnvelope.applyStandardFields(
            mapper = mapper,
            tool = this@PhotosGetFullTool,
            result = result,
            ok = !result.has("error"),
            resultType = "confirmed",
            displayPolicy = "show_primary",
            request = args
        )
    }

    companion object {
        private const val TAG = "PhotosGetFullTool"
    }
}
