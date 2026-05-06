package com.agentplatform.android.tools.photos

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.agentplatform.android.core.tool.Tool
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Internal candidate collector for server-side semantic photo search.
 *
 * <p>This tool intentionally returns OCR text + metadata + thumbnails, but does
 * not compute embeddings. agent-service owns embedding provider credentials and
 * ranks candidates with the same OpenAI-compatible embedding stack as memory.
 */
class PhotosSemanticCandidatesTool(
    private val context: Context,
    private val mapper: ObjectMapper
) : Tool {

    override val name: String = "photos.semantic_candidates"

    override val description: String = """
        Internal helper for photos.semantic_search. Scans recent/gallery images,
        extracts on-device OCR text when requested, and returns candidate
        metadata plus thumbnails. Do not call directly unless asked to debug
        semantic photo search internals.
    """.trimIndent()

    override val schema: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "Natural-language search query used only for local hint scoring."
            },
            "limit": {
              "type": "integer",
              "minimum": 1,
              "maximum": 20,
              "default": 8,
              "description": "Max candidates returned after local pre-filtering."
            },
            "scan_limit": {
              "type": "integer",
              "minimum": 1,
              "maximum": 200,
              "default": 60,
              "description": "Max gallery rows to inspect before semantic ranking."
            },
            "bucket_id": {
              "type": "string",
              "description": "Optional MediaStore bucket id to restrict search."
            },
            "name_contains": {
              "type": "string",
              "description": "Optional filename substring filter."
            },
            "date_after": {
              "type": "integer",
              "description": "Only inspect photos taken on/after this UNIX millisecond timestamp (UTC)."
            },
            "date_before": {
              "type": "integer",
              "description": "Only inspect photos taken on/before this UNIX millisecond timestamp (UTC)."
            },
            "ocr": {
              "type": "boolean",
              "default": true,
              "description": "Whether to run on-device OCR over thumbnails."
            }
          }
        }
        """.trimIndent()
    )

    override val confirmRequired: Boolean = false

    override suspend fun execute(args: JsonNode): JsonNode = withContext(Dispatchers.IO) {
        val query = args.path("query").asText("").trim()
        val limit = args.path("limit").asInt(8).coerceIn(1, 20)
        val scanLimit = args.path("scan_limit").asInt(60).coerceIn(limit, 200)
        val bucketId = args.path("bucket_id").asText("").trim().takeIf { it.isNotEmpty() }
        val nameContains = args.path("name_contains").asText("").trim().takeIf { it.isNotEmpty() }
        val dateAfter = args.path("date_after").let { if (it.isNumber) it.asLong() else null }
        val dateBefore = args.path("date_before").let { if (it.isNumber) it.asLong() else null }
        val runOcr = args.path("ocr").let { !it.isBoolean || it.asBoolean(true) }

        val rows = loadRows(scanLimit, bucketId, nameContains, dateAfter, dateBefore)
        val queryTerms = tokenize(query)
        val latinRecognizer = if (runOcr) TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) else null
        val chineseRecognizer = if (runOcr) TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) else null
        val imageLabeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.55f)
                .build()
        )

        val candidates = mutableListOf<Candidate>()
        for (row in rows) {
            var bitmap: Bitmap? = null
            try {
                bitmap = PhotoToolUtils.loadThumbnail(context.contentResolver, row.id, 384)
                val thumbB64 = PhotoToolUtils.jpegBase64(bitmap, 70)
                val ocrText = if (latinRecognizer != null && chineseRecognizer != null) {
                    ocrText(bitmap, row.id, latinRecognizer, chineseRecognizer)
                } else {
                    ""
                }
                val labels = visualLabels(bitmap, row.id, imageLabeler)
                val text = searchableText(row, ocrText, labels)
                val localScore = localScore(text, queryTerms)
                val visualScore = visualScore(labels, queryTerms)
                val recencyScore = recencyScore(row.dateTakenMs)
                val candidateScore = localScore + (visualScore * 3.0) + (recencyScore * 0.5)
                candidates += Candidate(
                    row = row,
                    thumbB64 = thumbB64,
                    ocrText = ocrText,
                    visualLabels = labels,
                    semanticText = text,
                    localScore = localScore,
                    visualScore = visualScore,
                    recencyScore = recencyScore,
                    candidateScore = candidateScore
                )
            } catch (e: Exception) {
                Log.w(TAG, "semantic candidate failed for id=${row.id}: ${e.message}")
            } finally {
                bitmap?.recycle()
            }
        }
        latinRecognizer?.close()
        chineseRecognizer?.close()
        imageLabeler.close()

        val ordered = candidates
            .sortedWith(
                compareByDescending<Candidate> { it.candidateScore }
                    .thenByDescending { it.localScore }
                    .thenByDescending { it.row.dateTakenMs }
            )
            .take(limit)

        val arr: ArrayNode = mapper.createArrayNode()
        for (c in ordered) {
            val obj: ObjectNode = mapper.createObjectNode()
            obj.put("id", c.row.id.toString())
            obj.put("name", c.row.name)
            obj.put("bucket_id", c.row.bucketId)
            obj.put("bucket_name", c.row.bucketName)
            obj.put("date_taken_ms", c.row.dateTakenMs)
            obj.put("date_text", dateText(c.row.dateTakenMs))
            obj.put("size_bytes", c.row.sizeBytes)
            obj.put("width", c.row.width)
            obj.put("height", c.row.height)
            obj.put("mime_type", c.row.mimeType)
            obj.put("ocr_text", c.ocrText)
            obj.set<ArrayNode>("visual_labels", mapper.createArrayNode().apply {
                c.visualLabels.forEach { add(it) }
            })
            obj.put("semantic_text", c.semanticText)
            obj.put("local_score", c.localScore)
            obj.put("visual_score", c.visualScore)
            obj.put("recency_score", c.recencyScore)
            obj.put("candidate_score", c.candidateScore)
            obj.put("thumb_b64", c.thumbB64)
            arr.add(obj)
        }

        val result: ObjectNode = mapper.createObjectNode()
        result.put("query", query)
        result.put("scanned", rows.size)
        result.put("count", arr.size())
        result.set<JsonNode>("photos", arr)
        result
    }

    private fun loadRows(
        scanLimit: Int,
        bucketId: String?,
        nameContains: String?,
        dateAfter: Long?,
        dateBefore: Long?
    ): List<Row> {
        val clauses = mutableListOf<String>()
        val params = mutableListOf<String>()
        if (bucketId != null) {
            clauses += "${MediaStore.Images.Media.BUCKET_ID} = ?"
            params += bucketId
        }
        if (nameContains != null) {
            clauses += "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            params += "%$nameContains%"
        }
        if (dateAfter != null) {
            clauses += "${MediaStore.Images.Media.DATE_TAKEN} >= ?"
            params += dateAfter.toString()
        }
        if (dateBefore != null) {
            clauses += "${MediaStore.Images.Media.DATE_TAKEN} <= ?"
            params += dateBefore.toString()
        }

        val selection = clauses.joinToString(" AND ").ifEmpty { null }
        val selectionArgs = if (params.isEmpty()) null else params.toTypedArray()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE
        )
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val bundle = Bundle().apply {
                putStringArray(
                    android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.Images.Media.DATE_TAKEN)
                )
                putInt(
                    android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                )
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, scanLimit)
                if (selection != null) {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                }
            }
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, bundle, null)
        } else {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            )
        }

        val out = mutableListOf<Row>()
        cursor?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bucketIdIdx = c.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameIdx = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val modifiedIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE)
            val widthIdx = c.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val heightIdx = c.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            val mimeIdx = c.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            while (c.moveToNext() && out.size < scanLimit) {
                val id = c.getLong(idIdx)
                val dateTaken = l(c, dateIdx)
                val modified = l(c, modifiedIdx) * 1000L
                out += Row(
                    id = id,
                    name = c.getString(nameIdx) ?: "image_$id",
                    bucketId = s(c, bucketIdIdx),
                    bucketName = s(c, bucketNameIdx),
                    dateTakenMs = if (dateTaken > 0L) dateTaken else modified,
                    sizeBytes = l(c, sizeIdx),
                    width = i(c, widthIdx),
                    height = i(c, heightIdx),
                    mimeType = s(c, mimeIdx)
                )
            }
        }
        return out
    }

    private fun searchableText(row: Row, ocrText: String, visualLabels: List<String>): String = buildString {
        append("filename: ").append(row.name).append('\n')
        if (row.bucketName.isNotBlank()) append("album: ").append(row.bucketName).append('\n')
        if (row.bucketId.isNotBlank()) append("bucket id: ").append(row.bucketId).append('\n')
        if (row.dateTakenMs > 0) append("date: ").append(dateText(row.dateTakenMs)).append('\n')
        if (row.width > 0 && row.height > 0) append("resolution: ").append(row.width).append('x').append(row.height).append('\n')
        if (row.mimeType.isNotBlank()) append("mime: ").append(row.mimeType).append('\n')
        if (visualLabels.isNotEmpty()) append("visual labels: ").append(visualLabels.joinToString(", ")).append('\n')
        if (ocrText.isNotBlank()) append("ocr text: ").append(ocrText)
    }.trim()

    private fun visualLabels(
        bitmap: Bitmap,
        id: Long,
        labeler: com.google.mlkit.vision.label.ImageLabeler
    ): List<String> {
        val input = InputImage.fromBitmap(bitmap, 0)
        return try {
            Tasks.await(labeler.process(input))
                .flatMap { label ->
                    val text = label.text.trim()
                    if (text.isEmpty()) emptyList() else listOf(text) + VISUAL_LABEL_ALIASES[text.lowercase()].orEmpty()
                }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(24)
        } catch (e: Exception) {
            Log.w(TAG, "Image labeling failed for id=$id: ${e.message}")
            emptyList()
        }
    }

    private fun ocrText(
        bitmap: Bitmap,
        id: Long,
        latinRecognizer: com.google.mlkit.vision.text.TextRecognizer,
        chineseRecognizer: com.google.mlkit.vision.text.TextRecognizer
    ): String {
        val parts = linkedSetOf<String>()
        val input = InputImage.fromBitmap(bitmap, 0)
        try {
            Tasks.await(latinRecognizer.process(input)).text.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
        } catch (e: Exception) {
            Log.w(TAG, "Latin OCR failed for id=$id: ${e.message}")
        }
        try {
            Tasks.await(chineseRecognizer.process(input)).text.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
        } catch (e: Exception) {
            Log.w(TAG, "Chinese OCR failed for id=$id: ${e.message}")
        }
        return parts.joinToString("\n")
    }

    private fun localScore(text: String, terms: Set<String>): Int {
        if (terms.isEmpty()) return 0
        val lower = text.lowercase()
        var score = 0
        for (term in terms) {
            if (lower.contains(term)) {
                score += termWeight(term)
            }
        }
        return score
    }

    private fun visualScore(labels: List<String>, queryTerms: Set<String>): Double {
        if (labels.isEmpty() || queryTerms.isEmpty()) return 0.0
        val labelTerms = linkedSetOf<String>()
        labels.forEach { labelTerms += expandVisualLabelTerms(it) }
        var score = 0.0
        for (term in queryTerms) {
            if (labelTerms.any { labelTerm -> tokenMatches(term, labelTerm) }) {
                score += termWeight(term).toDouble()
            }
        }
        return score.coerceAtMost(12.0)
    }

    private fun recencyScore(dateMs: Long): Double {
        if (dateMs <= 0L) return 0.0
        val ageMs = (System.currentTimeMillis() - dateMs).coerceAtLeast(0L)
        val ageDays = ageMs / 86_400_000.0
        return 1.0 / (1.0 + ageDays.coerceAtMost(365.0))
    }

    private fun expandVisualLabelTerms(text: String): Set<String> {
        val normalizedLabel = text.trim().lowercase()
        val terms = linkedSetOf<String>()
        terms += tokenize(normalizedLabel)
        VISUAL_LABEL_ALIASES[normalizedLabel].orEmpty().forEach { terms += tokenize(it) }
        return terms
    }

    private fun tokenize(query: String): Set<String> {
        val normalized = query.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\u4e00-\\u9fff]+"), " ")
        val parts = normalized.split(Regex("\\s+")).filter { it.length >= 2 }
        val cjk = normalized.filter { it.code in 0x4e00..0x9fff }
        val cjkSingles = cjk.map { it.toString() }
        val cjkBigrams = cjk.windowed(2, 1, partialWindows = false)
        return (parts + cjkSingles + cjkBigrams)
            .filter { it.isNotBlank() && it !in STOP_TERMS }
            .toSet()
    }

    private fun tokenMatches(queryTerm: String, labelTerm: String): Boolean {
        if (queryTerm == labelTerm) return true
        if (queryTerm.length < 2 || labelTerm.length < 2) return false
        return queryTerm.contains(labelTerm) || labelTerm.contains(queryTerm)
    }

    private fun termWeight(term: String): Int {
        if (term.length >= 4) return 3
        return 2
    }

    private fun dateText(ms: Long): String {
        if (ms <= 0L) return ""
        val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        return DATE_FORMAT.format(date)
    }

    private fun s(c: android.database.Cursor, idx: Int): String =
        if (idx >= 0 && !c.isNull(idx)) c.getString(idx) ?: "" else ""

    private fun l(c: android.database.Cursor, idx: Int): Long =
        if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else 0L

    private fun i(c: android.database.Cursor, idx: Int): Int =
        if (idx >= 0 && !c.isNull(idx)) c.getInt(idx) else 0

    private data class Row(
        val id: Long,
        val name: String,
        val bucketId: String,
        val bucketName: String,
        val dateTakenMs: Long,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
        val mimeType: String,
    )

    private data class Candidate(
        val row: Row,
        val thumbB64: String,
        val ocrText: String,
        val visualLabels: List<String>,
        val semanticText: String,
        val localScore: Int,
        val visualScore: Double,
        val recencyScore: Double,
        val candidateScore: Double,
    )

    companion object {
        private const val TAG = "PhotosSemanticCandidates"
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        private val VISUAL_LABEL_ALIASES = mapOf(
            "cat" to listOf("猫", "猫咪", "小猫", "kitten", "kitty", "宠物", "动物"),
            "dog" to listOf("狗", "狗狗", "小狗", "puppy", "宠物", "动物"),
            "pet" to listOf("宠物", "动物"),
            "animal" to listOf("动物", "宠物"),
            "bird" to listOf("鸟", "动物"),
            "flower" to listOf("花", "植物"),
            "plant" to listOf("植物", "花草"),
            "food" to listOf("食物", "饭菜", "餐饮"),
            "meal" to listOf("饭菜", "食物"),
            "person" to listOf("人", "人物"),
            "people" to listOf("人", "人物"),
            "selfie" to listOf("自拍", "人像"),
            "car" to listOf("车", "汽车"),
            "vehicle" to listOf("车辆", "车"),
            "screenshot" to listOf("截图", "屏幕截图"),
            "text" to listOf("文字", "文本"),
            "document" to listOf("文档", "文件"),
            "receipt" to listOf("收据", "小票", "票据"),
            "menu" to listOf("菜单", "菜品"),
            "qr code" to listOf("二维码", "码"),
            "computer" to listOf("电脑", "计算机"),
            "laptop" to listOf("笔记本", "电脑"),
            "mobile phone" to listOf("手机", "电话"),
            "screen" to listOf("屏幕", "显示器"),
            "keyboard" to listOf("键盘"),
            "book" to listOf("书", "书本"),
            "building" to listOf("建筑", "楼", "房子"),
            "house" to listOf("房子", "住宅"),
            "city" to listOf("城市", "街景"),
            "toy" to listOf("玩具"),
            "clothing" to listOf("衣服", "服装"),
            "fashion accessory" to listOf("配饰", "饰品"),
            "tableware" to listOf("餐具"),
            "drink" to listOf("饮料", "喝的"),
            "sky" to listOf("天空"),
            "water" to listOf("水", "海", "湖"),
            "beach" to listOf("海边", "沙滩"),
            "mountain" to listOf("山", "山景")
        )
        private val STOP_TERMS = setOf(
            "的", "了", "在", "是", "和", "或", "找", "看", "这", "那", "张", "个", "一",
            "图", "片", "照", "图片", "照片", "相片"
        )
    }
}
