package com.agentplatform.android.tools.photos

import android.content.Context
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Lists cached, uploaded display-sized original image assets inside one
 * specific album bucket.
 */
class PhotosListByAlbumTool(
    context: Context,
    mapper: ObjectMapper,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PhotoGridTool(PhotoGridDefinitions.byAlbum, context, mapper, ioDispatcher)
