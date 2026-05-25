package com.agentplatform.android.tools.photos

import android.content.Context
import com.agentplatform.android.core.tool.Tool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

abstract class PhotoGridTool(
    protected val context: Context,
    protected val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher
) : Tool {

    final override val confirmRequired: Boolean = false

    final override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        PhotoListQueryHelper.standardGridEnvelope(mapper, this@PhotoGridTool, gridResult(args), args)
    }

    protected abstract fun gridResult(args: JsonNode): ObjectNode
}
