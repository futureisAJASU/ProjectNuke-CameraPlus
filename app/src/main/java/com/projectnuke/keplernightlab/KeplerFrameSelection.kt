package com.projectnuke.keplernightlab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class KeplerFrameReviewItem(
    val index: Int,
    val file: File,
    val fileName: String,
    val included: Boolean,
    val recommendedInclude: Boolean,
    val userDecision: FrameUserDecision,
    val quality: FrameQualityMetrics?,
    val thumbnailFile: File?,
    val reason: String?
)

enum class FrameUserDecision {
    AUTO,
    INCLUDE,
    EXCLUDE
}

data class FrameQualityMetrics(
    val sharpness: Float,
    val blurRisk: Float,
    val noise: Float,
    val exposure: Float,
    val clippedShadowRatio: Float,
    val clippedHighlightRatio: Float,
    val motion: Float?,
    val alignmentScore: Float?,
    val overallScore: Float,
    val label: String
)

enum class FrameSelectionMode {
    MANUAL,
    AUTO_RULE_BASED,
    AI_RECOMMENDED
}

data class FrameSelectionRecommendation(
    val mode: FrameSelectionMode,
    val includedFrameIndices: Set<Int>,
    val excludedFrameReasons: Map<Int, String>,
    val summary: String,
    val confidence: Float
)

interface FrameSelectionAdvisor {
    suspend fun recommend(
        job: KeplerGalleryJobSummary?,
        frames: List<KeplerFrameReviewItem>
    ): FrameSelectionRecommendation
}

class RuleBasedFrameSelectionAdvisor : FrameSelectionAdvisor {
    override suspend fun recommend(
        job: KeplerGalleryJobSummary?,
        frames: List<KeplerFrameReviewItem>
    ): FrameSelectionRecommendation {
        if (frames.isEmpty()) {
            return FrameSelectionRecommendation(
                mode = FrameSelectionMode.AUTO_RULE_BASED,
                includedFrameIndices = emptySet(),
                excludedFrameReasons = emptyMap(),
                summary = "선택할 원본 프레임이 없습니다.",
                confidence = 0f
            )
        }
        val minimumKeep = minimumFrameCount(job?.jobType, job?.directory)
        val validFrames = frames.filter { it.file.isFile && it.file.length() > 0L }
        val invalidFrames = frames.filterNot { it.file.isFile && it.file.length() > 0L }
        val scored = validFrames.map { frame ->
            val score = frame.quality?.overallScore ?: safePlaceholderScore(frame)
            frame to score
        }
        val sorted = scored.sortedByDescending { it.second }
        val initialIncluded = sorted.filter { shouldKeepByRule(it.first, it.second) }.map { it.first.index }.toMutableSet()
        if (initialIncluded.isEmpty()) {
            initialIncluded += sorted.take(minimumKeep).map { it.first.index }
        }
        if (initialIncluded.size < minimumKeep) {
            initialIncluded += sorted.take(minimumKeep).map { it.first.index }
        }
        val reasons = buildMap {
            invalidFrames.forEach { put(it.index, "파일이 없거나 손상되었습니다.") }
            scored.filter { (frame, _) -> frame.index !in initialIncluded }.forEach { (frame, score) ->
                put(frame.index, frame.reason ?: frame.quality?.label?.let { "$it 프레임" } ?: "보수적 자동 제외 (${formatScore(score)})")
            }
        }
        val kept = initialIncluded.size
        return FrameSelectionRecommendation(
            mode = FrameSelectionMode.AUTO_RULE_BASED,
            includedFrameIndices = initialIncluded,
            excludedFrameReasons = reasons,
            summary = "자동 선택이 ${kept}개 프레임을 유지했습니다. 최소 프레임 수는 보존합니다.",
            confidence = if (validFrames.any { it.quality != null }) 0.72f else 0.35f
        )
    }

    private fun shouldKeepByRule(frame: KeplerFrameReviewItem, score: Float): Boolean {
        if (!frame.file.isFile || frame.file.length() <= 0L) return false
        val quality = frame.quality ?: return true
        if (quality.label in setOf("UNDEREXPOSED", "OVEREXPOSED")) return false
        if (quality.label == "SHAKY" && score < 0.35f) return false
        if (quality.label == "SOFT" && score < 0.30f) return false
        return score >= 0.25f
    }

    private fun safePlaceholderScore(frame: KeplerFrameReviewItem): Float {
        return when {
            !frame.file.isFile -> 0f
            frame.file.length() <= 0L -> 0f
            else -> 0.5f
        }
    }
}

@Deprecated("Current implementation is rule-based, not AI. Use RuleBasedFrameSelectionAdvisor.")
class AiFrameSelectionAdvisor(
    private val fallback: FrameSelectionAdvisor = RuleBasedFrameSelectionAdvisor()
) : FrameSelectionAdvisor {
    override suspend fun recommend(
        job: KeplerGalleryJobSummary?,
        frames: List<KeplerFrameReviewItem>
    ): FrameSelectionRecommendation {
        val base = fallback.recommend(job, frames)
        return base.copy(
            mode = FrameSelectionMode.AUTO_RULE_BASED,
            summary = "AI 추천 자리표시자입니다. 현재는 규칙 기반 선택을 검토용으로만 제안합니다.",
            confidence = (base.confidence + 0.08f).coerceAtMost(0.75f)
        )
    }
}

fun loadFrameReviewItems(
    context: Context,
    jobDir: File
): Result<List<KeplerFrameReviewItem>> = runCatching {
    val target = requireReprocessSafeJobDirectory(context, jobDir)
    val job = loadJobJsonSafe(target)
    val kind = detectJobKind(target, job)
    require(kind != ReprocessJobKind.UNSUPPORTED) { "지원하지 않는 작업 유형입니다." }
    val persistedIncluded = persistedIncludedFrameIndices(job)
    val persistedMode = persistedFrameSelectionMode(job)
    val savedSelectionFrames = persistedSelectionFrameMap(job)
    val fromMetadata = job.optJSONArray("frames")
        ?.let { array -> reviewItemsFromMetadata(target, kind, array, persistedIncluded, savedSelectionFrames, persistedMode) }
        .orEmpty()
    val items = if (fromMetadata.isNotEmpty()) {
        fromMetadata
    } else {
        reviewItemsFromFiles(target, kind, persistedIncluded, savedSelectionFrames, persistedMode)
    }
    items.sortedBy { it.index }
}

fun saveFrameSelection(
    jobDir: File,
    mode: FrameSelectionMode,
    frames: List<KeplerFrameReviewItem>
): Result<Unit> = runCatching {
    val job = loadJobJson(jobDir)
    val included = frames.filter { it.included }.map { it.index }.sorted()
    job.put("frameSelectionMode", mode.name)
        .put("frameSelectionUpdatedAt", isoNow())
        .put("includedFrameIndices", JSONArray(included))
    val frameSelectionFrames = JSONArray()
    val frameMap = frames.associateBy { it.index }
    val sourceFrames = job.optJSONArray("frames")
    repeat(sourceFrames?.length() ?: 0) { position ->
        val frameJson = sourceFrames?.optJSONObject(position) ?: return@repeat
        val index = frameJson.optInt("index", position)
        val review = frameMap[index] ?: return@repeat
        frameJson.put("enabled", review.included)
            .put("excludedByUser", !review.included)
            .put("userDecision", review.userDecision.name)
            .put("recommendedInclude", review.recommendedInclude)
            .put("excludeReason", if (review.included) JSONObject.NULL else (review.reason ?: "USER_EXCLUDED"))
        review.quality?.let { quality ->
            frameJson.put("qualityScore", quality.overallScore.toDouble())
                .put("qualityLabel", quality.label)
                .put("sharpnessScore", quality.sharpness.toDouble())
                .put("motionScore", quality.motion?.toDouble() ?: JSONObject.NULL)
                .put("exposureScore", quality.exposure.toDouble())
                .put("clippedShadowRatio", quality.clippedShadowRatio.toDouble())
                .put("clippedHighlightRatio", quality.clippedHighlightRatio.toDouble())
        }
        frameSelectionFrames.put(
            JSONObject()
                .put("index", review.index)
                .put("fileName", review.fileName)
                .put("included", review.included)
                .put("userDecision", review.userDecision.name)
                .put("recommendedInclude", review.recommendedInclude)
                .put("qualityScore", review.quality?.overallScore?.toDouble() ?: JSONObject.NULL)
                .put("qualityLabel", review.quality?.label ?: JSONObject.NULL)
                .put("qualityReason", review.reason ?: JSONObject.NULL)
        )
    }
    job.put("frameSelectionFrames", frameSelectionFrames)
        .put("updatedAt", System.currentTimeMillis())
    saveJobJson(jobDir, job)
}

internal fun persistedIncludedFrameIndices(job: JSONObject): Set<Int> =
    buildSet {
        val array = job.optJSONArray("includedFrameIndices") ?: return@buildSet
        repeat(array.length()) { index ->
            add(array.optInt(index, Int.MIN_VALUE))
        }
    }.filter { it != Int.MIN_VALUE }.toSet()

internal fun persistedFrameSelectionMode(job: JSONObject): FrameSelectionMode? =
    runCatching { FrameSelectionMode.valueOf(job.optString("frameSelectionMode")) }.getOrNull()

internal fun applyFrameSelectionToItems(
    frames: List<KeplerFrameReviewItem>,
    included: Set<Int>,
    mode: FrameSelectionMode
): List<KeplerFrameReviewItem> {
    return frames.map { frame ->
        val keep = frame.index in included
        frame.copy(
            included = keep,
            userDecision = when {
                mode == FrameSelectionMode.MANUAL && keep -> FrameUserDecision.INCLUDE
                mode == FrameSelectionMode.MANUAL && !keep -> FrameUserDecision.EXCLUDE
                keep -> FrameUserDecision.AUTO
                else -> FrameUserDecision.EXCLUDE
            },
            reason = if (keep) frame.reason else frame.reason ?: "선택에서 제외되었습니다."
        )
    }
}

internal fun minimumFrameCount(jobType: String?, jobDir: File?): Int = when {
    jobType == "RAW_NIGHT_FUSION" || jobDir?.name?.startsWith("KPL_RAW_FUSION_") == true -> MIN_RAW_FUSION_FRAMES
    jobType == "YUV_NIGHT_FUSION" || jobDir?.name?.startsWith("KPL_YUV_FUSION_") == true -> 2
    else -> 1
}

private fun reviewItemsFromMetadata(
    jobDir: File,
    kind: ReprocessJobKind,
    frames: JSONArray,
    persistedIncluded: Set<Int>,
    savedSelectionFrames: Map<Int, JSONObject>,
    persistedMode: FrameSelectionMode?
): List<KeplerFrameReviewItem> = buildList {
    repeat(frames.length()) { position ->
        val frame = frames.optJSONObject(position) ?: return@repeat
        val index = frame.optInt("index", position)
        val fileName = sourceFrameName(frame, kind)
        if (fileName.isBlank()) return@repeat
        val file = File(jobDir, fileName)
        if (!isReprocessSourceFrame(file, kind)) return@repeat
        if (isSelectableFrameBlocked(file.name)) return@repeat
        val selectionJson = savedSelectionFrames[index]
        val included = when {
            persistedIncluded.isNotEmpty() -> index in persistedIncluded
            frame.has("enabled") || frame.has("excludedByUser") ->
                frame.optBoolean("enabled", true) && !frame.optBoolean("excludedByUser", false)
            else -> true
        }
        val quality = frameQualityFromJson(frame)
        add(
            KeplerFrameReviewItem(
                index = index,
                file = file,
                fileName = file.name,
                included = included,
                recommendedInclude = selectionJson?.optBoolean("recommendedInclude")
                    ?: frame.optBoolean("recommendedExclude", false).not(),
                userDecision = parseUserDecision(selectionJson?.optString("userDecision") ?: frame.optString("userDecision"), persistedMode, included),
                quality = quality,
                thumbnailFile = thumbnailCandidate(jobDir, frame, file),
                reason = selectionJson?.optString("qualityReason").takeUnless { it.isNullOrBlank() || it == "null" }
                    ?: frame.optString("qualityReason").takeUnless { it.isNullOrBlank() || it == "null" }
            )
        )
    }
}

private fun reviewItemsFromFiles(
    jobDir: File,
    kind: ReprocessJobKind,
    persistedIncluded: Set<Int>,
    savedSelectionFrames: Map<Int, JSONObject>,
    persistedMode: FrameSelectionMode?
): List<KeplerFrameReviewItem> {
    return jobDir.listFiles()
        ?.filter { it.isFile && isReprocessSourceFrame(it, kind) && !isSelectableFrameBlocked(it.name) }
        ?.sortedBy { sourceFrameSortKey(it.name) }
        ?.mapIndexed { position, file ->
            val index = parseFrameIndex(file.name) ?: position
            val selectionJson = savedSelectionFrames[index]
            val included = if (persistedIncluded.isNotEmpty()) index in persistedIncluded else true
            KeplerFrameReviewItem(
                index = index,
                file = file,
                fileName = file.name,
                included = included,
                recommendedInclude = selectionJson?.optBoolean("recommendedInclude") ?: true,
                userDecision = parseUserDecision(selectionJson?.optString("userDecision"), persistedMode, included),
                quality = null,
                thumbnailFile = thumbnailCandidate(jobDir, null, file),
                reason = selectionJson?.optString("qualityReason")
                    ?.takeUnless { it.isBlank() || it == "null" }
            )
        }
        .orEmpty()
}

private fun frameQualityFromJson(frame: JSONObject): FrameQualityMetrics? {
    val score = frame.optDouble("qualityScore", Double.NaN)
    val sharpness = frame.optDouble("sharpnessScore", Double.NaN)
    val exposure = frame.optDouble("exposureScore", Double.NaN)
    val shadow = frame.optDouble("clippedShadowRatio", Double.NaN)
    val highlight = frame.optDouble("clippedHighlightRatio", Double.NaN)
    if (score.isNaN() && sharpness.isNaN() && exposure.isNaN()) return null
    return FrameQualityMetrics(
        sharpness = sharpness.takeUnless { it.isNaN() }?.toFloat() ?: 0f,
        blurRisk = (1.0 - (sharpness.takeUnless { it.isNaN() } ?: 0.0)).coerceIn(0.0, 1.0).toFloat(),
        noise = ((frame.optDouble("brightnessStdDev", 0.0) / 64.0).coerceIn(0.0, 1.0)).toFloat(),
        exposure = exposure.takeUnless { it.isNaN() }?.toFloat() ?: 0f,
        clippedShadowRatio = shadow.takeUnless { it.isNaN() }?.toFloat() ?: 0f,
        clippedHighlightRatio = highlight.takeUnless { it.isNaN() }?.toFloat() ?: 0f,
        motion = frame.optDouble("motionScore", Double.NaN).takeUnless { it.isNaN() }?.toFloat(),
        alignmentScore = frame.optDouble("alignmentScore", Double.NaN).takeUnless { it.isNaN() }?.toFloat(),
        overallScore = score.takeUnless { it.isNaN() }?.toFloat()
            ?: sharpness.takeUnless { it.isNaN() }?.toFloat()
            ?: 0f,
        label = frame.optString("qualityLabel").ifBlank { "UNKNOWN" }
    )
}

private fun sourceFrameName(frame: JSONObject, kind: ReprocessJobKind): String = when (kind) {
    ReprocessJobKind.RAW_FUSION -> frame.optString("raw16File")
        .ifBlank { frame.optString("dngFile") }
        .ifBlank { frame.optString("file") }
    ReprocessJobKind.YUV_FUSION,
    ReprocessJobKind.COLOR_BURST -> frame.optString("file")
        .ifBlank { frame.optString("yuvFile") }
        .ifBlank { frame.optString("nv21File") }
    ReprocessJobKind.UNSUPPORTED -> ""
}

private fun thumbnailCandidate(jobDir: File, frame: JSONObject?, file: File): File? {
    val explicit = frame?.optString("thumbnailFile").orEmpty()
    File(jobDir, explicit).takeIf { explicit.isNotBlank() && it.isFile }?.let { return it }
    return file.takeIf { it.extension.lowercase(Locale.US) in setOf("png", "jpg", "jpeg", "webp") }
}

private fun parseUserDecision(
    raw: String?,
    mode: FrameSelectionMode?,
    included: Boolean
): FrameUserDecision {
    val parsed = runCatching { FrameUserDecision.valueOf(raw.orEmpty()) }.getOrNull()
    if (parsed != null) return parsed
    return when {
        mode == FrameSelectionMode.MANUAL && included -> FrameUserDecision.INCLUDE
        mode == FrameSelectionMode.MANUAL && !included -> FrameUserDecision.EXCLUDE
        else -> FrameUserDecision.AUTO
    }
}

private fun persistedSelectionFrameMap(job: JSONObject): Map<Int, JSONObject> {
    val array = job.optJSONArray("frameSelectionFrames") ?: return emptyMap()
    return buildMap {
        repeat(array.length()) { index ->
            val item = array.optJSONObject(index) ?: return@repeat
            put(item.optInt("index", index), item)
        }
    }
}

private fun parseFrameIndex(name: String): Int? =
    Regex("""frame_(\d+)""").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()

private fun sourceFrameSortKey(name: String): String =
    parseFrameIndex(name)?.toString()?.padStart(8, '0') ?: name

private fun isSelectableFrameBlocked(name: String): Boolean {
    val lower = name.lowercase(Locale.US)
    return lower.contains("compare") ||
        lower.contains("thumb") ||
        lower.contains("thumbnail") ||
        lower.contains("preview") ||
        lower.contains("merged") ||
        lower.contains("final")
}

private fun isoNow(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())

private fun formatScore(score: Float): String = String.format(Locale.US, "%.2f", score)
