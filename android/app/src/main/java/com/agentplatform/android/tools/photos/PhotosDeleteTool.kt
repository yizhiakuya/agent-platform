package com.agentplatform.android.tools.photos

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.media.MediaStoreRequestBridge
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotosDeleteTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {
    override val name: String = "photos.delete"

    override val description: String = """
        Permanently delete existing gallery photos. Prefer photos.trash when the
        user did not explicitly ask for permanent deletion. Pass `id` for one
        photo, `ids` for a batch, or `selection_id` from media.selection.create
        for a previously reviewed set.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "id": {
              "type": "string",
              "description": "Single photo id."
            },
            "ids": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Photo ids. Maximum 100."
            },
            "selection_id": {
              "type": "string",
              "description": "Reusable photo selection id from media.selection.create."
            }
          },
          "anyOf": [
            { "required": ["id"] },
            { "required": ["ids"] },
            { "required": ["selection_id"] }
          ]
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = true

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        val ids = PhotoMutationHelpers.parseIds(context, mapper, args)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val approved = MediaStoreRequestBridge.request(
                context,
                MediaStore.createDeleteRequest(
                    context.contentResolver,
                    ids.map { PhotoMutationHelpers.photoUri(it) }
                )
            )
            if (!approved) throw SecurityException("Android media delete confirmation rejected")
            val result = mapper.createObjectNode().apply {
                set<JsonNode>("ids", PhotoMutationHelpers.idsArray(mapper, ids))
                put("deleted_count", ids.size)
                set<JsonNode>("summary", mapper.createObjectNode().apply {
                    put("deleted_count", ids.size)
                })
            }
            ToolResultEnvelope.applyStandardFields(mapper, this@PhotosDeleteTool, result, ok = true, request = args)
        } else {
            val failures = PhotoMutationHelpers.failuresArray(mapper)
            var deletedCount = 0
            for (id in ids) {
                try {
                    val deleted = PhotoMutationHelpers.deleteWithRecovery(
                        context,
                        PhotoMutationHelpers.photoUri(id)
                    )
                    if (deleted > 0) deletedCount += deleted else failures.addObject()
                        .put("id", id.toString())
                        .put("error", "photo not found or not deleted")
                } catch (e: Exception) {
                    PhotoMutationHelpers.addFailure(failures, id, e)
                }
            }
            val result = mapper.createObjectNode().apply {
                set<JsonNode>("ids", PhotoMutationHelpers.idsArray(mapper, ids))
                put("deleted_count", deletedCount)
                set<JsonNode>("failures", failures)
                set<JsonNode>("summary", mapper.createObjectNode().apply {
                    put("deleted_count", deletedCount)
                    put("failure_count", failures.size())
                })
            }
            ToolResultEnvelope.applyStandardFields(
                mapper,
                this@PhotosDeleteTool,
                result,
                ok = failures.size() == 0,
                request = args
            )
        }
    }
}
