package com.agentplatform.android.tools.photos

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
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
        val id = parsePhotoId(args)
        val result: ObjectNode = mapper.createObjectNode()
        result.put("id", id.toString())
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        addMediaStoreFields(result, uri)
        addExifFields(result, uri, id)
        result
    }

    private fun parsePhotoId(args: JsonNode): Long {
        val id = args.path("id").asText("").trim()
        return id.toLongOrNull() ?: throw IllegalArgumentException("invalid id: $id")
    }

    private fun addMediaStoreFields(result: ObjectNode, uri: Uri) {
        context.contentResolver.query(
            uri,
            MEDIA_STORE_PROJECTION,
            null,
            null,
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                addCursorFields(result, c)
            }
        }
    }

    private fun addCursorFields(result: ObjectNode, cursor: Cursor) {
        stringValue(cursor, MediaStore.Images.Media.DISPLAY_NAME)?.let { result.put("name", it) }
        longValue(cursor, MediaStore.Images.Media.SIZE)?.let { result.put("size_bytes", it) }
        intValue(cursor, MediaStore.Images.Media.WIDTH)?.let { result.put("width", it) }
        intValue(cursor, MediaStore.Images.Media.HEIGHT)?.let { result.put("height", it) }
        stringValue(cursor, MediaStore.Images.Media.MIME_TYPE)?.let { result.put("mime_type", it) }
        longValue(cursor, MediaStore.Images.Media.DATE_TAKEN)?.let { result.put("date_taken_ms", it) }
        intValue(cursor, MediaStore.Images.Media.ORIENTATION)?.let { result.put("orientation", it) }
    }

    private fun addExifFields(result: ObjectNode, uri: Uri, id: Long) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                addCameraFields(result, exif)
                addExposureFields(result, exif)
                addDateTimeField(result, exif)
                addGpsFields(result, exif)
            }
        } catch (e: Exception) {
            Log.w(TAG, "EXIF read failed for id=$id: ${e.message}")
        }
    }

    private fun addCameraFields(result: ObjectNode, exif: ExifInterface) {
        exif.nonBlank(ExifInterface.TAG_MAKE)?.let { result.put("camera_make", it.trim()) }
        exif.nonBlank(ExifInterface.TAG_MODEL)?.let { result.put("camera_model", it.trim()) }
    }

    private fun addExposureFields(result: ObjectNode, exif: ExifInterface) {
        addFocalLength(result, exif)
        addAperture(result, exif)
        addIso(result, exif)
        addShutterSpeed(result, exif)
    }

    private fun addFocalLength(result: ObjectNode, exif: ExifInterface) {
        exif.nonBlank(ExifInterface.TAG_FOCAL_LENGTH)?.let { raw ->
            rationalToDouble(raw)?.let { result.put("focal_length_mm", it) }
                ?: result.put("focal_length", raw)
        }
    }

    private fun addAperture(result: ObjectNode, exif: ExifInterface) {
        exif.nonBlank(ExifInterface.TAG_F_NUMBER)?.let { raw ->
            raw.toDoubleOrNull()?.let { result.put("aperture", it) }
                ?: result.put("aperture", raw)
        }
    }

    private fun addIso(result: ObjectNode, exif: ExifInterface) {
        exif.nonBlank(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { raw ->
            raw.toIntOrNull()?.let { result.put("iso", it) }
                ?: result.put("iso", raw)
        }
    }

    private fun addShutterSpeed(result: ObjectNode, exif: ExifInterface) {
        exif.nonBlank(ExifInterface.TAG_EXPOSURE_TIME)?.let { raw ->
            result.put("shutter_speed", shutterSpeed(raw))
        }
    }

    private fun addDateTimeField(result: ObjectNode, exif: ExifInterface) {
        exif.nonBlank(ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
            result.put("datetime_original", it)
        }
    }

    private fun addGpsFields(result: ObjectNode, exif: ExifInterface) {
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

    private fun ExifInterface.nonBlank(tag: String): String? {
        return getAttribute(tag)?.takeIf { it.isNotBlank() }
    }

    private fun shutterSpeed(raw: String): String {
        val sec = raw.toDoubleOrNull()
        return if (sec != null && sec > 0) {
            if (sec < 1.0) "1/${(1.0 / sec).toInt()}s" else "${sec}s"
        } else {
            raw
        }
    }

    private fun stringValue(cursor: Cursor, column: String): String? {
        val index = cursor.validIndex(column) ?: return null
        return cursor.getString(index)
    }

    private fun longValue(cursor: Cursor, column: String): Long? {
        val index = cursor.validIndex(column) ?: return null
        return cursor.getLong(index)
    }

    private fun intValue(cursor: Cursor, column: String): Int? {
        val index = cursor.validIndex(column) ?: return null
        return cursor.getInt(index)
    }

    private fun Cursor.validIndex(column: String): Int? {
        return getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }
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
        private val MEDIA_STORE_PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.ORIENTATION
        )
    }
}
