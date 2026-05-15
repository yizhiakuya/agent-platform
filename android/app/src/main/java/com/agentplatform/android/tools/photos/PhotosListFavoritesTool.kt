package com.agentplatform.android.tools.photos

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.agentplatform.android.core.tool.Tool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotosListFavoritesTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {
    override val name: String = "photos.list_favorites"

    override val description: String = """
        List photos marked as favorite in the device gallery. Returns the most
        recent matching favorites first as cached display-sized image asset URLs.
        Use this to inspect or verify favorite photo operations.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "limit": {
              "type": "integer",
              "minimum": 1,
              "maximum": 50,
              "default": 20,
              "description": "Max favorite photos to return."
            }
          }
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw UnsupportedOperationException("listing favorites requires Android 11 or newer")
        }
        listSpecialPhotos(
            context = context,
            mapper = mapper,
            limit = args.path("limit").asInt(20).coerceIn(1, 50),
            matchArg = MediaStore.QUERY_ARG_MATCH_FAVORITE,
            resultKey = "photos"
        )
    }
}

class PhotosListTrashTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {
    override val name: String = "photos.list_trash"

    override val description: String = """
        List photos currently in Android's trash/recycle bin. Returns the most
        recent trashed photos first. Use this before photos.restore when the
        user asks to recover deleted photos.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "limit": {
              "type": "integer",
              "minimum": 1,
              "maximum": 50,
              "default": 20,
              "description": "Max trashed photos to return."
            }
          }
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw UnsupportedOperationException("listing trash requires Android 11 or newer")
        }
        listSpecialPhotos(
            context = context,
            mapper = mapper,
            limit = args.path("limit").asInt(20).coerceIn(1, 50),
            matchArg = MediaStore.QUERY_ARG_MATCH_TRASHED,
            resultKey = "photos"
        )
    }
}

private fun listSpecialPhotos(
    context: Context,
    mapper: ObjectMapper,
    limit: Int,
    matchArg: String,
    resultKey: String
): JsonNode {
    val uploader = PhotoAssetUploader(context, mapper)
    val photos = mapper.createArrayNode()
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.SIZE
    )
    val queryArgs = Bundle().apply {
        putInt(matchArg, MediaStore.MATCH_ONLY)
        putStringArray(
            ContentResolver.QUERY_ARG_SORT_COLUMNS,
            arrayOf(MediaStore.Images.Media.DATE_TAKEN)
        )
        putInt(
            ContentResolver.QUERY_ARG_SORT_DIRECTION,
            ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
        )
        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
    }

    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        queryArgs,
        null
    )?.use { c ->
        val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val dateIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
        val modifiedIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
        val sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE)
        while (c.moveToNext() && photos.size() < limit) {
            val id = c.getLong(idIdx)
            val name = c.getString(nameIdx) ?: "image_$id"
            val date = if (dateIdx >= 0 && !c.isNull(dateIdx)) c.getLong(dateIdx) else 0L
            val modified = if (modifiedIdx >= 0 && !c.isNull(modifiedIdx)) c.getLong(modifiedIdx) else 0L
            val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L

            val obj = mapper.createObjectNode()
            obj.put("id", id.toString())
            obj.put("name", name)
            obj.put("date_taken_ms", date)
            obj.put("date_modified_sec", modified)
            obj.put("size_bytes", size)

            val image = try {
                PhotoToolUtils.encodedDisplayPhoto(
                    context = context,
                    id = id,
                    maxDim = 2048,
                    quality = 85,
                    sourceModifiedSec = modified,
                    sourceSizeBytes = size
                )
            } catch (e: Exception) {
                Log.w("PhotosListSpecial", "image encode failed for id=$id: ${e.message}")
                null
            }
            if (image != null) {
                val upload = uploader.uploadDisplayJpeg(id, name, image)
                PhotoAssetUploader.putUploadFields(obj, upload, image)
            }
            photos.add(obj)
        }
    }

    return mapper.createObjectNode().apply {
        set<JsonNode>(resultKey, photos)
        put("count", photos.size())
    }
}
