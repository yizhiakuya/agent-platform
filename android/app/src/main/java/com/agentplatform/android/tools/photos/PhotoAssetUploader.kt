package com.agentplatform.android.tools.photos

import android.content.Context
import android.util.Log
import com.agentplatform.android.data.AppPrefs
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class PhotoAssetUploader(
    context: Context,
    private val mapper: ObjectMapper
) {
    private val appPrefs = AppPrefs(context)
    private val uploadCache = context.applicationContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    fun uploadDisplayJpeg(
        mediaStoreId: Long,
        name: String?,
        image: PhotoToolUtils.EncodedPhoto
    ): UploadResult {
        val serverUrl = appPrefs.serverUrl?.trimEnd('/')
        val token = appPrefs.token
        if (serverUrl.isNullOrBlank() || token.isNullOrBlank()) {
            return UploadResult.Failure("not_bound")
        }

        val contentType = JPEG.toString()
        val contentHash = sha256(image.jpegBytes)
        cachedUpload(serverUrl, token, contentHash)?.let { return it }

        val upload = PendingUpload(
            serverUrl = serverUrl,
            token = token,
            mediaStoreId = mediaStoreId,
            name = name,
            image = image,
            contentType = contentType,
            contentHash = contentHash
        )
        return uploadToFirstAvailableEndpoint(upload)
    }

    private fun uploadToFirstAvailableEndpoint(upload: PendingUpload): UploadResult {
        var lastError = "upload_failed"
        for (endpoint in upload.endpoints) {
            try {
                when (val attempt = uploadToEndpoint(endpoint, upload)) {
                    is UploadAttempt.Success -> return attempt.result
                    is UploadAttempt.Failure -> {
                        lastError = attempt.message
                        if (!attempt.tryNextEndpoint) break
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: "upload_failed"
                Log.w(TAG, "photo asset upload failed endpoint=$endpoint id=${upload.mediaStoreId}: ${e.message}")
            }
        }
        return UploadResult.Failure(lastError)
    }

    private fun uploadToEndpoint(endpoint: String, upload: PendingUpload): UploadAttempt {
        val request = buildUploadRequest(endpoint, upload)
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val success = successFromResponse(endpoint, upload.mediaStoreId, body, upload.contentType)
                rememberUpload(upload.serverUrl, upload.token, upload.contentHash, success)
                return UploadAttempt.Success(success)
            }
            val err = response.body?.string()?.take(240).orEmpty()
            return UploadAttempt.Failure(
                message = "HTTP ${response.code}${if (err.isBlank()) "" else ": $err"}",
                tryNextEndpoint = response.code == 404 || response.code == 405
            )
        }
    }

    private fun buildUploadRequest(endpoint: String, upload: PendingUpload): Request =
        Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${upload.token}")
            .header("Content-Type", upload.contentType)
            .header("X-Media-Store-Id", upload.mediaStoreId.toString())
            .header("X-Content-Hash", upload.contentHash)
            .apply {
                upload.name
                    ?.takeIf { it.isNotBlank() }
                    ?.let { header("X-Filename-Hash", sha256(it.toByteArray(Charsets.UTF_8))) }
                header("X-Image-Width", upload.image.width.toString())
                header("X-Image-Height", upload.image.height.toString())
            }
            .post(upload.image.jpegBytes.toRequestBody(JPEG))
            .build()

    private fun successFromResponse(
        endpoint: String,
        mediaStoreId: Long,
        body: String,
        contentType: String
    ): UploadResult.Success {
        val json = body.trim().takeIf { it.startsWith("{") }?.let {
            runCatching { mapper.readTree(it) }.getOrNull()
        }
        val assetId = firstText(json, "asset_id", "assetId", "id")
            ?: mediaStoreId.toString()
        val imageUrl = firstText(json, "image_url", "imageUrl", "url", "href")
            ?: if (endpoint.endsWith("/content")) endpoint else "$endpoint/$assetId"
        val responseContentType = firstText(json, "content_type", "contentType", "mime_type", "mimeType")
            ?: contentType
        return UploadResult.Success(
            assetId = assetId,
            imageUrl = imageUrl,
            contentType = responseContentType
        )
    }

    private fun cachedUpload(serverUrl: String, token: String, contentHash: String): UploadResult.Success? {
        val raw = uploadCache.getString(cacheKey(serverUrl, token, contentHash), null) ?: return null
        val json = runCatching { mapper.readTree(raw) }.getOrNull() ?: return null
        val assetId = firstText(json, "asset_id", "assetId") ?: return null
        val imageUrl = firstText(json, "image_url", "imageUrl", "url") ?: return null
        val contentType = firstText(json, "content_type", "contentType") ?: JPEG.toString()
        return UploadResult.Success(
            assetId = assetId,
            imageUrl = imageUrl,
            contentType = contentType,
            assetCacheHit = true
        )
    }

    private fun rememberUpload(
        serverUrl: String,
        token: String,
        contentHash: String,
        success: UploadResult.Success
    ) {
        val obj = mapper.createObjectNode()
        obj.put("asset_id", success.assetId)
        obj.put("image_url", success.imageUrl)
        obj.put("content_type", success.contentType)
        obj.put("created_at_ms", System.currentTimeMillis())
        uploadCache.edit()
            .putString(cacheKey(serverUrl, token, contentHash), obj.toString())
            .apply()
        trimUploadCache()
    }

    private fun trimUploadCache() {
        val entries = uploadCache.all
        if (entries.size <= MAX_CACHE_ENTRIES) return
        val ordered = entries.mapNotNull { (key, value) ->
            val raw = value as? String ?: return@mapNotNull null
            val createdAt = runCatching { mapper.readTree(raw).path("created_at_ms").asLong(0L) }
                .getOrDefault(0L)
            key to createdAt
        }.sortedBy { it.second }
        val editor = uploadCache.edit()
        ordered.take((entries.size - MAX_CACHE_ENTRIES).coerceAtLeast(0))
            .forEach { (key, _) -> editor.remove(key) }
        editor.apply()
    }

    private fun cacheKey(serverUrl: String, token: String, contentHash: String): String {
        return "asset:" + sha256("$serverUrl|$token|$contentHash".toByteArray(Charsets.UTF_8))
    }

    private fun firstText(json: JsonNode?, vararg keys: String): String? {
        if (json == null || !json.isObject) return null
        for (key in keys) {
            val value = json.path(key)
            if (value.isTextual && value.asText().isNotBlank()) return value.asText()
            if (value.isNumber) return value.asText()
        }
        return null
    }

    private data class PendingUpload(
        val serverUrl: String,
        val token: String,
        val mediaStoreId: Long,
        val name: String?,
        val image: PhotoToolUtils.EncodedPhoto,
        val contentType: String,
        val contentHash: String
    ) {
        val endpoints: List<String>
            get() = listOf("$serverUrl/api/uploads/photos")
    }

    private sealed class UploadAttempt {
        data class Success(val result: UploadResult.Success) : UploadAttempt()
        data class Failure(val message: String, val tryNextEndpoint: Boolean) : UploadAttempt()
    }

    sealed class UploadResult {
        data class Success(
            val assetId: String,
            val imageUrl: String,
            val contentType: String,
            val assetCacheHit: Boolean = false
        ) : UploadResult()

        data class Failure(val message: String) : UploadResult()
    }

    sealed interface UploadFields {
        val imageUrl: String
        val assetId: String
        val contentType: String
        val bytes: String
        val width: String
        val height: String
        val cacheHit: String
        val assetCacheHit: String
        val error: String

        object Default : UploadFields {
            override val imageUrl = "image_url"
            override val assetId = "asset_id"
            override val contentType = "content_type"
            override val bytes = "image_bytes"
            override val width = "image_width"
            override val height = "image_height"
            override val cacheHit = "image_cache_hit"
            override val assetCacheHit = "asset_cache_hit"
            override val error = "image_upload_error"
        }

        object Thumbnail : UploadFields {
            override val imageUrl = "thumb_url"
            override val assetId = "thumb_asset_id"
            override val contentType = "content_type"
            override val bytes = "thumb_bytes"
            override val width = "thumb_width"
            override val height = "thumb_height"
            override val cacheHit = "thumb_cache_hit"
            override val assetCacheHit = "asset_cache_hit"
            override val error = "thumb_upload_error"
        }

        object AlbumCover : UploadFields {
            override val imageUrl = "cover_image_url"
            override val assetId = "cover_asset_id"
            override val contentType = "cover_content_type"
            override val bytes = "cover_image_bytes"
            override val width = "cover_image_width"
            override val height = "cover_image_height"
            override val cacheHit = "cover_image_cache_hit"
            override val assetCacheHit = "cover_asset_cache_hit"
            override val error = "cover_upload_error"
        }
    }

    companion object {
        private const val TAG = "PhotoAssetUploader"
        private const val CACHE_PREFS = "photo.asset.uploads"
        private const val MAX_CACHE_ENTRIES = 512
        private val JPEG = "image/jpeg".toMediaType()

        fun putUploadFields(
            obj: ObjectNode,
            upload: UploadResult,
            image: PhotoToolUtils.EncodedPhoto,
            fields: UploadFields = UploadFields.Default
        ) {
            when (upload) {
                is UploadResult.Success -> {
                    obj.put(fields.imageUrl, upload.imageUrl)
                    obj.put(fields.assetId, upload.assetId)
                    obj.put(fields.contentType, upload.contentType)
                    obj.put(fields.assetCacheHit, upload.assetCacheHit)
                }
                is UploadResult.Failure -> {
                    obj.put(fields.error, upload.message)
                    obj.put(fields.contentType, JPEG.toString())
                }
            }
            obj.put(fields.bytes, image.bytes)
            obj.put(fields.width, image.width)
            obj.put(fields.height, image.height)
            obj.put(fields.cacheHit, image.cacheHit)
        }

        private fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
