package com.agentplatform.android.tools.photos

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.agentplatform.android.core.tool.Tool
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
        photo or `ids` for a batch.
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
            }
          },
          "anyOf": [
            { "required": ["id"] },
            { "required": ["ids"] }
          ]
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = true

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        val ids = PhotoMutationHelpers.parseIds(args)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val approved = MediaStoreRequestBridge.request(
                context,
                MediaStore.createDeleteRequest(
                    context.contentResolver,
                    ids.map { PhotoMutationHelpers.photoUri(it) }
                )
            )
            if (!approved) throw SecurityException("Android media delete confirmation rejected")
            mapper.createObjectNode().apply {
                put("ok", true)
                set<JsonNode>("ids", PhotoMutationHelpers.idsArray(mapper, ids))
                put("deleted_count", ids.size)
            }
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
            mapper.createObjectNode().apply {
                put("ok", failures.size() == 0)
                set<JsonNode>("ids", PhotoMutationHelpers.idsArray(mapper, ids))
                put("deleted_count", deletedCount)
                set<JsonNode>("failures", failures)
            }
        }
    }
}
