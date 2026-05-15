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

class PhotosTrashTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {
    override val name: String = "photos.trash"

    override val description: String = """
        Move existing gallery photos to Android's trash/recycle bin. Pass `id`
        for one photo or `ids` for a batch. Use photos.restore to restore
        trashed photos. Requires Android 11+.
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw UnsupportedOperationException("trash requires Android 11 or newer")
        }
        val ids = PhotoMutationHelpers.parseIds(args)
        val uris = ids.map { PhotoMutationHelpers.photoUri(it) }
        val approved = MediaStoreRequestBridge.request(
            context,
            MediaStore.createTrashRequest(context.contentResolver, uris, true),
            autoApprove = true
        )
        if (!approved) throw SecurityException("Android media trash confirmation rejected")

        mapper.createObjectNode().apply {
            put("ok", true)
            set<JsonNode>("ids", PhotoMutationHelpers.idsArray(mapper, ids))
            put("trashed", true)
            put("affected_count", ids.size)
        }
    }
}
