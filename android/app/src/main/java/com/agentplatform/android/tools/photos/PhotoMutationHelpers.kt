package com.agentplatform.android.tools.photos

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.agentplatform.android.media.MediaStoreRequestBridge
import com.agentplatform.android.privilege.PrivilegeManager
import com.agentplatform.android.tools.media.MediaSelectionStore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.IOException
import java.util.Locale

internal object PhotoMutationHelpers {
    private const val MAX_BATCH = 100

    data class PhotoRecord(
        val id: Long,
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val dateTakenMs: Long,
        val sizeBytes: Long
    )

    data class PrivilegedMutationResult(
        val usedPrivilegedShell: Boolean,
        val usedManageMedia: Boolean,
        val commandResults: List<PrivilegeManager.MediaCommandResult>,
        val denied: SecurityException? = null
    ) {
        val succeeded: Boolean
            get() = denied == null && (usedPrivilegedShell || usedManageMedia)
    }

    data class PrivilegedMutationSpec(
        val countKey: String,
        val unavailableCode: String,
        val unavailableMessage: String,
        val unavailableHint: String,
        val deniedCode: String,
        val deniedMessage: String,
        val deniedHint: String,
        val successRootFields: List<Pair<String, Any?>> = emptyList(),
        val successSummaryFields: List<Pair<String, Any?>> = emptyList(),
        val errorSummaryFields: List<Pair<String, Any?>> = emptyList()
    )

    fun parseIds(args: JsonNode): List<Long> {
        return parseIds(args, emptyList())
    }

    fun parseIds(context: Context, mapper: ObjectMapper, args: JsonNode): List<Long> {
        val selectionId = args.path("selection_id").asText("").trim()
        val selectedIds = if (selectionId.isBlank()) {
            emptyList()
        } else {
            MediaSelectionStore(context, mapper).resolveIds(selectionId, "photo")
        }
        return parseIds(args, selectedIds)
    }

    private fun parseIds(args: JsonNode, extraIds: List<String>): List<Long> {
        val raw = mutableListOf<String>()
        val idsNode = args.get("ids")
        if (idsNode != null && idsNode.isArray) {
            idsNode.forEach { raw += it.asText("").trim() }
        }
        args.get("id")?.asText("")?.trim()?.takeIf { it.isNotBlank() }?.let { raw += it }
        raw += extraIds
        val ids = raw.mapNotNull { it.toLongOrNull() }.distinct()
        require(ids.isNotEmpty()) { "id, ids, or selection_id is required" }
        require(ids.size <= MAX_BATCH) { "at most $MAX_BATCH photos can be changed at once" }
        return ids
    }

    fun photoUri(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    fun queryPhoto(context: Context, id: Long): PhotoRecord {
        val uri = photoUri(id)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.SIZE
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val dateIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE)
                return PhotoRecord(
                    id = id,
                    uri = uri,
                    name = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else "image_$id",
                    mimeType = if (mimeIdx >= 0 && !c.isNull(mimeIdx)) c.getString(mimeIdx) else "image/jpeg",
                    dateTakenMs = if (dateIdx >= 0 && !c.isNull(dateIdx)) c.getLong(dateIdx) else 0L,
                    sizeBytes = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                )
            }
        }
        throw IllegalArgumentException("photo not found: $id")
    }

    suspend fun ensureWriteAccess(context: Context, ids: List<Long>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val uris = ids.map { photoUri(it) }
        val approved = MediaStoreRequestBridge.request(
            context,
            MediaStore.createWriteRequest(context.contentResolver, uris)
        )
        if (!approved) {
            throw SecurityException("Android media write confirmation rejected")
        }
    }

    suspend fun updateWithRecovery(context: Context, uri: Uri, values: ContentValues): Int {
        return try {
            context.contentResolver.update(uri, values, null, null)
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                val approved = MediaStoreRequestBridge.request(
                    context,
                    e.userAction.actionIntent,
                    autoApprove = true
                )
                if (!approved) throw SecurityException("Android media write confirmation rejected")
                context.contentResolver.update(uri, values, null, null)
            } else {
                throw e
            }
        }
    }

    suspend fun deleteWithRecovery(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.delete(uri, null, null)
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                val approved = MediaStoreRequestBridge.request(
                    context,
                    e.userAction.actionIntent,
                    autoApprove = true
                )
                if (!approved) throw SecurityException("Android media delete confirmation rejected")
                context.contentResolver.delete(uri, null, null)
            } else {
                throw e
            }
        }
    }

    fun sanitizeAlbum(value: String?): String {
        val clean = value
            ?.replace(Regex("[\\\\/:\\r\\n\\t]"), "_")
            ?.trim(' ', '.', '_')
            ?.take(64)
            ?.takeIf { it.isNotBlank() }
        return clean ?: "Agent Platform"
    }

    fun relativePicturesPath(album: String): String = "Pictures/${sanitizeAlbum(album)}"

    fun sanitizeFilename(value: String?, fallback: String, mimeType: String): String {
        val extension = extensionForMime(mimeType) ?: fallback.substringAfterLast('.', missingDelimiterValue = "")
        val clean = value
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringBefore('?')
            ?.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            ?.trim(' ', '.', '_')
            ?.take(120)
            ?.takeIf { it.isNotBlank() }
            ?: fallback.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").take(120)
        val lower = clean.lowercase(Locale.ROOT)
        return if (extension.isBlank() || lower.endsWith(".${extension.lowercase(Locale.ROOT)}")) {
            clean
        } else {
            "$clean.$extension"
        }
    }

    fun copyIntoAlbum(
        context: Context,
        source: PhotoRecord,
        album: String,
        filename: String?
    ): Uri {
        val resolver = context.contentResolver
        val safeName = sanitizeFilename(filename, source.name, source.mimeType)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, safeName)
            put(MediaStore.Images.Media.MIME_TYPE, source.mimeType)
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (source.dateTakenMs > 0) put(MediaStore.Images.Media.DATE_TAKEN, source.dateTakenMs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePicturesPath(album))
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("failed to create gallery image")
        try {
            resolver.openInputStream(source.uri)?.use { input ->
                resolver.openOutputStream(target)?.use { output ->
                    input.copyTo(output)
                } ?: throw IOException("failed to open gallery output stream")
            } ?: throw IOException("failed to open source photo")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(target, values, null, null)
            }
            return target
        } catch (e: Exception) {
            runCatching { resolver.delete(target, null, null) }
            throw e
        }
    }

    fun idsArray(mapper: ObjectMapper, ids: List<Long>): ArrayNode {
        val array = mapper.createArrayNode()
        ids.forEach { array.add(it.toString()) }
        return array
    }

    fun failuresArray(mapper: ObjectMapper): ArrayNode = mapper.createArrayNode()

    fun addFailure(failures: ArrayNode, id: Long, error: Throwable) {
        failures.addObject()
            .put("id", id.toString())
            .put("error", error.message ?: error.javaClass.simpleName)
    }

    fun mediaCommandResultsArray(
        mapper: ObjectMapper,
        commands: List<PrivilegeManager.MediaCommandResult>
    ): ArrayNode {
        val array = mapper.createArrayNode()
        commands.forEach { command ->
            array.addObject().apply {
                put("id", command.id.toString())
                put("ok", command.result.ok)
                put("exit_code", command.result.exitCode)
                put("timed_out", command.result.timedOut)
                if (command.result.stdout.isNotBlank()) put("stdout", command.result.stdout.take(1000))
                if (command.result.stderr.isNotBlank()) put("stderr", command.result.stderr.take(1000))
            }
        }
        return array
    }

    fun runPrivilegedImageMutation(
        context: Context,
        ids: List<Long>,
        shellMutation: (List<Long>) -> List<PrivilegeManager.MediaCommandResult>,
        manageMediaMutation: (Long) -> Unit
    ): PrivilegedMutationResult {
        var commandResults: List<PrivilegeManager.MediaCommandResult> = emptyList()
        if (PrivilegeManager.status(context).shellAvailable) {
            commandResults = shellMutation(ids)
            if (commandResults.all { it.result.ok }) {
                return PrivilegedMutationResult(
                    usedPrivilegedShell = true,
                    usedManageMedia = false,
                    commandResults = commandResults
                )
            }
        }
        if (!PrivilegeManager.canManageMedia(context)) {
            return PrivilegedMutationResult(
                usedPrivilegedShell = false,
                usedManageMedia = false,
                commandResults = commandResults
            )
        }
        return try {
            ids.forEach(manageMediaMutation)
            PrivilegedMutationResult(
                usedPrivilegedShell = false,
                usedManageMedia = true,
                commandResults = commandResults
            )
        } catch (e: SecurityException) {
            PrivilegedMutationResult(
                usedPrivilegedShell = false,
                usedManageMedia = true,
                commandResults = commandResults,
                denied = e
            )
        }
    }

    fun privilegedMutationSuccess(
        mapper: ObjectMapper,
        ids: List<Long>,
        mutation: PrivilegedMutationResult,
        spec: PrivilegedMutationSpec
    ): ObjectNode {
        return mapper.createObjectNode().apply {
            set<JsonNode>("ids", idsArray(mapper, ids))
            putFields(this, spec.successRootFields)
            put(spec.countKey, ids.size)
            put("used_privileged_shell", mutation.usedPrivilegedShell)
            put("used_manage_media", mutation.usedManageMedia)
            put("system_confirmation_required", !mutation.usedPrivilegedShell && !mutation.usedManageMedia)
            put("system_confirmation_suppressed", false)
            if (mutation.commandResults.isNotEmpty()) {
                set<JsonNode>("privileged_shell_results", mediaCommandResultsArray(mapper, mutation.commandResults))
            }
            set<JsonNode>("summary", mapper.createObjectNode().apply {
                put(spec.countKey, ids.size)
                putFields(this, spec.successSummaryFields)
                put("used_privileged_shell", mutation.usedPrivilegedShell)
                put("used_manage_media", mutation.usedManageMedia)
            })
        }
    }

    fun privilegedMutationError(
        mapper: ObjectMapper,
        tool: Tool,
        args: JsonNode,
        ids: List<Long>,
        mutation: PrivilegedMutationResult,
        spec: PrivilegedMutationSpec
    ): ObjectNode {
        val denied = mutation.denied != null
        val result = ToolResultEnvelope.error(
            mapper = mapper,
            tool = tool,
            code = if (denied) spec.deniedCode else spec.unavailableCode,
            message = mutation.denied?.message ?: if (denied) spec.deniedMessage else spec.unavailableMessage,
            retryable = true,
            hint = if (denied) spec.deniedHint else spec.unavailableHint,
            request = args
        )
        result.set<JsonNode>("ids", idsArray(mapper, ids))
        result.put(spec.countKey, 0)
        result.put("used_privileged_shell", false)
        result.put("used_manage_media", mutation.usedManageMedia)
        result.put("system_confirmation_required", true)
        result.put("system_confirmation_suppressed", true)
        if (mutation.commandResults.isNotEmpty()) {
            result.set<JsonNode>("privileged_shell_results", mediaCommandResultsArray(mapper, mutation.commandResults))
        }
        result.set<JsonNode>("summary", mapper.createObjectNode().apply {
            put(spec.countKey, 0)
            putFields(this, spec.errorSummaryFields)
            put("requires_privileged_media_access", true)
            put("system_confirmation_suppressed", true)
        })
        return result
    }

    private fun extensionForMime(mimeType: String): String? {
        return when (mimeType.lowercase(Locale.ROOT)) {
            "image/jpeg", "image/jpg", "image/pjpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            else -> null
        }
    }

    private fun putFields(node: ObjectNode, fields: List<Pair<String, Any?>>) {
        fields.forEach { (name, value) ->
            when (value) {
                null -> Unit
                is Boolean -> node.put(name, value)
                is Int -> node.put(name, value)
                is Long -> node.put(name, value)
                is String -> node.put(name, value)
                is JsonNode -> node.set<JsonNode>(name, value)
                else -> node.put(name, value.toString())
            }
        }
    }
}
