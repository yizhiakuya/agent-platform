package com.agentplatform.android.tools.photos

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

object PhotoToolUtils {
    private const val TAG = "PhotoToolUtils"

    data class EncodedPhoto(
        val jpegBytes: ByteArray,
        val width: Int,
        val height: Int,
        val cacheHit: Boolean
    ) {
        val b64: String
            get() = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val bytes: Int
            get() = jpegBytes.size
    }

    fun loadThumbnail(resolver: ContentResolver, id: Long, size: Int = 256): Bitmap {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(uri, Size(size, size), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                resolver,
                id,
                MediaStore.Images.Thumbnails.MINI_KIND,
                null
            ) ?: throw RuntimeException("no thumb")
        }
    }

    fun jpegBase64(bitmap: Bitmap, quality: Int = 70): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    fun encodedDisplayPhoto(
        context: Context,
        id: Long,
        maxDim: Int = 2048,
        quality: Int = 85,
        sourceModifiedSec: Long = 0L,
        sourceSizeBytes: Long = 0L
    ): EncodedPhoto {
        val safeMaxDim = maxDim.coerceIn(512, 2048)
        val safeQuality = quality.coerceIn(50, 95)
        val cacheFile = displayCacheFile(context, id, safeMaxDim, safeQuality, sourceModifiedSec, sourceSizeBytes)
        readCached(cacheFile)?.let {
            return it.copy(cacheHit = true)
        }

        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOpts)
        }
        val srcW = boundsOpts.outWidth
        val srcH = boundsOpts.outHeight
        if (srcW <= 0 || srcH <= 0) throw RuntimeException("invalid bounds")

        var sample = 1
        while (srcW / (sample * 2) > safeMaxDim && srcH / (sample * 2) > safeMaxDim) {
            sample *= 2
        }

        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw RuntimeException("decode failed")

        var current = raw
        try {
            val longEdge = maxOf(current.width, current.height)
            if (longEdge > safeMaxDim) {
                val ratio = safeMaxDim.toFloat() / longEdge
                val scaled = Bitmap.createScaledBitmap(
                    current,
                    (current.width * ratio).toInt().coerceAtLeast(1),
                    (current.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
                if (scaled !== current) current.recycle()
                current = scaled
            }

            val oriented = applyExifOrientation(context, id, current)
            if (oriented !== current) current = oriented

            val bytes = ByteArrayOutputStream().also {
                current.compress(Bitmap.CompressFormat.JPEG, safeQuality, it)
            }.toByteArray()
            val out = EncodedPhoto(
                jpegBytes = bytes,
                width = current.width,
                height = current.height,
                cacheHit = false
            )
            writeCached(cacheFile, out)
            trimDisplayCache(context, maxBytes = 160L * 1024L * 1024L)
            return out
        } finally {
            current.recycle()
        }
    }

    private fun applyExifOrientation(context: Context, id: Long, bmp: Bitmap): Bitmap {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        return try {
            val orientation = context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
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
        } catch (_: Exception) {
            bmp
        }
    }

    private fun displayCacheFile(
        context: Context,
        id: Long,
        maxDim: Int,
        quality: Int,
        sourceModifiedSec: Long,
        sourceSizeBytes: Long
    ): File {
        val dir = File(context.cacheDir, "photo-display-cache").apply { mkdirs() }
        val key = "$id|$maxDim|$quality|$sourceModifiedSec|$sourceSizeBytes"
        return File(dir, sha256(key) + ".jpg")
    }

    private fun readCached(file: File): EncodedPhoto? {
        return try {
            if (!file.isFile) return null
            if (!file.setLastModified(System.currentTimeMillis())) {
                Log.d(TAG, "Unable to refresh display cache timestamp for ${file.name}")
            }
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            EncodedPhoto(
                jpegBytes = bytes,
                width = opts.outWidth,
                height = opts.outHeight,
                cacheHit = true
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCached(file: File, photo: EncodedPhoto) {
        try {
            file.parentFile?.mkdirs()
            file.writeBytes(photo.jpegBytes)
        } catch (_: Exception) {
            // Cache failures should never fail the photo tool.
        }
    }

    private fun trimDisplayCache(context: Context, maxBytes: Long) {
        val dir = File(context.cacheDir, "photo-display-cache")
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val len = file.length()
            if (file.delete()) total -= len
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
