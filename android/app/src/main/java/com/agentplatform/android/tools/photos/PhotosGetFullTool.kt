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
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Returns a larger thumbnail (default 1024px, max 2048) of a single photo as
 * inline JPEG base64 — for "I want to actually see this picture" follow-ups
 * after photos.list_recent narrowed down to one image. Payload can reach
 * several hundred KB, so the LLM should call this surgically (one at a time),
 * not as a default browse path.
 */
class PhotosGetFullTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {

    override val name: String = "photos.get_full"

    override val description: String = """
        拉取单张照片的放大缩略图(默认 1024px 边长 JPEG quality 80)用于看清细节。
        仅当用户明确要求"看清楚某张/这张是什么/放大看"时使用。返回 thumb_b64 字段(可能 200-800KB)。
        注:不返回原图,原图请用旁路 upload(尚未实现)。

        `id` 来自 photos.list_recent 等的返回值。`max_dim` 默认 1024,最大 2048(超过会撑爆 WS frame)。
        一次只调一张,不要批量调。
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "id": {
              "type": "string",
              "description": "Photo id from photos.list_recent / photos.list_by_album."
            },
            "max_dim": {
              "type": "integer",
              "minimum": 256,
              "maximum": 2048,
              "default": 1024,
              "description": "Max edge length in pixels for the returned thumbnail."
            }
          },
          "required": ["id"]
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        val idStr = args.path("id").asText("").trim()
        val id = idStr.toLongOrNull()
            ?: throw IllegalArgumentException("invalid id: $idStr")
        val maxDim = args.path("max_dim").asInt(1024).coerceIn(256, 2048)

        val result: ObjectNode = mapper.createObjectNode()
        result.put("id", idStr)

        // Fetch lightweight columns alongside the bitmap so the LLM has context.
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME).takeIf { it >= 0 }
                    ?.let { result.put("name", c.getString(it) ?: "image_$id") }
                c.getColumnIndex(MediaStore.Images.Media.WIDTH).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { result.put("width", c.getInt(it)) }
                c.getColumnIndex(MediaStore.Images.Media.HEIGHT).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { result.put("height", c.getInt(it)) }
            }
        }

        try {
            val bmp: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(maxDim, maxDim), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    context.contentResolver, id,
                    MediaStore.Images.Thumbnails.MINI_KIND, null
                ) ?: throw RuntimeException("no thumb")
            }
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val bytes = baos.toByteArray()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            result.put("thumb_b64", b64)
            result.put("b64_bytes", bytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail generation failed for id=$id: ${e.message}")
            result.put("thumb_b64", "")
            result.put("b64_bytes", 0)
            result.put("error", e.message ?: "thumb failed")
        }

        result
    }

    companion object {
        private const val TAG = "PhotosGetFullTool"
    }
}
