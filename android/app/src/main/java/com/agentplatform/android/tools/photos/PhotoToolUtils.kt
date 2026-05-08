package com.agentplatform.android.tools.photos

import android.content.ContentResolver
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import java.io.ByteArrayOutputStream

object PhotoToolUtils {
    fun loadThumbnail(resolver: ContentResolver, id: Long, size: Int = 256): Bitmap {
        val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
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
}
