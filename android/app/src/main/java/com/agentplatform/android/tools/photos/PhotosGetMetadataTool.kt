package com.agentplatform.android.tools.photos

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.agentplatform.android.core.tool.Tool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads full EXIF / GPS / device metadata for one specific photo. Does NOT
 * return image bytes — pair it with {@code photos.list_recent} or
 * {@code photos.get_full} when the user wants to see the picture as well.
 */
class PhotosGetMetadataTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {

    override val name: String = "photos.get_metadata"

    override val description: String = """
        读取单张照片的完整元数据 — EXIF(拍摄时间/焦距/ISO/快门/设备型号)、GPS 坐标(若开启位置)、
        文件大小/分辨率/方向。当用户问"这张照片是哪儿拍的/什么时候/什么相机/参数"等具体信息时使用。
        不返回缩略图,只要图问 photos.list_recent 或 photos.get_full。

        `id` 是 photos.list_recent / photos.list_by_album 等返回的 photo id 字符串。
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "id": {
              "type": "string",
              "description": "Photo id from photos.list_recent / photos.list_by_album."
            }
          },
          "required": ["id"]
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        val idStr = args.path("id").asText("").trim()
        val id = idStr.toLongOrNull()
            ?: throw IllegalArgumentException("invalid id: $idStr")

        val result: ObjectNode = mapper.createObjectNode()
        result.put("id", idStr)

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.ORIENTATION
        )
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

        // Step 1: MediaStore columns (cheap, always present).
        context.contentResolver.query(
            uri, projection, null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                fun s(col: String) = c.getColumnIndex(col).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { c.getString(it) }
                fun l(col: String) = c.getColumnIndex(col).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { c.getLong(it) }
                fun i(col: String) = c.getColumnIndex(col).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { c.getInt(it) }

                s(MediaStore.Images.Media.DISPLAY_NAME)?.let { result.put("name", it) }
                l(MediaStore.Images.Media.SIZE)?.let { result.put("size_bytes", it) }
                i(MediaStore.Images.Media.WIDTH)?.let { result.put("width", it) }
                i(MediaStore.Images.Media.HEIGHT)?.let { result.put("height", it) }
                s(MediaStore.Images.Media.MIME_TYPE)?.let { result.put("mime_type", it) }
                l(MediaStore.Images.Media.DATE_TAKEN)?.let { result.put("date_taken_ms", it) }
                i(MediaStore.Images.Media.ORIENTATION)?.let { result.put("orientation", it) }
            }
        }

        // Step 2: EXIF (richer fields). Open InputStream because file path may
        // not be reachable on scoped storage.
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)

                exif.getAttribute(ExifInterface.TAG_MAKE)
                    ?.takeIf { it.isNotBlank() }?.let { result.put("camera_make", it.trim()) }
                exif.getAttribute(ExifInterface.TAG_MODEL)
                    ?.takeIf { it.isNotBlank() }?.let { result.put("camera_model", it.trim()) }

                // Focal length (mm). Stored as rational e.g. "26/1".
                exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                    ?.takeIf { it.isNotBlank() }?.let { raw ->
                        rationalToDouble(raw)?.let { result.put("focal_length_mm", it) }
                            ?: result.put("focal_length", raw)
                    }

                // Aperture: F number. May be absent on screenshots.
                exif.getAttribute(ExifInterface.TAG_F_NUMBER)
                    ?.takeIf { it.isNotBlank() }?.let { raw ->
                        raw.toDoubleOrNull()?.let { result.put("aperture", it) }
                            ?: result.put("aperture", raw)
                    }

                exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { it.toIntOrNull()?.let { v -> result.put("iso", v) } ?: result.put("iso", it) }

                exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                    ?.takeIf { it.isNotBlank() }?.let { raw ->
                        // EXIF exposure time is in seconds (decimal). Format as
                        // "1/250" if < 1s for human readability.
                        val sec = raw.toDoubleOrNull()
                        if (sec != null && sec > 0) {
                            val formatted = if (sec < 1.0) "1/${(1.0 / sec).toInt()}s" else "${sec}s"
                            result.put("shutter_speed", formatted)
                        } else {
                            result.put("shutter_speed", raw)
                        }
                    }

                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?.takeIf { it.isNotBlank() }?.let { result.put("datetime_original", it) }

                // GPS (rare; only present if user enabled location for camera).
                val latLong = FloatArray(2)
                if (exif.getLatLong(latLong)) {
                    result.put("gps_latitude", latLong[0].toDouble())
                    result.put("gps_longitude", latLong[1].toDouble())
                }
                val altitude = exif.getAltitude(Double.NaN)
                if (!altitude.isNaN()) {
                    result.put("gps_altitude_m", altitude)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "EXIF read failed for id=$id: ${e.message}")
        }

        result
    }

    /** Convert "26/1" / "11/10" rational EXIF strings to Double. */
    private fun rationalToDouble(raw: String): Double? {
        val parts = raw.split("/")
        if (parts.size != 2) return raw.toDoubleOrNull()
        val num = parts[0].toDoubleOrNull() ?: return null
        val den = parts[1].toDoubleOrNull() ?: return null
        if (den == 0.0) return null
        return num / den
    }

    companion object {
        private const val TAG = "PhotosGetMetadataTool"
    }
}
