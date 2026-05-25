package com.agentplatform.android.tools.photos

import android.content.Context
import android.content.ContentValues
import android.provider.MediaStore
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.privilege.PrivilegeManager
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotosTrashTool(
    private val context: Context,
    private val mapper: ObjectMapper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : Tool {
    override val name: String = "photos.trash"

    override val description: String = """
        Move existing gallery photos to Android's trash/recycle bin. Pass `id`
        for one photo, `ids` for a batch, or `selection_id` from
        media.selection.create for a previously reviewed set. Use
        photos.restore to restore trashed photos. Requires Android 11+.
    """.trimIndent()

    override val schema: JsonNode = PhotoMutationHelpers.mediaSelectionSchema(
        mapper = mapper,
        idDescription = "Single photo id.",
        idsDescription = "Photo ids. Maximum 100."
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(ioDispatcher) {
        PhotoMutationHelpers.requireAndroidR("trash")
        val ids = PhotoMutationHelpers.parseIds(context, mapper, args)
        val mutation = PhotoMutationHelpers.runPrivilegedImageMutation(
            context = context,
            ids = ids,
            shellMutation = { PrivilegeManager.setImagesTrashed(it, trashed = true) },
            manageMediaMutation = { id ->
                context.contentResolver.update(
                    PhotoMutationHelpers.photoUri(id),
                    ContentValues().apply { put(MediaStore.Images.Media.IS_TRASHED, 1) },
                    null,
                    null
                )
            }
        )
        if (!mutation.succeeded) {
            return@withContext mediaAccessError(args, ids, mutation)
        }
        val result = PhotoMutationHelpers.privilegedMutationSuccess(
            mapper = mapper,
            ids = ids,
            mutation = mutation,
            spec = mutationSpec()
        )
        ToolResultEnvelope.applyStandardFields(mapper, this@PhotosTrashTool, result, ok = true, request = args)
    }

    private fun mediaAccessError(
        args: JsonNode,
        ids: List<Long>,
        mutation: PhotoMutationHelpers.PrivilegedMutationResult
    ) = PhotoMutationHelpers.privilegedMutationError(
        mapper = mapper,
        tool = this@PhotosTrashTool,
        args = args,
        ids = ids,
        mutation = mutation,
        spec = mutationSpec()
    )

    private fun mutationSpec() = PhotoMutationHelpers.PrivilegedMutationSpec(
        countKey = "affected_count",
        unavailableCode = "privileged_media_access_required",
        unavailableMessage = "Moving photos to trash would require Android's foreground MediaStore confirmation on this device. Configure Shizuku/MANAGE_MEDIA first so the tool can run in the background without a system dialog.",
        unavailableHint = "Run privileges.status, then configure Shizuku and privileges.configure target=manage_media. The tool will not open the system confirmation dialog by default.",
        deniedCode = "media_mutation_denied",
        deniedMessage = "Android denied MediaStore trash without foreground confirmation.",
        deniedHint = "Verify Shizuku is running and authorized, then run privileges.configure target=manage_media. The tool suppressed the Android confirmation dialog.",
        successRootFields = listOf("trashed" to true),
        successSummaryFields = listOf("trashed" to true),
        errorSummaryFields = listOf("trashed" to false)
    )
}
