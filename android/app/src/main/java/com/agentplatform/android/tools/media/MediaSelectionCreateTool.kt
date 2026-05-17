package com.agentplatform.android.tools.media

import android.content.Context
import com.agentplatform.android.core.tool.Tool
import com.agentplatform.android.core.tool.ToolResultEnvelope
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

class MediaSelectionCreateTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {
    override val name: String = "media.selection.create"

    override val description: String = """
        Create a reusable media selection from ids the agent has already
        inspected or chosen. This tool does not search, fetch images, classify
        cleanup candidates, delete, or mutate gallery media. Use it after
        photos.search/list/get_full_batch when you need a stable selection_id
        for follow-up actions such as photos.trash. Include item reasons only
        when they come from the agent's own inspection or user instruction.
    """.trimIndent()

    override val toolClass: String = "selection"
    override val resultType: String = "selection"
    override val defaultDisplayPolicy: String = "debug_only"

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "media_type": {
              "type": "string",
              "enum": ["photo"],
              "default": "photo",
              "description": "Type of media in this selection. Currently only photo is supported."
            },
            "selection_name": {
              "type": "string",
              "description": "Short human-readable label, for example 'screenshots to review'."
            },
            "purpose": {
              "type": "string",
              "description": "Why this selection exists. The tool stores this as audit metadata; it does not judge the media."
            },
            "source": {
              "type": "object",
              "description": "Optional audit metadata about where the ids came from, such as source tool and query.",
              "additionalProperties": true
            },
            "items": {
              "type": "array",
              "minItems": 1,
              "maxItems": 100,
              "items": {
                "type": "object",
                "properties": {
                  "id": {
                    "type": "string",
                    "description": "Media id from a previous tool result."
                  },
                  "display_index": {
                    "type": "integer",
                    "minimum": 1,
                    "description": "Optional user-visible order from the displayed grid."
                  },
                  "reason": {
                    "type": "string",
                    "description": "Optional agent/user reason for selecting this item."
                  },
                  "confidence": {
                    "type": "number",
                    "minimum": 0,
                    "maximum": 1,
                    "description": "Optional confidence in the agent's own selection judgment."
                  },
                  "suggested_action": {
                    "type": "string",
                    "enum": ["trash", "delete", "move", "copy", "favorite", "restore", "keep", "review", "other"],
                    "description": "Optional action the agent is preparing or discussing."
                  },
                  "metadata": {
                    "type": "object",
                    "description": "Optional small audit metadata. Do not put image bytes here.",
                    "additionalProperties": true
                  }
                },
                "required": ["id"]
              }
            }
          },
          "required": ["items"]
        }
        """.trimIndent()
    )

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        val mediaType = args.path("media_type").asText("photo").lowercase(Locale.ROOT).trim()
        require(mediaType == "photo") { "only photo selections are supported" }

        val itemsNode = args.path("items")
        require(itemsNode.isArray && itemsNode.size() > 0) { "items must be a non-empty array" }
        require(itemsNode.size() <= MediaSelectionStore.MAX_ITEMS) {
            "at most ${MediaSelectionStore.MAX_ITEMS} items can be selected"
        }

        val items = mapper.createArrayNode()
        val ids = mapper.createArrayNode()
        val seen = LinkedHashSet<String>()
        itemsNode.forEachIndexed { index, item ->
            val id = item.path("id").asText("").trim()
            require(id.isNotBlank()) { "items[$index].id is required" }
            require(id.toLongOrNull() != null) { "items[$index].id must be a numeric MediaStore id" }
            if (seen.add(id)) {
                items.add(normalizeItem(item, mediaType, id))
                ids.add(id)
            }
        }
        require(items.size() > 0) { "selection must contain at least one unique item" }

        val now = System.currentTimeMillis()
        val selectionId = "sel_${now}_${UUID.randomUUID().toString().take(8)}"
        val selection = mapper.createObjectNode().apply {
            put("selection_id", selectionId)
            put("media_type", mediaType)
            put("selection_name", boundedText(args.path("selection_name").asText(""), 80))
            put("purpose", boundedText(args.path("purpose").asText(""), 500))
            put("created_at_ms", now)
            put("expires_at_ms", now + MediaSelectionStore.TTL_MS)
            put("count", items.size())
            set<ArrayNode>("ids", ids)
            set<ArrayNode>("items", items)
            if (args.has("source") && args.get("source").isObject) {
                set<JsonNode>("source", args.get("source").deepCopy())
            }
        }

        MediaSelectionStore(context, mapper).save(selection)

        selection.set<ObjectNode>("next", mapper.createObjectNode().apply {
            put("recommended_usage", "Use selection_id for a follow-up action after user confirmation, or pass ids directly to older tools.")
            set<ObjectNode>("trash_example", mapper.createObjectNode().apply {
                put("tool", "photos.trash")
                set<ObjectNode>("args", mapper.createObjectNode().apply {
                    put("selection_id", selectionId)
                })
            })
        })

        ToolResultEnvelope.applyStandardFields(
            mapper = mapper,
            tool = this@MediaSelectionCreateTool,
            result = selection,
            ok = true,
            resultType = "selection",
            displayPolicy = "debug_only",
            request = args
        )
    }

    private fun normalizeItem(item: JsonNode, mediaType: String, id: String): ObjectNode =
        mapper.createObjectNode().apply {
            put("media_type", mediaType)
            put("id", id)
            val displayIndex = item.path("display_index").takeIf { it.isIntegralNumber }?.asInt()
            if (displayIndex != null && displayIndex > 0) put("display_index", displayIndex)
            val reason = boundedText(item.path("reason").asText(""), 300)
            if (reason.isNotBlank()) put("reason", reason)
            if (item.path("confidence").isNumber) {
                val normalized = (item.path("confidence").asDouble().coerceIn(0.0, 1.0) * 1000)
                    .roundToInt() / 1000.0
                put("confidence", normalized)
            }
            val suggestedAction = item.path("suggested_action").asText("").lowercase(Locale.ROOT)
            if (suggestedAction in SUGGESTED_ACTIONS) put("suggested_action", suggestedAction)
            if (item.has("metadata") && item.get("metadata").isObject) {
                set<JsonNode>("metadata", item.get("metadata").deepCopy())
            }
        }

    private fun boundedText(value: String, maxLength: Int): String =
        value.trim().replace(Regex("[\\r\\n\\t]+"), " ").take(maxLength)

    companion object {
        private val SUGGESTED_ACTIONS = setOf(
            "trash",
            "delete",
            "move",
            "copy",
            "favorite",
            "restore",
            "keep",
            "review",
            "other"
        )
    }
}
