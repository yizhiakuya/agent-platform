package com.agentplatform.android.tools.photos

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.data.AppPrefs
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Save an image URL visible to this platform account into the phone gallery.
 */
class PhotosSaveToGalleryTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {

    override val name: String = "photos.save_to_gallery"

    override val description: String = """
        Save an image from the current chat into the phone gallery. Use this
        when the user asks to save an attached/generated/platform image to the
        phone album. Prefer passing image_url from the user's attachment or a
        previous tool result. This modifies the device gallery and requires
        user confirmation on the phone.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "image_url": {
              "type": "string",
              "description": "Image URL to save. Relative platform upload URLs like /api/uploads/photos/{assetId} are supported and authenticated automatically."
            },
            "filename": {
              "type": "string",
              "description": "Optional filename. Extension is added when missing."
            },
            "album": {
              "type": "string",
              "description": "Optional album/folder name under Pictures. Defaults to Agent Platform."
            },
            "mime_type": {
              "type": "string",
              "enum": ["image/jpeg", "image/png", "image/webp"],
              "description": "Optional expected image MIME type."
            }
          },
          "required": ["image_url"]
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = true

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        val imageUrl = args.path("image_url").asText("").trim()
        require(imageUrl.isNotBlank()) { "image_url required" }
        val prefs = AppPrefs(context)
        val resolvedUrl = resolveUrl(imageUrl, prefs)
        val expectedMime = normalizeMime(args.path("mime_type").asText(null))
        val response = downloadImage(resolvedUrl, prefs)
        val mime = normalizeMime(response.contentType) ?: sniffMime(response.bytes) ?: expectedMime ?: "image/jpeg"
        val filename = safeFilename(args.path("filename").asText(null), mime)
        val album = safeAlbum(args.path("album").asText(null))
        val saved = insertIntoGallery(response.bytes, mime, filename, album)

        mapper.createObjectNode().apply {
            put("ok", true)
            put("image_url", imageUrl)
            put("saved_uri", saved)
            put("filename", filename)
            put("album", album)
            put("mime_type", mime)
            put("bytes", response.bytes.size)
        }
    }

    private fun resolveUrl(imageUrl: String, prefs: AppPrefs): String {
        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) return imageUrl
        if (!imageUrl.startsWith("/")) {
            throw IllegalArgumentException("image_url must be absolute http(s) or a platform-relative path")
        }
        val serverUrl = prefs.serverUrl?.trimEnd('/')
            ?: throw IllegalStateException("device is not bound to a server")
        return serverUrl + imageUrl
    }

    private fun downloadImage(url: String, prefs: AppPrefs): DownloadedImage {
        val builder = Request.Builder().url(url).get()
        if (isPlatformUploadUrl(url, prefs)) {
            val token = prefs.token
            if (!token.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $token")
            }
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("download failed HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("empty image response")
            val bytes = body.bytes()
            if (bytes.isEmpty()) throw IOException("image is empty")
            if (bytes.size > MAX_IMAGE_BYTES) throw IOException("image exceeds ${MAX_IMAGE_BYTES / 1024 / 1024}MB")
            return DownloadedImage(bytes, response.header("Content-Type"))
        }
    }

    private fun isPlatformUploadUrl(url: String, prefs: AppPrefs): Boolean {
        val serverUrl = prefs.serverUrl?.trimEnd('/') ?: return false
        return url.startsWith("$serverUrl/api/uploads/")
    }

    private fun insertIntoGallery(bytes: ByteArray, mime: String, filename: String, album: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$album")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("failed to create gallery image")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("failed to open gallery output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri.toString()
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun normalizeMime(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val clean = value.substringBefore(';').trim().lowercase(Locale.ROOT)
        return when (clean) {
            "image/jpg", "image/pjpeg" -> "image/jpeg"
            "image/jpeg", "image/png", "image/webp" -> clean
            else -> null
        }
    }

    private fun sniffMime(bytes: ByteArray): String? {
        if (bytes.size >= 3
            && (bytes[0].toInt() and 0xff) == 0xff
            && (bytes[1].toInt() and 0xff) == 0xd8
            && (bytes[2].toInt() and 0xff) == 0xff) return "image/jpeg"
        if (bytes.size >= 8
            && (bytes[0].toInt() and 0xff) == 0x89
            && bytes[1] == 0x50.toByte()
            && bytes[2] == 0x4e.toByte()
            && bytes[3] == 0x47.toByte()) return "image/png"
        if (bytes.size >= 12
            && bytes[0] == 0x52.toByte()
            && bytes[1] == 0x49.toByte()
            && bytes[2] == 0x46.toByte()
            && bytes[3] == 0x46.toByte()
            && bytes[8] == 0x57.toByte()
            && bytes[9] == 0x45.toByte()
            && bytes[10] == 0x42.toByte()
            && bytes[11] == 0x50.toByte()) return "image/webp"
        return null
    }

    private fun safeFilename(value: String?, mime: String): String {
        val extension = when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val stem = value
            ?.substringAfterLast('/')
            ?.substringBefore('?')
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.trim('_', '.', '-')
            ?.takeIf { it.isNotBlank() }
            ?: "agent_image_${System.currentTimeMillis()}"
        return if (stem.lowercase(Locale.ROOT).endsWith(".$extension")) stem else "$stem.$extension"
    }

    private fun safeAlbum(value: String?): String {
        val clean = value
            ?.replace(Regex("[/\\\\:]"), "_")
            ?.trim()
            ?.take(48)
            ?.takeIf { it.isNotBlank() }
        return clean ?: "Agent Platform"
    }

    private data class DownloadedImage(val bytes: ByteArray, val contentType: String?)

    companion object {
        private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
    }
}
