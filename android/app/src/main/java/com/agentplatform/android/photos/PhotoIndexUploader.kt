package com.agentplatform.android.photos

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.agentplatform.android.data.AppPrefs
import com.agentplatform.android.tools.photos.PhotoToolUtils
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class PhotoIndexUploader(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val appPrefs = AppPrefs(context)
    private val indexPrefs = PhotoIndexPrefs(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun syncOnce(maxRows: Int = 300, batchSize: Int = 20): SyncResult = withContext(ioDispatcher) {
        val binding = currentBinding()
        if (binding == null) {
            return@withContext SyncResult(0, 0, "not_bound")
        }

        val didReconcile = maybeReconcile(binding.serverUrl, binding.token)
        val rows = loadRows(
            maxRows.coerceIn(1, 500),
            indexPrefs.lastIndexedModifiedSec,
            indexPrefs.lastIndexedIdAtModified
        )
        if (rows.isEmpty()) {
            return@withContext finishEmptySync(didReconcile)
        }

        val progress = uploadRows(binding, rows, batchSize.coerceIn(1, 50))
        finishUploadedSync(rows.size, progress)
    }

    private fun currentBinding(): ServerBinding? {
        val serverUrl = appPrefs.serverUrl?.trimEnd('/')
        val token = appPrefs.token
        return if (serverUrl.isNullOrBlank() || token.isNullOrBlank()) {
            null
        } else {
            ServerBinding(serverUrl, token)
        }
    }

    private fun finishEmptySync(didReconcile: Boolean): SyncResult {
        indexPrefs.lastRunMs = System.currentTimeMillis()
        return SyncResult(0, 0, if (didReconcile) "reconciled" else "no_new_rows")
    }

    private fun finishUploadedSync(scanned: Int, progress: UploadProgress): SyncResult {
        if (progress.uploaded > 0) {
            indexPrefs.lastIndexedModifiedSec = progress.maxModified
            indexPrefs.lastIndexedIdAtModified = progress.maxIdAtModified
        }
        indexPrefs.lastRunMs = System.currentTimeMillis()
        return SyncResult(scanned, progress.uploaded, "ok")
    }

    private fun uploadRows(binding: ServerBinding, rows: List<Row>, batchSize: Int): UploadProgress {
        var progress = UploadProgress(
            uploaded = 0,
            maxModified = indexPrefs.lastIndexedModifiedSec,
            maxIdAtModified = indexPrefs.lastIndexedIdAtModified
        )
        for (chunk in rows.chunked(batchSize)) {
            val batch = buildUploadBatch(chunk)
            if (batch.assets.isEmpty) break

            postAssetBatch(binding, batch.assets)
            progress = progress.record(batch)
            if (batch.stoppedByPayloadFailure) break
        }
        return progress
    }

    private fun buildUploadBatch(rows: List<Row>): UploadBatch {
        val assets = mapper.createArrayNode()
        val assetRows = mutableListOf<Row>()
        var stoppedByPayloadFailure = false
        for (row in rows) {
            val asset = buildAsset(row)
            if (asset == null) {
                stoppedByPayloadFailure = true
                break
            }
            assets.add(asset)
            assetRows += row
        }
        return UploadBatch(assets, assetRows, stoppedByPayloadFailure)
    }

    private fun buildAsset(row: Row): ObjectNode? {
        return try {
            val bitmap = PhotoToolUtils.loadThumbnail(context.contentResolver, row.id, 512)
            try {
                val thumbB64 = PhotoToolUtils.jpegBase64(bitmap, 76)
                buildAssetNode(row, thumbB64)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail/index payload failed id=${row.id}: ${e.message}")
            null
        }
    }

    private fun buildAssetNode(row: Row, thumbB64: String): ObjectNode {
        return mapper.createObjectNode().apply {
            put("mediaStoreId", row.id.toString())
            put("name", row.name)
            if (row.bucketId.isNotBlank()) put("bucketId", row.bucketId)
            if (row.bucketName.isNotBlank()) put("bucketName", row.bucketName)
            put("dateTakenMs", row.dateTakenMs)
            put("dateModifiedSec", row.dateModifiedSec)
            put("sizeBytes", row.sizeBytes)
            put("width", row.width)
            put("height", row.height)
            if (row.mimeType.isNotBlank()) put("mimeType", row.mimeType)
            put("contentHash", row.fingerprint())
            put("thumbB64", thumbB64)
        }
    }

    private fun postAssetBatch(binding: ServerBinding, assets: ArrayNode) {
        val body = mapper.createObjectNode()
        body.set<ArrayNode>("assets", assets)
        val req = Request.Builder()
            .url("${binding.serverUrl}/api/photos/index/batch")
            .header("Authorization", "Bearer ${binding.token}")
            .post(mapper.writeValueAsBytes(body).toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string()?.take(300)
                throw RuntimeException("photo index upload failed HTTP ${resp.code}: $err")
            }
        }
    }

    private fun maybeReconcile(serverUrl: String, token: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - indexPrefs.lastReconcileMs < RECONCILE_INTERVAL_MS) return false
        if (!hasFullImageAccess()) {
            Log.w(TAG, "photo index reconcile skipped: full image access is not granted")
            return false
        }

        val ids = loadCurrentMediaStoreIds(MAX_RECONCILE_IDS + 1)
        if (ids == null) {
            Log.w(TAG, "photo index reconcile skipped: MediaStore query returned no cursor")
            return false
        }
        if (ids.size > MAX_RECONCILE_IDS) {
            Log.w(TAG, "photo index reconcile skipped: current MediaStore image count exceeds $MAX_RECONCILE_IDS")
            return false
        }
        try {
            val body = mapper.createObjectNode()
            val arr = mapper.createArrayNode()
            ids.forEach { arr.add(it.toString()) }
            body.set<ArrayNode>("mediaStoreIds", arr)
            val req = Request.Builder()
                .url("$serverUrl/api/photos/index/reconcile")
                .header("Authorization", "Bearer $token")
                .post(mapper.writeValueAsBytes(body).toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string()?.take(300)
                    throw RuntimeException("HTTP ${resp.code}: $err")
                }
            }
            indexPrefs.lastReconcileMs = now
            Log.i(TAG, "photo index reconcile uploaded currentIds=${ids.size}")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "photo index reconcile failed; continuing incremental upload: ${e.message}", e)
            return false
        }
    }

    private fun loadCurrentMediaStoreIds(limit: Int): List<Long>? {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val args = Bundle().apply {
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media._ID))
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_ASCENDING
                )
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, args, null)
        } else {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media._ID} ASC"
            )
        }

        val ids = mutableListOf<Long>()
        cursor ?: return null
        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext() && ids.size < limit) {
                ids += c.getLong(idIdx)
            }
        }
        return ids
    }

    private fun hasFullImageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun loadRows(limit: Int, afterModifiedSec: Long, afterIdAtModified: Long): List<Row> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE
        )
        val selection = if (afterModifiedSec > 0L) {
            "(${MediaStore.Images.Media.DATE_MODIFIED} > ? OR (${MediaStore.Images.Media.DATE_MODIFIED} = ? AND ${MediaStore.Images.Media._ID} > ?))"
        } else null
        val selectionArgs = if (afterModifiedSec > 0L) {
            arrayOf(afterModifiedSec.toString(), afterModifiedSec.toString(), afterIdAtModified.toString())
        } else null
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val args = Bundle().apply {
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media._ID)
                )
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_ASCENDING
                )
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                if (selection != null) {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                }
            }
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, args, null)
        } else {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_MODIFIED} ASC, ${MediaStore.Images.Media._ID} ASC"
            )
        }

        val out = mutableListOf<Row>()
        cursor?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bucketIdIdx = c.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameIdx = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val modifiedIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE)
            val widthIdx = c.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val heightIdx = c.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            val mimeIdx = c.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(idIdx)
                val dateTaken = l(c, dateIdx)
                val modified = l(c, modifiedIdx)
                out += Row(
                    id = id,
                    name = c.getString(nameIdx) ?: "image_$id",
                    bucketId = s(c, bucketIdIdx),
                    bucketName = s(c, bucketNameIdx),
                    dateTakenMs = if (dateTaken > 0L) dateTaken else modified * 1000L,
                    dateModifiedSec = modified,
                    sizeBytes = l(c, sizeIdx),
                    width = i(c, widthIdx),
                    height = i(c, heightIdx),
                    mimeType = s(c, mimeIdx)
                )
            }
        }
        return out
    }

    private fun s(c: android.database.Cursor, idx: Int): String =
        if (idx >= 0 && !c.isNull(idx)) c.getString(idx) ?: "" else ""

    private fun l(c: android.database.Cursor, idx: Int): Long =
        if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else 0L

    private fun i(c: android.database.Cursor, idx: Int): Int =
        if (idx >= 0 && !c.isNull(idx)) c.getInt(idx) else 0

    private data class ServerBinding(
        val serverUrl: String,
        val token: String
    )

    private data class UploadBatch(
        val assets: ArrayNode,
        val rows: List<Row>,
        val stoppedByPayloadFailure: Boolean
    )

    private data class UploadProgress(
        val uploaded: Int,
        val maxModified: Long,
        val maxIdAtModified: Long
    ) {
        fun record(batch: UploadBatch): UploadProgress {
            var nextMaxModified = maxModified
            var nextMaxIdAtModified = maxIdAtModified
            batch.rows.forEach { row ->
                val newerModified = row.dateModifiedSec > nextMaxModified
                val sameModifiedWithHigherId = row.dateModifiedSec == nextMaxModified && row.id > nextMaxIdAtModified
                if (newerModified || sameModifiedWithHigherId) {
                    nextMaxModified = row.dateModifiedSec
                    nextMaxIdAtModified = row.id
                }
            }
            return copy(
                uploaded = uploaded + batch.assets.size(),
                maxModified = nextMaxModified,
                maxIdAtModified = nextMaxIdAtModified
            )
        }
    }

    private data class Row(
        val id: Long,
        val name: String,
        val bucketId: String,
        val bucketName: String,
        val dateTakenMs: Long,
        val dateModifiedSec: Long,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
        val mimeType: String
    ) {
        fun fingerprint(): String {
            val raw = "$id|$name|$dateTakenMs|$dateModifiedSec|$sizeBytes|$width|$height"
            val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    data class SyncResult(val scanned: Int, val uploaded: Int, val status: String)

    companion object {
        private const val TAG = "PhotoIndexUploader"
        private const val RECONCILE_INTERVAL_MS = 6 * 60 * 60_000L
        private const val MAX_RECONCILE_IDS = 50_000
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
