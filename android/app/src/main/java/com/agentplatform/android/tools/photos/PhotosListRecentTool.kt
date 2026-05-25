package com.agentplatform.android.tools.photos

import android.content.Context
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Returns the N most-recent photos from the device gallery as cached,
 * uploaded display-sized original JPEG assets. Used by the LLM to answer
 * "show me my recent photos".
 *
 * Permission: requires READ_MEDIA_IMAGES on Android 13+ or legacy
 * READ_EXTERNAL_STORAGE on older versions. If permission is missing, the
 * MediaStore cursor is null and the tool returns an empty array.
 */
class PhotosListRecentTool(
    context: Context,
    mapper: ObjectMapper,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PhotoGridTool(PhotoGridDefinitions.recent, context, mapper, ioDispatcher)
