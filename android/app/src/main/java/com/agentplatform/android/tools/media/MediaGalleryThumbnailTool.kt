package com.agentplatform.android.tools.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.tools.photos.PhotoAssetUploader
import com.agentplatform.android.tools.photos.PhotoToolUtils
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

class MediaGalleryThumbnailTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {
    override val name: String = "media.gallery.thumbnail"

    override val description: String = """
        Fetch one gallery thumbnail as a small uploaded image asset URL. Use
        this only for lazy UI display after media.gallery.browse returned a
        media_type and id. It returns metadata and image_url, not base64.
    """.trimIndent()

    override val toolClass: String = "inspect"
    override val resultType: String = "confirmed"
    override val defaultDisplayPolicy: String = "debug_only"

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "media_type": {
              "type": "string",
              "enum": ["photo", "video"],
              "description": "Media kind from media.gallery.browse."
            },
            "id": {
              "type": "string",
              "description": "MediaStore id from media.gallery.browse."
            },
            "max_dim": {
              "type": "integer",
              "minimum": 128,
              "maximum": 640,
              "default": 256
            }
          },
          "required": ["media_type", "id"]
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        val mediaType = args.path("media_type").asText("photo").lowercase(Locale.ROOT).trim()
        val idStr = args.path("id").asText("").trim()
        val id = idStr.toLongOrNull()
            ?: throw IllegalArgumentException("invalid id: $idStr")
        val maxDim = args.path("max_dim").asInt(DEFAULT_MAX_DIM).coerceIn(MIN_MAX_DIM, MAX_MAX_DIM)
        val result: ObjectNode = mapper.createObjectNode()
        result.put("media_type", mediaType)
        result.put("id", idStr)
        result.put("max_dim", maxDim)

        try {
            val image = when (mediaType) {
                "photo" -> photoThumbnail(id, maxDim)
                "video" -> videoThumbnail(id, maxDim)
                else -> throw IllegalArgumentException("unsupported media_type: $mediaType")
            }
            val upload = PhotoAssetUploader(context, mapper)
                .uploadDisplayJpeg(id, "${mediaType}_thumb_$id.jpg", image)
            PhotoAssetUploader.putUploadFields(
                obj = result,
                upload = upload,
                image = image,
                fields = PhotoAssetUploader.UploadFields.Thumbnail
            )
            if (!result.hasNonNull("thumb_url")) {
                result.put("error", result.path("thumb_upload_error").asText("thumbnail upload failed"))
                result.set<ObjectNode>("error_detail", mapper.createObjectNode().apply {
                    put("code", "thumbnail_upload_failed")
                    put("message", result.path("error").asText())
                    put("retryable", true)
                })
            }
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail failed mediaType=$mediaType id=$id: ${e.message}", e)
            result.put("error", e.message ?: "thumbnail failed")
            result.set<ObjectNode>("error_detail", mapper.createObjectNode().apply {
                put("code", "thumbnail_failed")
                put("message", e.message ?: "thumbnail failed")
                put("retryable", true)
            })
        }

        ToolResultEnvelope.applyStandardFields(
            mapper = mapper,
            tool = this@MediaGalleryThumbnailTool,
            result = result,
            ok = !result.has("error"),
            resultType = "confirmed",
            displayPolicy = "debug_only",
            request = args
        )
    }

    private fun photoThumbnail(id: Long, maxDim: Int): PhotoToolUtils.EncodedPhoto {
        val bmp = PhotoToolUtils.loadThumbnail(context.contentResolver, id, maxDim)
        return try {
            encodeJpeg(bmp)
        } finally {
            bmp.recycle()
        }
    }

    private fun videoThumbnail(id: Long, maxDim: Int): PhotoToolUtils.EncodedPhoto {
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(maxDim, maxDim), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver,
                id,
                MediaStore.Video.Thumbnails.MINI_KIND,
                null
            ) ?: throw RuntimeException("no video thumbnail")
        }
        return try {
            encodeJpeg(bmp)
        } finally {
            bmp.recycle()
        }
    }

    private fun encodeJpeg(bitmap: Bitmap): PhotoToolUtils.EncodedPhoto {
        val bytes = ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }.toByteArray()
        return PhotoToolUtils.EncodedPhoto(
            jpegBytes = bytes,
            width = bitmap.width,
            height = bitmap.height,
            cacheHit = false
        )
    }

    companion object {
        private const val TAG = "MediaGalleryThumbnailTool"
        private const val DEFAULT_MAX_DIM = 256
        private const val MIN_MAX_DIM = 128
        private const val MAX_MAX_DIM = 640
        private const val JPEG_QUALITY = 74
    }
}
