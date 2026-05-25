package com.agentplatform.android.tools.photos

import android.content.Context
import android.provider.MediaStore
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists recent screenshots, matching either the typical filename pattern
 * (Screenshot_*) or the Screenshots / 截图 bucket — both because Chinese ROMs
 * use varied conventions. Returns cached, uploaded display-sized original
 * image assets.
 */
class PhotosRecentScreenshotsTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {

    override val name: String = "photos.recent_screenshots"

    override val description: String = """
        List recent screenshots as cached display-sized original JPEG asset URLs
        (up to 2048px long edge). Matches common Screenshot filenames
        and Screenshots albums across Android ROMs. Optional name_contains
        narrows within screenshots.
        Choose `limit` from the user's actual request. If you are continuing
        after a previous result, reuse `next_args` or pass `offset`. Use smaller
        `max_dim` (512-768) when browsing many screenshots.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "limit": {
              "type": "integer",
              "minimum": 1,
              "default": 12,
              "description": "Number of screenshots the agent wants in this call. Choose the count that matches the task."
            },
            "offset": {
              "type": "integer",
              "minimum": 0,
              "default": 0,
              "description": "Number of matching screenshots to skip. Use next_args.offset from a previous result to fetch the next page."
            },
            "max_dim": {
              "type": "integer",
              "minimum": 512,
              "maximum": 2048,
              "default": 1024,
              "description": "Long-edge size for returned display images. Use 512-768 for many screenshots; use 1024-2048 for detail."
            },
            "name_contains": {
              "type": "string",
              "description": "Optional secondary filter on the filename within screenshots."
            }
          }
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        val request = PhotoListQueryHelper.pageRequest(args)
        val nameContains = PhotoListQueryHelper.optionalText(args, "name_contains")

        // Three-pronged screenshot heuristic for cross-vendor coverage:
        //   1. DISPLAY_NAME starts with Screenshot
        //   2. BUCKET_DISPLAY_NAME contains Screenshot (English ROM)
        //   3. BUCKET_DISPLAY_NAME contains 截图 (Chinese ROM)
        // Then optionally AND a user-supplied substring.
        val selection = screenshotSelection(nameContains)
        val page = PhotoListQueryHelper.queryImages(context, mapper, request, selection, TAG)
        val nextArgs = if (page.hasMore) nextArgs(request, page.photos.size(), nameContains) else null
        val result = PhotoListQueryHelper.gridResult(
            mapper = mapper,
            page = page,
            request = request,
            nextArgs = nextArgs,
            summaryFields = listOf("name_contains" to nameContains)
        )
        ToolResultEnvelope.applyStandardFields(
            mapper = mapper,
            tool = this@PhotosRecentScreenshotsTool,
            result = result,
            ok = true,
            resultType = "results",
            displayPolicy = "show_grid",
            request = args
        )
    }

    private fun screenshotSelection(nameContains: String?): PhotoSelection {
        val baseClause =
            "(${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? " +
                "OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ? " +
                "OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?)"
        val params = mutableListOf("Screenshot%", "%Screenshot%", "%截图%")
        val clause = if (nameContains == null) {
            baseClause
        } else {
            params += "%$nameContains%"
            "$baseClause AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        }
        return PhotoSelection(clause, params.toTypedArray())
    }

    private fun nextArgs(request: PhotoPageRequest, nextOffset: Int, nameContains: String?) =
        PhotoListQueryHelper.nextArgs(
            mapper,
            listOf(
                "limit" to request.limit,
                "offset" to request.offset + nextOffset,
                "max_dim" to request.maxDim,
                "name_contains" to nameContains
            )
        )

    companion object {
        private const val TAG = "PhotosRecentScreenshotsTool"
    }
}
