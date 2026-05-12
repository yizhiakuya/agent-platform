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

        val endpoints = listOf("$serverUrl/api/uploads/photos")
        var lastError = "upload_failed"
        for (endpoint in endpoints) {
            try {
                var tryNextEndpoint = false
                val request = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", contentType)
                    .header("X-Media-Store-Id", mediaStoreId.toString())
                    .header("X-Content-Hash", contentHash)
                    .apply {
                        if (!name.isNullOrBlank()) header("X-Filename-Hash", sha256(name.toByteArray(Charsets.UTF_8)))
                        header("X-Image-Width", image.width.toString())
                        header("X-Image-Height", image.height.toString())
                    }
                    .post(image.jpegBytes.toRequestBody(JPEG))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val success = successFromResponse(endpoint, mediaStoreId, body, contentType)
                        rememberUpload(serverUrl, token, contentHash, success)
                        return success
                    }
                    val err = response.body?.string()?.take(240).orEmpty()
                    lastError = "HTTP ${response.code}${if (err.isBlank()) "" else ": $err"}"
                    tryNextEndpoint = response.code == 404 || response.code == 405
                }
                if (!tryNextEndpoint) break
            } catch (e: Exception) {
                lastError = e.message ?: "upload_failed"
                Log.w(TAG, "photo asset upload failed endpoint=$endpoint id=$mediaStoreId: ${e.message}")
            }
        }
        return UploadResult.Failure(lastError)
    }

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

    sealed class UploadResult {
        data class Success(
            val assetId: String,
            val imageUrl: String,
            val contentType: String,
            val assetCacheHit: Boolean = false
        ) : UploadResult()

        data class Failure(val message: String) : UploadResult()
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
            imageUrlField: String = "image_url",
            assetIdField: String = "asset_id",
            contentTypeField: String = "content_type",
            bytesField: String = "image_bytes",
            widthField: String = "image_width",
            heightField: String = "image_height",
            cacheHitField: String = "image_cache_hit",
            assetCacheHitField: String = "asset_cache_hit",
            errorField: String = "image_upload_error"
        ) {
            when (upload) {
                is UploadResult.Success -> {
                    obj.put(imageUrlField, upload.imageUrl)
                    obj.put(assetIdField, upload.assetId)
                    obj.put(contentTypeField, upload.contentType)
                    obj.put(assetCacheHitField, upload.assetCacheHit)
                }
                is UploadResult.Failure -> {
                    obj.put(errorField, upload.message)
                    obj.put(contentTypeField, JPEG.toString())
                }
            }
            obj.put(bytesField, image.bytes)
            obj.put(widthField, image.width)
            obj.put(heightField, image.height)
            obj.put(cacheHitField, image.cacheHit)
        }

        private fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
