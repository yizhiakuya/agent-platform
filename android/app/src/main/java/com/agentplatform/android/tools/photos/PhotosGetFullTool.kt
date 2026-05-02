package com.agentplatform.android.tools.photos

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.agentplatform.android.core.tool.Tool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * High-resolution single-photo fetch — for the LLM to actually *see* a photo
 * after a list-style tool narrowed it down to one. Decodes the original
 * MediaStore image (NOT the cached MediaStore thumbnail) and scales it down
 * to {@code max_dim}. Default 2048px so vision-aware Claude can read text /
 * recognise faces / inspect UI screenshots.
 *
 * <p>Returns BOTH:
 * <ul>
 *   <li>{@code vision_b64} — the high-res JPEG. Picked up by the server-side
 *       VisionAwareToolCallingManager and injected as a multimodal
 *       tool_result image so Claude can see it. Strip-list-aware so it never
 *       leaks into the LLM's text context as base64.</li>
 *   <li>{@code thumb_b64} — a small 256px companion so the web client can
 *       still render a familiar thumbnail in the result bubble without
 *       painting a 2MB image.</li>
 * </ul>
 *
 * <p>Bypasses {@code contentResolver.loadThumbnail}, which on Android Q+
 * returns whatever cached size MediaStore happens to have (often 512px),
 * not the requested target. We open the original byte stream and decode
 * with {@code inSampleSize} so we deterministically get pixels close to
 * {@code max_dim}.
 */
class PhotosGetFullTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {

    override val name: String = "photos.get_full"

    override val description: String = """
        Fetch one photo at high resolution (up to 2048px on the long edge) so you
        can actually see what's in it — read text in a screenshot, recognise a
        face, inspect a chart. Returns `vision_b64` for the LLM to view directly
        plus a small `thumb_b64` for the UI; the LLM should NOT echo either
        string in its reply.

        Use after a list-style tool (photos.list_recent / list_by_album /
        recent_screenshots) gave you specific `id`s. One id per call — batches
        blow past WS frame limits.

        `max_dim` defaults 2048; lower it (e.g. 1024) only when you just need
        to confirm gross subject and want to save tokens.
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

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        val idStr = args.path("id").asText("").trim()
        val id = idStr.toLongOrNull()
            ?: throw IllegalArgumentException("invalid id: $idStr")
        val maxDim = args.path("max_dim").asInt(2048).coerceIn(512, 2048)
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

        val result: ObjectNode = mapper.createObjectNode()
        result.put("id", idStr)

        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME).takeIf { it >= 0 }
                    ?.let { result.put("name", c.getString(it) ?: "image_$id") }
                c.getColumnIndex(MediaStore.Images.Media.WIDTH).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { result.put("source_width", c.getInt(it)) }
                c.getColumnIndex(MediaStore.Images.Media.HEIGHT).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { result.put("source_height", c.getInt(it)) }
            }
        }

        try {
            // Pass 1: read bounds without allocating pixels.
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            val srcW = boundsOpts.outWidth
            val srcH = boundsOpts.outHeight
            if (srcW <= 0 || srcH <= 0) throw RuntimeException("invalid bounds")

            // inSampleSize must be a power of 2; pick the smallest that brings
            // both dims under maxDim*2 — then a final scale lands close to maxDim.
            var sample = 1
            while (srcW / (sample * 2) > maxDim && srcH / (sample * 2) > maxDim) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw: Bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: throw RuntimeException("decode failed")

            // Final scale to maxDim long edge.
            val longEdge = maxOf(raw.width, raw.height)
            val scaled: Bitmap = if (longEdge > maxDim) {
                val ratio = maxDim.toFloat() / longEdge
                Bitmap.createScaledBitmap(raw, (raw.width * ratio).toInt(), (raw.height * ratio).toInt(), true)
                    .also { if (it !== raw) raw.recycle() }
            } else raw

            // Honor EXIF orientation so portrait shots aren't sideways for the LLM.
            val oriented: Bitmap = applyExifOrientation(uri, scaled)

            // Vision-quality JPEG (quality 85 — vision_b64 is for the LLM).
            val visionBytes = ByteArrayOutputStream().also {
                oriented.compress(Bitmap.CompressFormat.JPEG, 85, it)
            }.toByteArray()
            val visionB64 = Base64.encodeToString(visionBytes, Base64.NO_WRAP)
            result.put("vision_b64", visionB64)
            result.put("vision_bytes", visionBytes.size)
            result.put("vision_width", oriented.width)
            result.put("vision_height", oriented.height)

            // Cheap 256px companion thumb for the web bubble.
            val thumbLong = 256
            val thumbScale = thumbLong.toFloat() / maxOf(oriented.width, oriented.height)
            val thumb = Bitmap.createScaledBitmap(
                oriented,
                (oriented.width * thumbScale).toInt().coerceAtLeast(1),
                (oriented.height * thumbScale).toInt().coerceAtLeast(1),
                true
            )
            val thumbBytes = ByteArrayOutputStream().also {
                thumb.compress(Bitmap.CompressFormat.JPEG, 70, it)
            }.toByteArray()
            result.put("thumb_b64", Base64.encodeToString(thumbBytes, Base64.NO_WRAP))

            if (thumb !== oriented) thumb.recycle()
            if (oriented !== scaled) oriented.recycle()
            if (scaled !== raw) {
                /* scaled may already equal raw when longEdge<=maxDim */
            }
        } catch (e: Exception) {
            Log.w(TAG, "high-res fetch failed for id=$id: ${e.message}", e)
            result.put("error", e.message ?: "decode failed")
        }
        result
    }

    private fun applyExifOrientation(uri: android.net.Uri, bmp: Bitmap): Bitmap {
        return try {
            val orientation = context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
            if (orientation == ExifInterface.ORIENTATION_NORMAL) return bmp
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> return bmp
            }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                .also { if (it !== bmp) bmp.recycle() }
        } catch (_: Exception) { bmp }
    }

    companion object {
        private const val TAG = "PhotosGetFullTool"
    }
}
