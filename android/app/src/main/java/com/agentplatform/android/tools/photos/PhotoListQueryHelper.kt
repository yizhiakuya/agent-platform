package com.agentplatform.android.tools.photos

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

internal data class PhotoPageRequest(
    val requestedLimit: Int,
    val limit: Int,
    val offset: Int,
    val maxDim: Int,
    val safetyCap: Int
) {
    val queryLimit: Int = (limit + 1).coerceAtMost(safetyCap + 1)
    val truncatedBySafetyCap: Boolean = requestedLimit > limit
}

internal class PhotoSelection(
    val sql: String?,
    val args: Array<String>?
)

internal data class PhotoPage(
    val photos: ArrayNode,
    val matchedCount: Int,
    val hasMore: Boolean
)

internal data class PhotoDateRange(
    val after: Long?,
    val before: Long?
) {
    val fields: List<Pair<String, Any?>>
        get() = listOf("date_after" to after, "date_before" to before)
}

internal data class PhotoFilteredGridQuery(
    val context: Context,
    val mapper: ObjectMapper,
    val args: JsonNode,
    val tag: String,
    val selectionFields: List<Pair<String, String?>>,
    val nextArgFields: (PhotoPageRequest, Int) -> List<Pair<String, Any?>>,
    val rootFields: List<Pair<String, Any?>> = emptyList(),
    val summaryFields: List<Pair<String, Any?>> = emptyList()
)

internal object PhotoListQueryHelper {
    const val DEFAULT_LIMIT = 12
    const val MAX_RESULTS_PER_CALL = 200
    const val DEFAULT_MAX_DIM = 1024
    const val MIN_MAX_DIM = 512
    const val MAX_MAX_DIM = 2048

    private val imageProjection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.SIZE
    )

    fun pageRequest(
        args: JsonNode,
        defaultLimit: Int = DEFAULT_LIMIT,
        safetyCap: Int = MAX_RESULTS_PER_CALL,
        defaultMaxDim: Int = DEFAULT_MAX_DIM
    ): PhotoPageRequest {
        val requestedLimit = args.path("limit").asInt(defaultLimit).coerceAtLeast(1)
        return PhotoPageRequest(
            requestedLimit = requestedLimit,
            limit = requestedLimit.coerceAtMost(safetyCap),
            offset = args.path("offset").asInt(0).coerceAtLeast(0),
            maxDim = args.path("max_dim").asInt(defaultMaxDim).coerceIn(MIN_MAX_DIM, MAX_MAX_DIM),
            safetyCap = safetyCap
        )
    }

    fun optionalText(args: JsonNode, field: String): String? =
        args.path(field).asText("").trim().takeIf { it.isNotEmpty() }

    fun optionalLong(args: JsonNode, field: String): Long? =
        args.path(field).let { if (it.isNumber) it.asLong() else null }

    fun dateRange(args: JsonNode): PhotoDateRange =
        PhotoDateRange(
            after = optionalLong(args, "date_after"),
            before = optionalLong(args, "date_before")
        )

    fun dateRangeProperties(mapper: ObjectMapper): List<Pair<String, ObjectNode>> =
        listOf(
            "date_after" to integerProperty(
                mapper,
                "Only return photos taken on/after this UNIX millisecond timestamp (UTC)."
            ),
            "date_before" to integerProperty(
                mapper,
                "Only return photos taken on/before this UNIX millisecond timestamp (UTC)."
            )
        )

    fun dateRangeSelection(range: PhotoDateRange): List<Pair<String, String?>> =
        listOf(
            MediaStore.Images.Media.DATE_TAKEN + " >= ?" to range.after?.toString(),
            MediaStore.Images.Media.DATE_TAKEN + " <= ?" to range.before?.toString()
        )

    fun gridSchema(
        mapper: ObjectMapper,
        itemLabel: String,
        additionalProperties: List<Pair<String, ObjectNode>> = emptyList(),
        required: List<String> = emptyList()
    ): ObjectNode {
        val properties = mapper.createObjectNode().apply {
            set<ObjectNode>(
                "limit",
                integerProperty(
                    mapper = mapper,
                    minimum = 1,
                    defaultValue = DEFAULT_LIMIT,
                    description = "Number of $itemLabel the agent wants in this call. Choose the count that matches the task."
                )
            )
            set<ObjectNode>(
                "offset",
                integerProperty(
                    mapper = mapper,
                    minimum = 0,
                    defaultValue = 0,
                    description = "Number of matching $itemLabel to skip. Use next_args.offset from a previous result to fetch the next page."
                )
            )
            set<ObjectNode>(
                "max_dim",
                integerProperty(
                    mapper = mapper,
                    minimum = MIN_MAX_DIM,
                    maximum = MAX_MAX_DIM,
                    defaultValue = DEFAULT_MAX_DIM,
                    description = "Long-edge size for returned display images. Use 512-768 for many $itemLabel; use 1024-2048 for detail."
                )
            )
            additionalProperties.forEach { (name, property) -> set<ObjectNode>(name, property) }
        }
        return mapper.createObjectNode().apply {
            put("type", "object")
            set<ObjectNode>("properties", properties)
            if (required.isNotEmpty()) {
                set<ArrayNode>("required", mapper.createArrayNode().apply { required.forEach(::add) })
            }
        }
    }

    fun stringProperty(mapper: ObjectMapper, description: String): ObjectNode =
        mapper.createObjectNode().apply {
            put("type", "string")
            put("description", description)
        }

    fun integerProperty(
        mapper: ObjectMapper,
        description: String,
        minimum: Int? = null,
        maximum: Int? = null,
        defaultValue: Int? = null
    ): ObjectNode =
        mapper.createObjectNode().apply {
            put("type", "integer")
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
            defaultValue?.let { put("default", it) }
            put("description", description)
        }

    fun selection(filters: List<Pair<String, String?>>): PhotoSelection {
        val present = filters.mapNotNull { (clause, value) -> value?.let { clause to it } }
        return PhotoSelection(
            sql = present.joinToString(" AND ") { it.first }.ifEmpty { null },
            args = present.map { it.second }.takeIf { it.isNotEmpty() }?.toTypedArray()
        )
    }

    fun queryImages(
        context: Context,
        mapper: ObjectMapper,
        request: PhotoPageRequest,
        selection: PhotoSelection,
        tag: String
    ): PhotoPage {
        val photos = mapper.createArrayNode()
        val uploader = PhotoAssetUploader(context, mapper)
        var matchedCount = 0
        var hasMore = false
        queryImageCursor(context, request, selection)?.use { cursor ->
            val reader = PhotoCursorReader(cursor)
            while (cursor.moveToNext()) {
                matchedCount += 1
                if (photos.size() >= request.limit) {
                    hasMore = true
                    break
                }
                photos.add(readPhotoNode(context, mapper, uploader, reader, request.maxDim, tag))
            }
        }
        return PhotoPage(photos = photos, matchedCount = matchedCount, hasMore = hasMore)
    }

    fun querySpecialImages(
        context: Context,
        mapper: ObjectMapper,
        limit: Int,
        matchArg: String,
        tag: String
    ): ArrayNode {
        val photos = mapper.createArrayNode()
        val uploader = PhotoAssetUploader(context, mapper)
        querySpecialCursor(context, limit, matchArg)?.use { cursor ->
            val reader = PhotoCursorReader(cursor)
            while (cursor.moveToNext() && photos.size() < limit) {
                photos.add(readPhotoNode(context, mapper, uploader, reader, MAX_MAX_DIM, tag))
            }
        }
        return photos
    }

    fun nextArgs(mapper: ObjectMapper, fields: List<Pair<String, Any?>>): ObjectNode =
        mapper.createObjectNode().apply { putFields(this, fields) }

    fun gridResult(
        mapper: ObjectMapper,
        page: PhotoPage,
        request: PhotoPageRequest,
        nextArgs: ObjectNode?,
        rootFields: List<Pair<String, Any?>> = emptyList(),
        summaryFields: List<Pair<String, Any?>> = emptyList()
    ): ObjectNode {
        val result = mapper.createObjectNode()
        putFields(result, rootFields)
        result.set<JsonNode>("photos", page.photos)
        putPageFields(result, page, request, nextArgs)
        result.set<ObjectNode>("pagination", paginationNode(mapper, page, request, nextArgs))
        result.set<ObjectNode>("display", displayNode(mapper, page, request))
        result.set<ObjectNode>("summary", summaryNode(mapper, page, request, summaryFields))
        return result
    }

    fun filteredGridResult(query: PhotoFilteredGridQuery): ObjectNode {
        val request = pageRequest(query.args)
        val page = queryImages(query.context, query.mapper, request, selection(query.selectionFields), query.tag)
        val nextOffset = request.offset + page.photos.size()
        val nextArgs = if (page.hasMore) nextArgs(query.mapper, query.nextArgFields(request, nextOffset)) else null
        return gridResult(
            mapper = query.mapper,
            page = page,
            request = request,
            nextArgs = nextArgs,
            rootFields = query.rootFields,
            summaryFields = query.summaryFields
        )
    }

    private fun queryImageCursor(
        context: Context,
        request: PhotoPageRequest,
        selection: PhotoSelection
    ): Cursor? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imageProjection,
                modernQueryArgs(request, selection),
                null
            )
        } else {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imageProjection,
                selection.sql,
                selection.args,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC LIMIT ${request.queryLimit} OFFSET ${request.offset}"
            )
        }
    }

    private fun querySpecialCursor(context: Context, limit: Int, matchArg: String): Cursor? {
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection,
            specialQueryArgs(limit, matchArg),
            null
        )
    }

    private fun modernQueryArgs(request: PhotoPageRequest, selection: PhotoSelection): Bundle =
        Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_TAKEN))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, request.queryLimit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, request.offset)
            selection.sql?.let {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, requireNotNull(selection.args))
            }
        }

    private fun specialQueryArgs(limit: Int, matchArg: String): Bundle =
        Bundle().apply {
            putInt(matchArg, MediaStore.MATCH_ONLY)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_TAKEN))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }

    private fun readPhotoNode(
        context: Context,
        mapper: ObjectMapper,
        uploader: PhotoAssetUploader,
        reader: PhotoCursorReader,
        maxDim: Int,
        tag: String
    ): ObjectNode {
        val row = reader.current()
        val photo = mapper.createObjectNode()
        photo.put("id", row.id.toString())
        photo.put("name", row.name)
        photo.put("date_taken_ms", row.dateTakenMs)
        photo.put("size_bytes", row.sizeBytes)
        photo.put("date_modified_sec", row.dateModifiedSec)
        encodeImage(context, row, maxDim, tag)?.let { image ->
            val upload = uploader.uploadDisplayJpeg(row.id, row.name, image)
            PhotoAssetUploader.putUploadFields(photo, upload, image)
        }
        return photo
    }

    private fun encodeImage(
        context: Context,
        row: PhotoRow,
        maxDim: Int,
        tag: String
    ): PhotoToolUtils.EncodedPhoto? {
        return try {
            PhotoToolUtils.encodedDisplayPhoto(
                context = context,
                id = row.id,
                maxDim = maxDim,
                quality = 85,
                sourceModifiedSec = row.dateModifiedSec,
                sourceSizeBytes = row.sizeBytes
            )
        } catch (e: Exception) {
            Log.w(tag, "image encode failed for id=${row.id}: ${e.message}")
            null
        }
    }

    private fun putPageFields(
        result: ObjectNode,
        page: PhotoPage,
        request: PhotoPageRequest,
        nextArgs: ObjectNode?
    ) {
        result.put("count", page.photos.size())
        result.put("requested_limit", request.requestedLimit)
        result.put("limit", request.limit)
        result.put("offset", request.offset)
        result.put("max_dim", request.maxDim)
        result.put("has_more", page.hasMore)
        result.put("truncated_by_safety_cap", request.truncatedBySafetyCap)
        if (request.truncatedBySafetyCap) result.put("safety_cap", request.safetyCap)
        result.put("matched_count_in_window", page.matchedCount)
        nextArgs?.let {
            result.put("next_offset", nextOffset(page, request))
            result.set<ObjectNode>("next_args", it)
        }
    }

    private fun paginationNode(
        mapper: ObjectMapper,
        page: PhotoPage,
        request: PhotoPageRequest,
        nextArgs: ObjectNode?
    ): ObjectNode {
        return mapper.createObjectNode().apply {
            put("offset", request.offset)
            put("requested_limit", request.requestedLimit)
            put("limit", request.limit)
            put("max_dim", request.maxDim)
            put("returned_count", page.photos.size())
            put("start_index", if (page.photos.size() == 0) 0 else request.offset + 1)
            put("end_index", nextOffset(page, request))
            put("has_more", page.hasMore)
            put("truncated_by_safety_cap", request.truncatedBySafetyCap)
            if (request.truncatedBySafetyCap) put("safety_cap", request.safetyCap)
            nextArgs?.let {
                put("next_offset", nextOffset(page, request))
                set<ObjectNode>("next_args", it)
            }
        }
    }

    private fun displayNode(mapper: ObjectMapper, page: PhotoPage, request: PhotoPageRequest): ObjectNode =
        mapper.createObjectNode().apply {
            put("policy", "show_grid")
            put("page", if (page.photos.size() == 0) "0" else "${request.offset + 1}-${nextOffset(page, request)}")
            put("has_more", page.hasMore)
            if (page.hasMore) put("next_offset", nextOffset(page, request))
        }

    private fun summaryNode(
        mapper: ObjectMapper,
        page: PhotoPage,
        request: PhotoPageRequest,
        summaryFields: List<Pair<String, Any?>>
    ): ObjectNode {
        return mapper.createObjectNode().apply {
            putFields(this, summaryFields)
            put("count", page.photos.size())
            put("requested_limit", request.requestedLimit)
            put("limit", request.limit)
            put("max_dim", request.maxDim)
            put("offset", request.offset)
            put("has_more", page.hasMore)
            put("truncated_by_safety_cap", request.truncatedBySafetyCap)
            if (request.truncatedBySafetyCap) put("safety_cap", request.safetyCap)
            if (page.hasMore) put("next_offset", nextOffset(page, request))
        }
    }

    private fun nextOffset(page: PhotoPage, request: PhotoPageRequest): Int =
        request.offset + page.photos.size()

    private fun putFields(node: ObjectNode, fields: List<Pair<String, Any?>>) {
        fields.forEach { (name, value) ->
            when (value) {
                null -> Unit
                is Int -> node.put(name, value)
                is Long -> node.put(name, value)
                is String -> node.put(name, value)
                is Boolean -> node.put(name, value)
                is JsonNode -> node.set<JsonNode>(name, value)
                else -> node.put(name, value.toString())
            }
        }
    }

    private data class PhotoRow(
        val id: Long,
        val name: String,
        val dateTakenMs: Long,
        val dateModifiedSec: Long,
        val sizeBytes: Long
    )

    private class PhotoCursorReader(private val cursor: Cursor) {
        private val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        private val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        private val dateIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
        private val modifiedIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
        private val sizeIdx = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

        fun current(): PhotoRow {
            val id = cursor.getLong(idIdx)
            return PhotoRow(
                id = id,
                name = cursor.getString(nameIdx) ?: "image_$id",
                dateTakenMs = longOrZero(dateIdx),
                dateModifiedSec = longOrZero(modifiedIdx),
                sizeBytes = longOrZero(sizeIdx)
            )
        }

        private fun longOrZero(index: Int): Long =
            if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
    }
}
