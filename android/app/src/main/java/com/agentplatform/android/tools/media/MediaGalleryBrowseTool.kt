package com.agentplatform.android.tools.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.data.AppPrefs
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class MediaGalleryBrowseTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {
    private val appPrefs = AppPrefs(context)

    override val name: String = "media.gallery.browse"

    override val description: String = """
        Browse the user's phone gallery like the native Gallery app, not just
        raw folders. Use `view=albums` for a gallery home page with sections
        such as Common, Albums, and More. Use returned `browse_args` to enter a
        category or album. Use `view=photos` for the timeline/all-media stream.

        Basic supported categories: all, camera, screenshots_recordings,
        videos, recent_deleted, documents, gif, custom. Pet/travel/memories
        style private AI groupings are intentionally not exposed by this tool.
    """.trimIndent()

    override val toolClass: String = "search"
    override val resultType: String = "results"
    override val defaultDisplayPolicy: String = "show_gallery"

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "view": {
              "type": "string",
              "enum": ["albums", "photos", "timeline", "category", "album"],
              "default": "albums",
              "description": "albums returns the gallery entry page; photos/timeline returns all media by time; category opens a built-in category; album opens a bucket album."
            },
            "category": {
              "type": "string",
              "enum": ["all", "camera", "screenshots_recordings", "videos", "recent_deleted", "documents", "gif", "custom"],
              "description": "Built-in gallery category when view=category."
            },
            "bucket_id": {
              "type": "string",
              "description": "MediaStore bucket id from an album entry when view=album."
            },
            "limit": {
              "type": "integer",
              "minimum": 1,
              "maximum": 80,
              "default": 20,
              "description": "Number of media items to return for media-grid views."
            },
            "offset": {
              "type": "integer",
              "minimum": 0,
              "default": 0,
              "description": "Number of matching media items to skip."
            },
            "max_dim": {
              "type": "integer",
              "minimum": 128,
              "maximum": 640,
              "default": 256,
              "description": "Thumbnail edge size for lazy thumbnail URLs. Fetch the original/display image separately with returned open_original args."
            }
          }
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        val view = args.path("view").asText("albums").lowercase(Locale.ROOT).trim().ifBlank { "albums" }
        val normalizedView = if (view == "timeline") "photos" else view
        val requestedLimit = args.path("limit").asInt(DEFAULT_LIMIT).coerceAtLeast(1)
        val limit = requestedLimit.coerceAtMost(MAX_RESULTS_PER_CALL)
        val offset = args.path("offset").asInt(0).coerceAtLeast(0)
        val maxDim = args.path("max_dim").asInt(DEFAULT_MAX_DIM).coerceIn(MIN_MAX_DIM, MAX_MAX_DIM)
        val page = GridPage(requestedLimit, limit, offset, maxDim)

        when (normalizedView) {
            "albums" -> buildAlbumsHome(args, maxDim)
            "photos" -> buildMediaGrid(
                GridRequest(
                    source = args,
                    route = GridRoute(view = "photos", title = "照片", category = "all", bucketId = null),
                    filters = GridFilters(
                        includePhotos = true,
                        includeVideos = true,
                        photoFilter = PhotoFilter.None,
                        videoFilter = VideoFilter.None
                    ),
                    page = page
                )
            )
            "category" -> {
                val category = args.path("category").asText("all").lowercase(Locale.ROOT).trim().ifBlank { "all" }
                buildCategoryGrid(args, category, page)
            }
            "album" -> {
                val bucketId = args.path("bucket_id").asText("").trim()
                require(bucketId.isNotEmpty()) { "bucket_id is required when view=album" }
                buildMediaGrid(
                    GridRequest(
                        source = args,
                        route = GridRoute(
                            view = "album",
                            title = args.path("title").asText("相册").ifBlank { "相册" },
                            category = null,
                            bucketId = bucketId
                        ),
                        filters = GridFilters(
                            includePhotos = true,
                            includeVideos = true,
                            photoFilter = PhotoFilter.Bucket(bucketId),
                            videoFilter = VideoFilter.Bucket(bucketId)
                        ),
                        page = page
                    )
                )
            }
            else -> throw IllegalArgumentException("unsupported gallery view: $view")
        }
    }

    private fun buildAlbumsHome(request: JsonNode, maxDim: Int): JsonNode {
        val coverMaxDim = argsCoverMaxDim(request, maxDim)
        val summary = GallerySummary(
            photoCount = countPhotos(PhotoFilter.None),
            videoCount = countVideos(VideoFilter.None),
            cameraCount = countPhotos(PhotoFilter.Camera) + countVideos(VideoFilter.Camera),
            screenshotsRecordingsCount = countPhotos(PhotoFilter.ScreenshotsRecordings) +
                countVideos(VideoFilter.ScreenshotsRecordings),
            recentDeletedCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                countPhotos(PhotoFilter.RecentDeleted) + countVideos(VideoFilter.RecentDeleted)
            } else 0,
            documentsCount = countPhotos(PhotoFilter.Documents),
            gifCount = countPhotos(PhotoFilter.Gif),
            customCount = countPhotos(PhotoFilter.Custom)
        )

        val sections = mapper.createArrayNode()
        sections.add(section("常用").apply {
            set<ArrayNode>("entries", mapper.createArrayNode().apply {
                add(entry("all", "全部照片", summary.photoCount + summary.videoCount, "category", "all", maxDim).apply {
                    setCover(this, coverFor(PhotoFilter.None, VideoFilter.None, includePhotos = true, includeVideos = true, coverMaxDim))
                })
                add(entry("camera", "相机", summary.cameraCount, "category", "camera", maxDim).apply {
                    setCover(this, coverFor(PhotoFilter.Camera, VideoFilter.Camera, includePhotos = true, includeVideos = true, coverMaxDim))
                })
                add(entry("screenshots_recordings", "截屏录屏", summary.screenshotsRecordingsCount, "category", "screenshots_recordings", maxDim).apply {
                    setCover(this, coverFor(PhotoFilter.ScreenshotsRecordings, VideoFilter.ScreenshotsRecordings, includePhotos = true, includeVideos = true, coverMaxDim))
                })
                add(entry("videos", "视频", summary.videoCount, "category", "videos", maxDim).apply {
                    setCover(this, coverFor(PhotoFilter.None, VideoFilter.None, includePhotos = false, includeVideos = true, coverMaxDim))
                })
            })
        })

        val albumEntries = mapper.createArrayNode()
        listAlbums(limit = ALBUM_ENTRY_LIMIT, maxDim = maxDim, coverMaxDim = coverMaxDim).forEach { albumEntries.add(it) }
        sections.add(section("相册").apply {
            set<ArrayNode>("entries", albumEntries)
        })

        sections.add(section("更多").apply {
            set<ArrayNode>("entries", mapper.createArrayNode().apply {
                add(entry("recent_deleted", "最近删除", summary.recentDeletedCount, "category", "recent_deleted", maxDim).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setCover(this, coverFor(PhotoFilter.RecentDeleted, VideoFilter.RecentDeleted, includePhotos = true, includeVideos = true, coverMaxDim))
                    }
                })
                add(entry("documents", "文档", summary.documentsCount, "category", "documents", maxDim).apply {
                    setCover(this, coverFor(PhotoFilter.Documents, VideoFilter.None, includePhotos = true, includeVideos = false, coverMaxDim))
                })
                add(entry("gif", "GIF格式", summary.gifCount, "category", "gif", maxDim).apply {
                    setCover(this, coverFor(PhotoFilter.Gif, VideoFilter.None, includePhotos = true, includeVideos = false, coverMaxDim))
                })
                add(entry("custom", "自定义", summary.customCount, "category", "custom", maxDim).apply {
                    setCover(this, coverFor(PhotoFilter.Custom, VideoFilter.Custom, includePhotos = true, includeVideos = true, coverMaxDim))
                })
            })
        })

        val result = mapper.createObjectNode()
        result.put("view", "albums")
        result.put("title", "影集")
        result.put("count", summary.photoCount + summary.videoCount)
        result.set<ArrayNode>("sections", sections)
        result.set<ObjectNode>("summary", mapper.createObjectNode().apply {
            put("photo_count", summary.photoCount)
            put("video_count", summary.videoCount)
            put("all_count", summary.photoCount + summary.videoCount)
            put("camera_count", summary.cameraCount)
            put("screenshots_recordings_count", summary.screenshotsRecordingsCount)
            put("recent_deleted_count", summary.recentDeletedCount)
            put("documents_count", summary.documentsCount)
            put("gif_count", summary.gifCount)
            put("custom_count", summary.customCount)
        })
        result.set<ObjectNode>("display", mapper.createObjectNode().apply {
            put("policy", "show_gallery")
            put("mode", "sections")
        })
        return ToolResultEnvelope.applyStandardFields(
            mapper = mapper,
            tool = this@MediaGalleryBrowseTool,
            result = result,
            ok = true,
            resultType = "results",
            displayPolicy = "show_gallery",
            request = request
        )
    }

    private fun buildCategoryGrid(
        request: JsonNode,
        category: String,
        page: GridPage
    ): JsonNode {
        return when (category) {
            "all" -> buildMediaGrid(categoryGridRequest(
                request,
                category,
                "全部照片",
                GridFilters(true, true, PhotoFilter.None, VideoFilter.None),
                page
            ))
            "camera" -> buildMediaGrid(categoryGridRequest(
                request,
                category,
                "相机",
                GridFilters(true, true, PhotoFilter.Camera, VideoFilter.Camera),
                page
            ))
            "screenshots_recordings" -> buildMediaGrid(categoryGridRequest(
                request,
                category,
                "截屏录屏",
                GridFilters(true, true, PhotoFilter.ScreenshotsRecordings, VideoFilter.ScreenshotsRecordings),
                page
            ))
            "videos" -> buildMediaGrid(categoryGridRequest(
                request,
                category,
                "视频",
                GridFilters(false, true, PhotoFilter.None, VideoFilter.None),
                page
            ))
            "recent_deleted" -> recentDeletedGrid(request, category, page)
            "documents" -> buildMediaGrid(categoryGridRequest(
                request,
                category,
                "文档",
                GridFilters(true, false, PhotoFilter.Documents, VideoFilter.None),
                page
            ))
            "gif" -> buildMediaGrid(categoryGridRequest(
                request,
                category,
                "GIF格式",
                GridFilters(true, false, PhotoFilter.Gif, VideoFilter.None),
                page
            ))
            "custom" -> buildMediaGrid(categoryGridRequest(
                request,
                category,
                "自定义",
                GridFilters(true, true, PhotoFilter.Custom, VideoFilter.Custom),
                page
            ))
            else -> throw IllegalArgumentException("unsupported gallery category: $category")
        }
    }

    private fun recentDeletedGrid(request: JsonNode, category: String, page: GridPage): JsonNode {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "recent_deleted requires Android 11 or newer" }
        return buildMediaGrid(categoryGridRequest(
            request,
            category,
            "最近删除",
            GridFilters(true, true, PhotoFilter.RecentDeleted, VideoFilter.RecentDeleted),
            page
        ))
    }

    private fun categoryGridRequest(
        request: JsonNode,
        category: String,
        title: String,
        filters: GridFilters,
        page: GridPage
    ): GridRequest =
        GridRequest(
            source = request,
            route = GridRoute(view = "category", title = title, category = category, bucketId = null),
            filters = filters,
            page = page
        )

    private fun buildMediaGrid(grid: GridRequest): JsonNode {
        val candidates = gridCandidates(grid.filters, grid.page)
        val mediaPage = visibleMediaPage(candidates, grid.page)
        val mutationAction = if (grid.route.category == "recent_deleted") {
            MediaMutationAction.Restore
        } else {
            MediaMutationAction.Trash
        }
        val items = gridItems(mediaPage.visible, grid.page, mutationAction)
        val nextArgs = nextGridArgs(grid, items.size())
        val result = gridResult(grid, items, mediaPage.hasMore, nextArgs)
        return ToolResultEnvelope.applyStandardFields(
            mapper = mapper,
            tool = this@MediaGalleryBrowseTool,
            result = result,
            ok = true,
            resultType = "results",
            displayPolicy = "show_gallery",
            request = grid.source
        )
    }

    private fun gridCandidates(filters: GridFilters, page: GridPage): List<MediaCandidate> {
        val fetch = (page.offset + page.limit + 1).coerceAtMost(MAX_MERGE_WINDOW)
        val candidates = mutableListOf<MediaCandidate>()
        if (filters.includePhotos) candidates += queryPhotos(filters.photoFilter, fetch, 0)
        if (filters.includeVideos) candidates += queryVideos(filters.videoFilter, fetch, 0)
        return candidates.sortedByDescending { it.sortMs }
    }

    private fun visibleMediaPage(candidates: List<MediaCandidate>, page: GridPage): GridMediaPage {
        val window = candidates.drop(page.offset).take(page.limit + 1)
        return GridMediaPage(
            visible = window.take(page.limit),
            hasMore = window.size > page.limit
        )
    }

    private fun gridItems(
        visible: List<MediaCandidate>,
        page: GridPage,
        mutationAction: MediaMutationAction
    ): ArrayNode {
        val items = mapper.createArrayNode()
        visible.forEachIndexed { index, candidate ->
            items.add(candidate.item.apply {
                putThumbnailUrl(this, candidate.mediaType, candidate.id, page.maxDim)
                put("display_index", page.offset + index + 1)
                put("media_ref", "media://${candidate.mediaType}/${candidate.id}")
                putMutationAction(this, candidate.mediaType, candidate.id.toLong(), mutationAction)
            })
        }
        return items
    }

    private fun nextGridArgs(grid: GridRequest, returnedCount: Int): ObjectNode =
        mapper.createObjectNode().apply {
            put("view", grid.route.view)
            grid.route.category?.let { put("category", it) }
            grid.route.bucketId?.let { put("bucket_id", it) }
            put("limit", grid.page.limit)
            put("offset", grid.page.offset + returnedCount)
            put("max_dim", grid.page.maxDim)
        }

    private fun gridResult(
        grid: GridRequest,
        items: ArrayNode,
        hasMore: Boolean,
        nextArgs: ObjectNode
    ): ObjectNode {
        val result = mapper.createObjectNode()
        val route = grid.route
        val page = grid.page
        result.put("view", route.view)
        result.put("title", route.title)
        route.category?.let { result.put("category", it) }
        route.bucketId?.let { result.put("bucket_id", it) }
        result.set<ArrayNode>("items", items)
        result.put("count", items.size())
        result.put("requested_limit", page.requestedLimit)
        result.put("limit", page.limit)
        result.put("offset", page.offset)
        result.put("max_dim", page.maxDim)
        result.put("has_more", hasMore)
        result.set<ObjectNode>("pagination", paginationNode(page, items.size(), hasMore, nextArgs))
        result.set<ObjectNode>("display", gridDisplayNode(page, items.size(), hasMore))
        result.set<ObjectNode>("selection", selectionNode())
        return result
    }

    private fun paginationNode(
        page: GridPage,
        itemCount: Int,
        hasMore: Boolean,
        nextArgs: ObjectNode
    ): ObjectNode =
        mapper.createObjectNode().apply {
            put("offset", page.offset)
            put("requested_limit", page.requestedLimit)
            put("limit", page.limit)
            put("returned_count", itemCount)
            put("start_index", if (itemCount == 0) 0 else page.offset + 1)
            put("end_index", page.offset + itemCount)
            put("has_more", hasMore)
            if (hasMore) {
                put("next_offset", page.offset + itemCount)
                set<ObjectNode>("next_args", nextArgs)
            }
        }

    private fun gridDisplayNode(page: GridPage, itemCount: Int, hasMore: Boolean): ObjectNode =
        mapper.createObjectNode().apply {
            put("policy", "show_gallery")
            put("mode", "grid")
            put("page", if (itemCount == 0) "0" else "${page.offset + 1}-${page.offset + itemCount}")
            put("has_more", hasMore)
            if (hasMore) put("next_offset", page.offset + itemCount)
        }

    private fun selectionNode(): ObjectNode =
        mapper.createObjectNode().apply {
            put("source_tool", name)
            put("recommended_reference", "Use media_ref, media_type and id from selected items.")
        }

    private fun listAlbums(limit: Int, maxDim: Int, coverMaxDim: Int): List<ObjectNode> {
        val albums = linkedMapOf<String, AlbumBucket>()
        collectAlbumBuckets(albums, "photo")
        collectAlbumBuckets(albums, "video")
        return albums.values
            .sortedByDescending { it.latestMs }
            .take(limit)
            .map { album ->
                mapper.createObjectNode().apply {
                    put("entry_type", "album")
                    put("key", "album:${album.bucketId}")
                    put("title", album.name)
                    put("count", album.count)
                    put("bucket_id", album.bucketId)
                    put("latest_date_ms", album.latestMs)
                    put("latest_media_type", album.latestMediaType)
                    setCover(
                        this,
                        coverFor(
                            PhotoFilter.Bucket(album.bucketId),
                            VideoFilter.Bucket(album.bucketId),
                            includePhotos = true,
                            includeVideos = true,
                            coverMaxDim = coverMaxDim
                        )
                    )
                    set<ObjectNode>("browse_args", mapper.createObjectNode().apply {
                        put("view", "album")
                        put("bucket_id", album.bucketId)
                        put("title", album.name)
                        put("limit", FAST_GRID_LIMIT)
                        put("offset", 0)
                        put("max_dim", fastGridMaxDim(maxDim))
                    })
                }
            }
    }

    private fun argsCoverMaxDim(request: JsonNode, maxDim: Int): Int =
        request.path("cover_max_dim").asInt((maxDim / 2).coerceAtLeast(COVER_MAX_DIM))
            .coerceIn(COVER_MIN_DIM, COVER_MAX_DIM)

    private fun setCover(entry: ObjectNode, cover: CoverMedia?) {
        if (cover == null) return
        entry.put("cover_media_type", cover.mediaType)
        entry.put("cover_id", cover.id)
        entry.put("cover_date_ms", cover.sortMs)
        putThumbnailUrl(entry, cover.mediaType, cover.id, cover.maxDim, "cover_thumb_url")
    }

    private fun coverFor(
        photoFilter: PhotoFilter,
        videoFilter: VideoFilter,
        includePhotos: Boolean,
        includeVideos: Boolean,
        coverMaxDim: Int
    ): CoverMedia? {
        val photo = if (includePhotos) queryPhotoCover(photoFilter, coverMaxDim) else null
        val video = if (includeVideos) queryVideoCover(videoFilter, coverMaxDim) else null
        return listOfNotNull(photo, video).maxByOrNull { it.sortMs }
    }

    private fun queryPhotoCover(filter: PhotoFilter, coverMaxDim: Int): CoverMedia? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        return try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                queryArgs(
                    sortColumn = MediaStore.Images.Media.DATE_TAKEN,
                    limit = 1,
                    offset = 0,
                    selection = filter.selection(),
                    selectionArgs = filter.selectionArgs(),
                    trashMatch = filter.trashMatch()
                ),
                null
            )?.use { c ->
                if (!c.moveToNext()) return@use null
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val modifiedIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                val id = c.getLong(idIdx)
                CoverMedia(
                    mediaType = "photo",
                    id = id.toString(),
                    sortMs = sortTime(longColumn(c, dateIdx), longColumn(c, modifiedIdx)),
                    maxDim = coverMaxDim
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "photo cover query failed: ${e.message}")
            null
        }
    }

    private fun queryVideoCover(filter: VideoFilter, coverMaxDim: Int): CoverMedia? {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_MODIFIED
        )
        return try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                queryArgs(
                    sortColumn = MediaStore.Video.Media.DATE_TAKEN,
                    limit = 1,
                    offset = 0,
                    selection = filter.selection(),
                    selectionArgs = filter.selectionArgs(),
                    trashMatch = filter.trashMatch()
                ),
                null
            )?.use { c ->
                if (!c.moveToNext()) return@use null
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dateIdx = c.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
                val modifiedIdx = c.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val id = c.getLong(idIdx)
                CoverMedia(
                    mediaType = "video",
                    id = id.toString(),
                    sortMs = sortTime(longColumn(c, dateIdx), longColumn(c, modifiedIdx)),
                    maxDim = coverMaxDim
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "video cover query failed: ${e.message}")
            null
        }
    }

    private fun collectAlbumBuckets(
        albums: LinkedHashMap<String, AlbumBucket>,
        mediaType: String
    ) {
        val isVideo = mediaType == "video"
        val uri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val bucketIdColumn = if (isVideo) MediaStore.Video.Media.BUCKET_ID else MediaStore.Images.Media.BUCKET_ID
        val bucketNameColumn = if (isVideo) MediaStore.Video.Media.BUCKET_DISPLAY_NAME else MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        val dateColumn = if (isVideo) MediaStore.Video.Media.DATE_TAKEN else MediaStore.Images.Media.DATE_TAKEN
        val modifiedColumn = if (isVideo) MediaStore.Video.Media.DATE_MODIFIED else MediaStore.Images.Media.DATE_MODIFIED
        val projection = arrayOf(bucketIdColumn, bucketNameColumn, dateColumn, modifiedColumn)
        context.contentResolver.query(
            uri,
            projection,
            queryArgs(
                sortColumn = dateColumn,
                limit = ALBUM_SCAN_LIMIT,
                offset = 0,
                selection = null,
                selectionArgs = null,
                trashMatch = TrashMatch.Exclude
            ),
            null
        )?.use { c ->
            val bucketIdIdx = c.getColumnIndex(bucketIdColumn)
            val bucketNameIdx = c.getColumnIndex(bucketNameColumn)
            val dateIdx = c.getColumnIndex(dateColumn)
            val modifiedIdx = c.getColumnIndex(modifiedColumn)
            while (c.moveToNext()) {
                val bucketId = stringColumn(c, bucketIdIdx) ?: continue
                val bucketName = stringColumn(c, bucketNameIdx)?.takeIf { it.isNotBlank() } ?: "(未命名)"
                val latestMs = sortTime(longColumn(c, dateIdx), longColumn(c, modifiedIdx))
                val existing = albums[bucketId]
                if (existing == null) {
                    albums[bucketId] = AlbumBucket(bucketId, bucketName, 1, latestMs, mediaType)
                } else {
                    existing.count += 1
                    if (latestMs > existing.latestMs) {
                        existing.latestMs = latestMs
                        existing.latestMediaType = mediaType
                    }
                }
            }
        }
    }

    private fun entry(
        key: String,
        title: String,
        count: Int,
        browseView: String,
        category: String,
        maxDim: Int
    ): ObjectNode {
        return mapper.createObjectNode().apply {
            put("entry_type", "category")
            put("key", "category:$key")
            put("title", title)
            put("count", count)
            put("category", category)
            set<ObjectNode>("browse_args", mapper.createObjectNode().apply {
                put("view", browseView)
                put("category", category)
                put("limit", FAST_GRID_LIMIT)
                put("offset", 0)
                put("max_dim", fastGridMaxDim(maxDim))
            })
        }
    }

    private fun section(title: String): ObjectNode =
        mapper.createObjectNode().apply { put("title", title) }

    private fun queryPhotos(filter: PhotoFilter, limit: Int, offset: Int): List<MediaCandidate> {
        val out = mutableListOf<MediaCandidate>()
        mediaCursor(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            PHOTO_PROJECTION,
            MediaStore.Images.Media.DATE_TAKEN,
            filter,
            limit,
            offset
        )?.use { cursor ->
            val columns = PhotoColumns(cursor)
            while (cursor.moveToNext() && out.size < limit) {
                out += mediaCandidate(readPhotoRow(cursor, columns))
            }
        }
        return out
    }

    private fun queryVideos(filter: VideoFilter, limit: Int, offset: Int): List<MediaCandidate> {
        val out = mutableListOf<MediaCandidate>()
        mediaCursor(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            VIDEO_PROJECTION,
            MediaStore.Video.Media.DATE_TAKEN,
            filter,
            limit,
            offset
        )?.use { cursor ->
            val columns = VideoColumns(cursor)
            while (cursor.moveToNext() && out.size < limit) {
                out += mediaCandidate(readVideoRow(cursor, columns))
            }
        }
        return out
    }

    private fun mediaCursor(
        uri: android.net.Uri,
        projection: Array<String>,
        sortColumn: String,
        filter: GalleryFilter,
        limit: Int,
        offset: Int
    ): Cursor? =
        context.contentResolver.query(
            uri,
            projection,
            queryArgs(
                sortColumn = sortColumn,
                limit = limit,
                offset = offset,
                selection = filter.selection(),
                selectionArgs = filter.selectionArgs(),
                trashMatch = filter.trashMatch()
            ),
            null
        )

    private fun readPhotoRow(cursor: Cursor, columns: PhotoColumns): GalleryMediaRow {
        val id = cursor.getLong(columns.id)
        return GalleryMediaRow(
            mediaType = "photo",
            id = id,
            name = cursor.getString(columns.name) ?: "image_$id",
            time = GalleryTime(longColumn(cursor, columns.date), longColumn(cursor, columns.modified)),
            bucket = GalleryBucketInfo(stringColumn(cursor, columns.bucketId), stringColumn(cursor, columns.bucketName)),
            details = GalleryMediaDetails(
                sizeBytes = longColumn(cursor, columns.size),
                mimeType = stringColumn(cursor, columns.mime),
                width = longColumn(cursor, columns.width),
                height = longColumn(cursor, columns.height),
                relativePath = stringColumn(cursor, columns.relativePath),
                durationMs = null
            )
        )
    }

    private fun readVideoRow(cursor: Cursor, columns: VideoColumns): GalleryMediaRow {
        val id = cursor.getLong(columns.id)
        return GalleryMediaRow(
            mediaType = "video",
            id = id,
            name = cursor.getString(columns.name) ?: "video_$id",
            time = GalleryTime(longColumn(cursor, columns.date), longColumn(cursor, columns.modified)),
            bucket = GalleryBucketInfo(stringColumn(cursor, columns.bucketId), stringColumn(cursor, columns.bucketName)),
            details = GalleryMediaDetails(
                sizeBytes = longColumn(cursor, columns.size),
                mimeType = stringColumn(cursor, columns.mime),
                width = longColumn(cursor, columns.width),
                height = longColumn(cursor, columns.height),
                relativePath = stringColumn(cursor, columns.relativePath),
                durationMs = longColumn(cursor, columns.duration)
            )
        )
    }

    private fun mediaCandidate(row: GalleryMediaRow): MediaCandidate {
        val item = mediaItem(row)
        return MediaCandidate(
            mediaType = row.mediaType,
            id = row.id.toString(),
            sortMs = sortTime(row.time.dateTakenMs, row.time.dateModifiedSec),
            bucketId = row.bucket.id,
            bucketName = row.bucket.name,
            item = item
        )
    }

    private fun mediaItem(row: GalleryMediaRow): ObjectNode {
        val item = mapper.createObjectNode()
        val details = row.details
        item.put("media_type", row.mediaType)
        item.put("id", row.id.toString())
        item.put("name", row.name)
        item.put("date_taken_ms", row.time.dateTakenMs)
        item.put("date_modified_sec", row.time.dateModifiedSec)
        item.put("size_bytes", details.sizeBytes)
        details.durationMs?.let { item.put("duration_ms", it) }
        putOptionalString(item, "bucket_id", row.bucket.id)
        putOptionalString(item, "bucket_name", row.bucket.name)
        putOptionalString(item, "mime_type", details.mimeType)
        putPositiveLong(item, "width", details.width)
        putPositiveLong(item, "height", details.height)
        putOptionalString(item, "relative_path", details.relativePath)
        item.put("preview_kind", "thumbnail")
        item.put("open_original_available", row.mediaType == "photo")
        if (row.mediaType == "photo") putOpenOriginalAction(item, row.id)
        return item
    }

    private fun putOptionalString(item: ObjectNode, field: String, value: String?) {
        if (!value.isNullOrBlank()) item.put(field, value)
    }

    private fun putPositiveLong(item: ObjectNode, field: String, value: Long) {
        if (value > 0L) item.put(field, value)
    }

    private fun queryArgs(
        sortColumn: String,
        limit: Int,
        offset: Int,
        selection: String?,
        selectionArgs: Array<String>?,
        trashMatch: TrashMatch
    ): Bundle {
        return Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(sortColumn))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit.coerceAtLeast(1))
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset.coerceAtLeast(0))
            if (!selection.isNullOrBlank()) {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs ?: emptyArray())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, trashMatch.mediaStoreValue)
            }
        }
    }

    private fun countPhotos(filter: PhotoFilter): Int = count(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, filter)

    private fun countVideos(filter: VideoFilter): Int = count(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, filter)

    private fun count(uri: android.net.Uri, filter: GalleryFilter): Int {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        return try {
            context.contentResolver.query(
                uri,
                projection,
                queryArgs(
                    sortColumn = MediaStore.MediaColumns.DATE_MODIFIED,
                    limit = COUNT_SCAN_LIMIT,
                    offset = 0,
                    selection = filter.selection(),
                    selectionArgs = filter.selectionArgs(),
                    trashMatch = filter.trashMatch()
                ),
                null
            )?.use { it.count } ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "count failed uri=$uri: ${e.message}")
            0
        }
    }

    private fun fastGridMaxDim(maxDim: Int): Int = maxDim.coerceAtMost(FAST_GRID_MAX_DIM)

    private fun putThumbnailUrl(
        item: ObjectNode,
        mediaType: String,
        id: String,
        maxDim: Int,
        fieldName: String = "thumb_url"
    ) {
        val deviceId = appPrefs.deviceId?.takeIf { it.isNotBlank() }?.let { "&deviceId=$it" } ?: ""
        item.put(
            fieldName,
            "/api/chat/media-gallery/thumbnail?mediaType=$mediaType&id=$id&maxDim=${maxDim.coerceIn(MIN_MAX_DIM, MAX_MAX_DIM)}$deviceId"
        )
    }

    private fun putOpenOriginalAction(item: ObjectNode, id: Long) {
        item.set<ObjectNode>("open_original", mapper.createObjectNode().apply {
            put("tool", "photos.get_full")
            set<ObjectNode>("args", mapper.createObjectNode().apply {
                put("id", id.toString())
                put("max_dim", ORIGINAL_MAX_DIM)
            })
        })
    }

    private fun putMutationAction(item: ObjectNode, mediaType: String, id: Long, action: MediaMutationAction) {
        item.set<ObjectNode>(action.fieldName, mapper.createObjectNode().apply {
            put("tool", action.toolName)
            set<ArrayNode>("items", mapper.createArrayNode().apply {
                addObject()
                    .put("media_type", mediaType)
                    .put("id", id.toString())
            })
        })
    }

    private fun longColumn(cursor: android.database.Cursor, index: Int): Long =
        if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L

    private fun stringColumn(cursor: android.database.Cursor, index: Int): String? =
        if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null

    private fun sortTime(dateTakenMs: Long, modifiedSec: Long): Long =
        if (dateTakenMs > 0L) dateTakenMs else modifiedSec * 1000L

    private data class AlbumBucket(
        val bucketId: String,
        var name: String,
        var count: Int,
        var latestMs: Long,
        var latestMediaType: String
    )

    private data class GallerySummary(
        val photoCount: Int,
        val videoCount: Int,
        val cameraCount: Int,
        val screenshotsRecordingsCount: Int,
        val recentDeletedCount: Int,
        val documentsCount: Int,
        val gifCount: Int,
        val customCount: Int
    )

    private data class GridRequest(
        val source: JsonNode,
        val route: GridRoute,
        val filters: GridFilters,
        val page: GridPage
    )

    private data class GridRoute(
        val view: String,
        val title: String,
        val category: String?,
        val bucketId: String?
    )

    private data class GridFilters(
        val includePhotos: Boolean,
        val includeVideos: Boolean,
        val photoFilter: PhotoFilter,
        val videoFilter: VideoFilter
    )

    private data class GridPage(
        val requestedLimit: Int,
        val limit: Int,
        val offset: Int,
        val maxDim: Int
    )

    private data class GridMediaPage(
        val visible: List<MediaCandidate>,
        val hasMore: Boolean
    )

    private data class MediaCandidate(
        val mediaType: String,
        val id: String,
        val sortMs: Long,
        val bucketId: String?,
        val bucketName: String?,
        val item: ObjectNode
    )

    private data class CoverMedia(
        val mediaType: String,
        val id: String,
        val sortMs: Long,
        val maxDim: Int
    )

    private data class GalleryMediaRow(
        val mediaType: String,
        val id: Long,
        val name: String,
        val time: GalleryTime,
        val bucket: GalleryBucketInfo,
        val details: GalleryMediaDetails
    )

    private data class GalleryTime(
        val dateTakenMs: Long,
        val dateModifiedSec: Long
    )

    private data class GalleryBucketInfo(
        val id: String?,
        val name: String?
    )

    private data class GalleryMediaDetails(
        val sizeBytes: Long,
        val mimeType: String?,
        val width: Long,
        val height: Long,
        val relativePath: String?,
        val durationMs: Long?
    )

    private class PhotoColumns(cursor: Cursor) {
        val id: Int = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val name: Int = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val date: Int = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
        val modified: Int = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
        val size: Int = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
        val bucketId: Int = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
        val bucketName: Int = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val mime: Int = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
        val width: Int = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
        val height: Int = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
        val relativePath: Int = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
    }

    private class VideoColumns(cursor: Cursor) {
        val id: Int = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val name: Int = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val date: Int = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
        val modified: Int = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
        val size: Int = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
        val bucketId: Int = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
        val bucketName: Int = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        val mime: Int = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
        val duration: Int = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
        val width: Int = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
        val height: Int = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
        val relativePath: Int = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
    }

    private enum class MediaMutationAction(
        val fieldName: String,
        val toolName: String
    ) {
        Trash("trash", "media.gallery.trash"),
        Restore("restore", "media.gallery.restore")
    }

    private interface GalleryFilter {
        fun selection(): String?
        fun selectionArgs(): Array<String>?
        fun trashMatch(): TrashMatch = TrashMatch.Exclude
    }

    private sealed class PhotoFilter : GalleryFilter {
        object None : PhotoFilter()
        object Camera : PhotoFilter()
        object ScreenshotsRecordings : PhotoFilter()
        object RecentDeleted : PhotoFilter()
        object Documents : PhotoFilter()
        object Gif : PhotoFilter()
        object Custom : PhotoFilter()
        data class Bucket(val bucketId: String) : PhotoFilter()

        override fun selection(): String? = when (this) {
            None, RecentDeleted -> null
            Camera -> "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?"
            ScreenshotsRecordings -> "(${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)"
            Documents -> "(${MediaStore.Images.Media.MIME_TYPE} LIKE ? OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?)"
            Gif -> "${MediaStore.Images.Media.MIME_TYPE} = ?"
            Custom -> "(${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?)"
            is Bucket -> "${MediaStore.Images.Media.BUCKET_ID} = ?"
        }

        override fun selectionArgs(): Array<String>? = when (this) {
            None, RecentDeleted -> null
            Camera -> arrayOf(CAMERA_PATTERN)
            ScreenshotsRecordings -> arrayOf(SCREENSHOT_PATTERN, SCREENSHOT_PATTERN, SCREENSHOT_PATTERN)
            Documents -> arrayOf("%pdf%", DOCUMENTS_PATTERN, DOCUMENTS_PATTERN)
            Gif -> arrayOf("image/gif")
            Custom -> arrayOf(CUSTOM_PATTERN, CUSTOM_PATTERN)
            is Bucket -> arrayOf(bucketId)
        }

        override fun trashMatch(): TrashMatch = if (this is RecentDeleted) TrashMatch.Only else TrashMatch.Exclude
    }

    private sealed class VideoFilter : GalleryFilter {
        object None : VideoFilter()
        object Camera : VideoFilter()
        object ScreenshotsRecordings : VideoFilter()
        object RecentDeleted : VideoFilter()
        object Custom : VideoFilter()
        data class Bucket(val bucketId: String) : VideoFilter()

        override fun selection(): String? = when (this) {
            None, RecentDeleted -> null
            Camera -> "${MediaStore.Video.Media.BUCKET_DISPLAY_NAME} LIKE ?"
            ScreenshotsRecordings -> "(${MediaStore.Video.Media.BUCKET_DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?)"
            Custom -> "(${MediaStore.Video.Media.BUCKET_DISPLAY_NAME} LIKE ? OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?)"
            is Bucket -> "${MediaStore.Video.Media.BUCKET_ID} = ?"
        }

        override fun selectionArgs(): Array<String>? = when (this) {
            None, RecentDeleted -> null
            Camera -> arrayOf(CAMERA_PATTERN)
            ScreenshotsRecordings -> arrayOf(SCREEN_PATTERN, SCREEN_PATTERN, SCREEN_PATTERN)
            Custom -> arrayOf(CUSTOM_PATTERN, CUSTOM_PATTERN)
            is Bucket -> arrayOf(bucketId)
        }

        override fun trashMatch(): TrashMatch = if (this is RecentDeleted) TrashMatch.Only else TrashMatch.Exclude
    }

    private enum class TrashMatch(val mediaStoreValue: Int) {
        Exclude(MediaStore.MATCH_EXCLUDE),
        Only(MediaStore.MATCH_ONLY)
    }

    companion object {
        private const val TAG = "MediaGalleryBrowseTool"
        private const val DEFAULT_LIMIT = 20
        private const val FAST_GRID_LIMIT = 20
        private const val MAX_RESULTS_PER_CALL = 80
        private const val MAX_MERGE_WINDOW = 240
        private const val DEFAULT_MAX_DIM = 256
        private const val FAST_GRID_MAX_DIM = 256
        private const val MIN_MAX_DIM = 128
        private const val MAX_MAX_DIM = 640
        private const val ORIGINAL_MAX_DIM = 2048
        private const val ALBUM_ENTRY_LIMIT = 24
        private const val ALBUM_SCAN_LIMIT = 600
        private const val COUNT_SCAN_LIMIT = 5000
        private const val COVER_MIN_DIM = 128
        private const val COVER_MAX_DIM = 320
        private const val CAMERA_PATTERN = "%Camera%"
        private const val CUSTOM_PATTERN = "%Custom%"
        private const val DOCUMENTS_PATTERN = "%Documents%"
        private const val SCREEN_PATTERN = "%Screen%"
        private const val SCREENSHOT_PATTERN = "%Screenshot%"
        private val PHOTO_PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        private val VIDEO_PROJECTION = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.RELATIVE_PATH
        )
    }
}
