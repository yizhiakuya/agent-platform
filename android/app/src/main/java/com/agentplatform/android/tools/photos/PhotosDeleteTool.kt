package com.agentplatform.android.tools.photos

import android.content.Context
import android.os.Build
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.privilege.PrivilegeManager
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotosDeleteTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        val ids = PhotoMutationHelpers.parseIds(context, mapper, args)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) privilegedDelete(args, ids) else legacyDelete(args, ids)
    }

    private fun privilegedDelete(args: JsonNode, ids: List<Long>): JsonNode {
        val mutation = PhotoMutationHelpers.runPrivilegedImageMutation(
            context = context,
            ids = ids,
            shellMutation = { PrivilegeManager.deleteImages(it) },
            manageMediaMutation = { id ->
                context.contentResolver.delete(PhotoMutationHelpers.photoUri(id), null, null)
            }
        )
        if (!mutation.succeeded) return mediaAccessError(args, ids, mutation)
        val result = PhotoMutationHelpers.privilegedMutationSuccess(
            mapper = mapper,
            ids = ids,
            mutation = mutation,
            spec = mutationSpec()
        )
        return ToolResultEnvelope.applyStandardFields(mapper, this@PhotosDeleteTool, result, ok = true, request = args)
    }

    private suspend fun legacyDelete(args: JsonNode, ids: List<Long>): JsonNode {
        val outcome = legacyDeleteOutcome(ids)
        val result = legacyDeleteResult(ids, outcome)
        return ToolResultEnvelope.applyStandardFields(
            mapper,
            this@PhotosDeleteTool,
            result,
            ok = outcome.failures.size() == 0,
            request = args
        )
    }

    private suspend fun legacyDeleteOutcome(ids: List<Long>): LegacyDeleteOutcome {
        val failures = PhotoMutationHelpers.failuresArray(mapper)
        var deletedCount = 0
        for (id in ids) {
            deletedCount += deleteLegacyPhoto(id, failures)
        }
        return LegacyDeleteOutcome(deletedCount, failures)
    }

    private suspend fun deleteLegacyPhoto(id: Long, failures: com.fasterxml.jackson.databind.node.ArrayNode): Int =
        try {
            val deleted = PhotoMutationHelpers.deleteWithRecovery(context, PhotoMutationHelpers.photoUri(id))
            if (deleted > 0) deleted else {
                failures.addObject()
                    .put("id", id.toString())
                    .put("error", "photo not found or not deleted")
                0
            }
        } catch (e: Exception) {
            PhotoMutationHelpers.addFailure(failures, id, e)
            0
        }

    private fun legacyDeleteResult(ids: List<Long>, outcome: LegacyDeleteOutcome): com.fasterxml.jackson.databind.node.ObjectNode {
        return mapper.createObjectNode().apply {
            set<JsonNode>("ids", PhotoMutationHelpers.idsArray(mapper, ids))
            put("deleted_count", outcome.deletedCount)
            set<JsonNode>("failures", outcome.failures)
            set<JsonNode>("summary", legacyDeleteSummary(outcome))
        }
    }

    private fun legacyDeleteSummary(outcome: LegacyDeleteOutcome): com.fasterxml.jackson.databind.node.ObjectNode {
        return mapper.createObjectNode().apply {
            put("deleted_count", outcome.deletedCount)
            put("failure_count", outcome.failures.size())
        }
    }

    private fun mediaAccessError(
        args: JsonNode,
        ids: List<Long>,
        mutation: PhotoMutationHelpers.PrivilegedMutationResult
    ) = PhotoMutationHelpers.privilegedMutationError(
        mapper = mapper,
        tool = this@PhotosDeleteTool,
        args = args,
        ids = ids,
        mutation = mutation,
        spec = mutationSpec()
    )

    private fun mutationSpec() = PhotoMutationHelpers.PrivilegedMutationSpec(
        countKey = "deleted_count",
        unavailableCode = "privileged_media_access_required",
        unavailableMessage = "Deleting photos would require Android's foreground MediaStore confirmation on this device. Configure Shizuku/MANAGE_MEDIA first so the tool can run in the background without a system dialog.",
        unavailableHint = "Run privileges.status, then configure Shizuku and privileges.configure target=manage_media. The tool will not open the system confirmation dialog by default.",
        deniedCode = "media_mutation_denied",
        deniedMessage = "Android denied MediaStore delete without foreground confirmation.",
        deniedHint = "Verify Shizuku is running and authorized, then run privileges.configure target=manage_media. The tool suppressed the Android confirmation dialog."
    )

    private data class LegacyDeleteOutcome(
        val deletedCount: Int,
        val failures: com.fasterxml.jackson.databind.node.ArrayNode
    )
}
