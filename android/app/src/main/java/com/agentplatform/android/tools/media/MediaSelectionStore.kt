package com.agentplatform.android.tools.media

import android.content.Context
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

class MediaSelectionStore(
    context: Context,
    private val mapper: ObjectMapper
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(selection: ObjectNode) {
        pruneExpired()
        val selectionId = selection.path("selection_id").asText("")
        require(selectionId.isNotBlank()) { "selection_id is required" }
        prefs.edit()
            .putString(selectionId, mapper.writeValueAsString(selection))
            .apply()
        pruneOverflow()
    }

    fun get(selectionId: String): ObjectNode {
        val cleanId = selectionId.trim()
        require(cleanId.isNotBlank()) { "selection_id is required" }
        val raw = prefs.getString(cleanId, null)
            ?: throw IllegalArgumentException("selection not found or expired: $cleanId")
        val selection = mapper.readTree(raw) as? ObjectNode
            ?: throw IllegalArgumentException("selection is invalid: $cleanId")
        if (selection.path("expires_at_ms").asLong(0L) in 1 until System.currentTimeMillis()) {
            prefs.edit().remove(cleanId).apply()
            throw IllegalArgumentException("selection expired: $cleanId")
        }
        return selection
    }

    fun resolveIds(selectionId: String, mediaType: String): List<String> {
        val selection = get(selectionId)
        val items = selection.path("items")
        require(items.isArray) { "selection has no items: $selectionId" }
        return items.mapNotNull { item ->
            val type = item.path("media_type").asText(selection.path("media_type").asText(""))
            val id = item.path("id").asText("").trim()
            id.takeIf { type == mediaType && it.isNotBlank() }
        }.distinct()
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        val edit = prefs.edit()
        var changed = false
        prefs.all.forEach { (key, value) ->
            val raw = value as? String ?: return@forEach
            val expiresAt = runCatching { mapper.readTree(raw).path("expires_at_ms").asLong(0L) }
                .getOrDefault(0L)
            if (expiresAt in 1 until now) {
                edit.remove(key)
                changed = true
            }
        }
        if (changed) edit.apply()
    }

    private fun pruneOverflow() {
        val entries = prefs.all.mapNotNull { (key, value) ->
            val raw = value as? String ?: return@mapNotNull null
            val createdAt = runCatching { mapper.readTree(raw).path("created_at_ms").asLong(0L) }
                .getOrDefault(0L)
            key to createdAt
        }
        if (entries.size <= MAX_SELECTIONS) return
        val toRemove = entries
            .sortedBy { it.second }
            .take(entries.size - MAX_SELECTIONS)
            .map { it.first }
        val edit = prefs.edit()
        toRemove.forEach(edit::remove)
        edit.apply()
    }

    companion object {
        const val TTL_MS: Long = 24L * 60L * 60L * 1000L
        const val MAX_ITEMS: Int = 100
        private const val MAX_SELECTIONS: Int = 32
        private const val PREFS_NAME: String = "agent_media_selections"
    }
}
