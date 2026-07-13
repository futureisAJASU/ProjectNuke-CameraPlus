package com.projectnuke.keplernightlab

import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max

internal const val YUV_FUSION_V2_SKELETON_VERSION = "YUV_FUSION_V2_SKELETON"
internal const val YUV_FUSION_V2_DRY_RUN_VERSION = "YUV_FUSION_V2_DRY_RUN"
internal const val YUV_FUSION_V2_QUALITY_WEIGHTED_VERSION = "YUV_FUSION_V2_QUALITY_WEIGHTED"

private const val V2_QUALITY_MAX_SAMPLE_DIMENSION = 320
private const val V2_NEUTRAL_SCORE = 0.5
private const val V2_NEUTRAL_WEIGHT = 1.0

internal class YuvFusionV2DryRunClassicFusionFailedException(
    message: String,
    cause: Throwable
) : RuntimeException(message, cause)

private val V2_OWNED_METADATA_KEYS = listOf(
    "experimentalFusionVersion",
    "v2SkeletonUsed",
    "yuvFusionV2DryRun",
    "yuvFusionV2ScoringRan",
    "yuvFusionV2FrameQualityScores",
    "yuvFusionV2SkippedFrames",
    "yuvFusionV2ScoringFailed",
    "yuvFusionV2MergePlanPreview",
    "yuvFusionV2MergePlanEmpty",
    "yuvFusionV2MergePlanReason",
    "yuvFusionV2WeightedMergeAttempted",
    "yuvFusionV2WeightedMergeUsed",
    "yuvFusionV2FallbackToV1",
    "yuvFusionV2FallbackReason",
    "yuvFusionV2DryRunCalledClassicFusion",
    "yuvFusionV2ClassicFusionFailed",
    "yuvFusionV2ClassicFusionFailureType",
    "yuvFusionV2ClassicFusionFailureMessage",
    "yuvFusionV2ClassicFusionFailureStackTrace"
)

internal data class YuvFusionV2FrameQuality(
    val jsonIndex: Int,
    val displayFrameIndex: Int,
    val fileName: String,
    val sharpnessScore: Double?,
    val exposureScore: Double?,
    val metadataScore: Double?,
    val finalWeight: Double
)

private data class YuvFusionV2FrameInput(
    val jsonIndex: Int,
    val fileName: String
)

private data class YuvFusionV2MergeWeight(
    val jsonIndex: Int,
    val displayFrameIndex: Int,
    val fileName: String,
    val sourceFinalWeight: Double,
    val normalizedWeight: Double
)

private data class YuvFusionV2MergePlan(
    val weights: List<YuvFusionV2MergeWeight>,
    val empty: Boolean,
    val reason: String
)

private data class YuvFusionV2SkippedFrame(
    val jsonIndex: Int,
    val displayFrameIndex: Int,
    val fileName: String,
    val reason: String
)

private data class YuvFusionV2ScoringResult(
    val scores: List<YuvFusionV2FrameQuality>,
    val skipped: List<YuvFusionV2SkippedFrame>,
    val scoringFailed: Boolean
)

private data class YuvFusionV2FrameQualityInput(
    val jsonIndex: Int,
    val displayFrameIndex: Int,
    val fileName: String,
    val file: File?,
    val frameJson: JSONObject
)

private data class YuvFusionV2LumaSample(
    val width: Int,
    val height: Int,
    val values: FloatArray
)

private data class YuvFusionV2MetadataWrite(
    val dryRun: Boolean,
    val scoringResult: YuvFusionV2ScoringResult,
    val mergePlan: YuvFusionV2MergePlan,
    val weightedMergeAttempted: Boolean,
    val weightedMergeUsed: Boolean,
    val fallbackToV1: Boolean,
    val fallbackReason: String?,
    val scoringRan: Boolean = true,
    val dryRunCalledClassicFusion: Boolean = false,
    val classicFusionFailed: Boolean = false,
    val classicFusionFailureType: String? = null,
    val classicFusionFailureMessage: String? = null,
    val classicFusionFailureStackTrace: String? = null
)

internal fun processYuvFusionJobV2(
    jobDir: File,
    onStatus: (String) -> Unit,
    requestedParams: ClassicYuvFusionParams? = null,
    dryRun: Boolean = false,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
): File {
    cancellation.throwIfCancelled()
    val scoringResult = safeScoreYuvFusionV2Frames(jobDir)
    cancellation.throwIfCancelled()
    val mergePlan = runCatching { buildYuvFusionV2MergePlan(scoringResult) }
        .getOrElse {
            YuvFusionV2MergePlan(
                weights = emptyList(),
                empty = true,
                reason = "MERGE_PLAN_FAILED"
            )
        }
    cancellation.throwIfCancelled()

    if (dryRun) {
        val metadataBase = YuvFusionV2MetadataWrite(
            dryRun = true,
            scoringResult = scoringResult,
            mergePlan = mergePlan,
            weightedMergeAttempted = false,
            weightedMergeUsed = false,
            fallbackToV1 = false,
            fallbackReason = null,
            scoringRan = true,
            dryRunCalledClassicFusion = true
        )
        return try {
            cancellation.throwIfCancelled()
            val finalFile = processClassicYuvFusionJob(
                jobDir = jobDir,
                onStatus = onStatus,
                requestedParams = requestedParams,
                cancellation = cancellation
            )
            cancellation.throwIfCancelled()
            runCatching {
                writeYuvFusionV2Metadata(
                    jobDir = jobDir,
                    metadata = metadataBase
                )
            }
            finalFile
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Exception) {
            val failureMessage = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            runCatching {
                writeYuvFusionV2Metadata(
                    jobDir = jobDir,
                    metadata = metadataBase.copy(
                        classicFusionFailed = true,
                        classicFusionFailureType = t.javaClass.name,
                        classicFusionFailureMessage = failureMessage,
                        classicFusionFailureStackTrace = t.stackTraceToString()
                    )
                )
            }
            throw YuvFusionV2DryRunClassicFusionFailedException(
                "YUV Fusion V2 dry-run classic fusion failed: ${t.javaClass.simpleName}: $failureMessage",
                t
            )
        }
    }

    val externalWeights = mergePlanToExternalFrameWeights(mergePlan)
    if (externalWeights == null) {
        cancellation.throwIfCancelled()
        val finalFile = processClassicYuvFusionJob(
            jobDir = jobDir,
            onStatus = onStatus,
            requestedParams = requestedParams,
            cancellation = cancellation
        )
        cancellation.throwIfCancelled()
        runCatching {
            writeYuvFusionV2Metadata(
                jobDir = jobDir,
                metadata = YuvFusionV2MetadataWrite(
                    dryRun = false,
                    scoringResult = scoringResult,
                    mergePlan = mergePlan,
                    weightedMergeAttempted = true,
                    weightedMergeUsed = false,
                    fallbackToV1 = true,
                    fallbackReason = mergePlan.reason
                )
            )
        }
        return finalFile
    }

    return try {
        cancellation.throwIfCancelled()
        val finalFile = processClassicYuvFusionJob(
            jobDir = jobDir,
            onStatus = onStatus,
            requestedParams = requestedParams,
            externalFrameWeights = externalWeights,
            cancellation = cancellation
        )
        cancellation.throwIfCancelled()
        runCatching {
            writeYuvFusionV2Metadata(
                jobDir = jobDir,
                metadata = YuvFusionV2MetadataWrite(
                    dryRun = false,
                    scoringResult = scoringResult,
                    mergePlan = mergePlan,
                    weightedMergeAttempted = true,
                    weightedMergeUsed = true,
                    fallbackToV1 = false,
                    fallbackReason = null
                )
            )
        }
        finalFile
    } catch (oom: OutOfMemoryError) {
        cancellation.throwIfCancelled()
        val finalFile = processClassicYuvFusionJob(
            jobDir = jobDir,
            onStatus = onStatus,
            requestedParams = requestedParams,
            cancellation = cancellation
        )
        cancellation.throwIfCancelled()
        runCatching {
            writeYuvFusionV2Metadata(
                jobDir = jobDir,
                metadata = YuvFusionV2MetadataWrite(
                    dryRun = false,
                    scoringResult = scoringResult,
                    mergePlan = mergePlan,
                    weightedMergeAttempted = true,
                    weightedMergeUsed = false,
                    fallbackToV1 = true,
                    fallbackReason = "OutOfMemoryError"
                )
            )
        }
        finalFile
    } catch (ce: CancellationException) {
        throw ce
        } catch (t: Exception) {
        cancellation.throwIfCancelled()
        val finalFile = processClassicYuvFusionJob(
            jobDir = jobDir,
            onStatus = onStatus,
            requestedParams = requestedParams,
            cancellation = cancellation
        )
        cancellation.throwIfCancelled()
        runCatching {
            writeYuvFusionV2Metadata(
                jobDir = jobDir,
                metadata = YuvFusionV2MetadataWrite(
                    dryRun = false,
                    scoringResult = scoringResult,
                    mergePlan = mergePlan,
                    weightedMergeAttempted = true,
                    weightedMergeUsed = false,
                    fallbackToV1 = true,
                    fallbackReason = "${t.javaClass.simpleName}: ${t.message}".take(120)
                )
            )
        }
        finalFile
    }
}

private fun safeScoreYuvFusionV2Frames(jobDir: File): YuvFusionV2ScoringResult =
    try {
        scoreYuvFusionV2Frames(jobDir)
    } catch (_: OutOfMemoryError) {
        YuvFusionV2ScoringResult(
            scores = emptyList(),
            skipped = emptyList(),
            scoringFailed = true
        )
    } catch (_: Exception) {
        YuvFusionV2ScoringResult(
            scores = emptyList(),
            skipped = emptyList(),
            scoringFailed = true
        )
    }

private fun scoreYuvFusionV2Frames(jobDir: File): YuvFusionV2ScoringResult {
    val scores = mutableListOf<YuvFusionV2FrameQuality>()
    val skipped = mutableListOf<YuvFusionV2SkippedFrame>()
    loadFrameQualityInputs(jobDir).forEach { input ->
        val file = input.file
        if (file == null) {
            skipped += YuvFusionV2SkippedFrame(
                jsonIndex = input.jsonIndex,
                displayFrameIndex = input.displayFrameIndex,
                fileName = input.fileName,
                reason = "FILE_MISSING"
            )
            return@forEach
        }
        val sample = try {
            decodeV2LumaSample(file)
        } catch (oom: OutOfMemoryError) {
            throw oom
        } catch (_: Exception) {
            null
        }
        if (sample == null && !hasUsableMetadataScores(input.frameJson)) {
            skipped += YuvFusionV2SkippedFrame(
                jsonIndex = input.jsonIndex,
                displayFrameIndex = input.displayFrameIndex,
                fileName = input.fileName,
                reason = "DECODE_FAILED"
            )
            return@forEach
        }
        val sharpness = estimateSharpnessScore(input.frameJson, sample)
        val exposure = estimateExposureScore(input.frameJson, sample)
        val metadata = estimateMetadataScore(input.frameJson)
        if (sharpness == null && exposure == null && metadata == null) {
            skipped += YuvFusionV2SkippedFrame(
                jsonIndex = input.jsonIndex,
                displayFrameIndex = input.displayFrameIndex,
                fileName = input.fileName,
                reason = "INSUFFICIENT_SIGNAL"
            )
            return@forEach
        }
        scores += combineFrameQualityScores(
            jsonIndex = input.jsonIndex,
            displayFrameIndex = input.displayFrameIndex,
            fileName = input.fileName,
            sharpnessScore = sharpness,
            exposureScore = exposure,
            metadataScore = metadata
        )
    }
    return YuvFusionV2ScoringResult(
        scores = scores,
        skipped = skipped,
        scoringFailed = false
    )
}

private fun buildYuvFusionV2MergePlan(scoringResult: YuvFusionV2ScoringResult): YuvFusionV2MergePlan {
    if (scoringResult.scoringFailed) {
        return YuvFusionV2MergePlan(
            weights = emptyList(),
            empty = true,
            reason = "SCORING_FAILED"
        )
    }
    if (scoringResult.scores.isEmpty()) {
        return YuvFusionV2MergePlan(
            weights = emptyList(),
            empty = true,
            reason = "NO_SCORED_FRAMES"
        )
    }
    val inputs = scoringResult.scores.map { score ->
        YuvFusionV2FrameInput(
            jsonIndex = score.jsonIndex,
            fileName = score.fileName
        )
    }
    val rawWeights = scoringResult.scores.map { score ->
        YuvFusionV2MergeWeight(
            jsonIndex = score.jsonIndex,
            displayFrameIndex = score.displayFrameIndex,
            fileName = score.fileName,
            sourceFinalWeight = score.finalWeight,
            normalizedWeight = 0.0
        )
    }
    return normalizeYuvFusionV2Weights(inputs, rawWeights)
}

private fun normalizeYuvFusionV2Weights(
    inputs: List<YuvFusionV2FrameInput>,
    rawWeights: List<YuvFusionV2MergeWeight>
): YuvFusionV2MergePlan {
    if (inputs.isEmpty() || rawWeights.isEmpty()) {
        return YuvFusionV2MergePlan(
            weights = emptyList(),
            empty = true,
            reason = "NO_VALID_WEIGHTS"
        )
    }
    val validWeights = rawWeights.mapNotNull { weight ->
        val source = weight.sourceFinalWeight
        if (!source.isFinite() || source <= 0.0) null else weight
    }
    if (validWeights.isEmpty()) {
        return YuvFusionV2MergePlan(
            weights = emptyList(),
            empty = true,
            reason = "ALL_WEIGHTS_INVALID"
        )
    }
    val total = validWeights.sumOf { it.sourceFinalWeight }
    if (!total.isFinite() || total <= 0.0) {
        return YuvFusionV2MergePlan(
            weights = emptyList(),
            empty = true,
            reason = "ZERO_TOTAL_WEIGHT"
        )
    }
    val normalized = validWeights.map { weight ->
        weight.copy(normalizedWeight = (weight.sourceFinalWeight / total).coerceIn(0.0, 1.0))
    }
    return YuvFusionV2MergePlan(
        weights = normalized,
        empty = normalized.isEmpty(),
        reason = if (normalized.isEmpty()) "NO_VALID_WEIGHTS" else "OK"
    )
}

private fun mergePlanToExternalFrameWeights(mergePlan: YuvFusionV2MergePlan): Map<Int, Float>? {
    if (mergePlan.empty || mergePlan.weights.isEmpty()) return null
    val weights = mergePlan.weights.associate { weight ->
        val value = weight.sourceFinalWeight.toFloat()
        weight.jsonIndex to if (value.isFinite() && value > 0f) value else 1.0f
    }
    return weights.takeIf { it.isNotEmpty() }
}

private fun loadFrameQualityInputs(jobDir: File): List<YuvFusionV2FrameQualityInput> {
    val job = runCatching { loadJobJson(jobDir) }.getOrNull() ?: return emptyList()
    val frames = job.optJSONArray("frames") ?: return emptyList()
    return buildList {
        repeat(frames.length()) { position ->
            val frame = frames.optJSONObject(position) ?: return@repeat
            if (!frame.optBoolean("enabled", true) || frame.optBoolean("excludedByUser", false)) {
                return@repeat
            }
            val fileName = frame.optString("file")
            if (fileName.isBlank()) return@repeat
            val file = File(jobDir, fileName).takeIf { it.isFile }
            add(
                YuvFusionV2FrameQualityInput(
                    jsonIndex = position,
                    displayFrameIndex = frame.optInt("index", position),
                    fileName = fileName,
                    file = file,
                    frameJson = frame
                )
            )
        }
    }
}

private fun hasUsableMetadataScores(frameJson: JSONObject): Boolean =
    frameJson.optDoubleOrNull("qualityScore") != null ||
        frameJson.optDoubleOrNull("sharpnessScore") != null ||
        frameJson.optDoubleOrNull("exposureScore") != null

private fun estimateSharpnessScore(
    frameJson: JSONObject,
    sample: YuvFusionV2LumaSample?
): Double? {
    frameJson.optDoubleOrNull("sharpnessScore")?.let { return it.coerceIn(0.0, 1.0) }
    val luma = sample ?: return null
    if (luma.values.isEmpty()) return null
    var gradientSum = 0.0
    var gradientCount = 0
    for (y in 1 until luma.height - 1) {
        for (x in 1 until luma.width - 1) {
            val index = y * luma.width + x
            gradientSum += abs(luma.values[index + 1] - luma.values[index - 1])
            gradientSum += abs(luma.values[index + luma.width] - luma.values[index - luma.width])
            gradientCount += 2
        }
    }
    if (gradientCount == 0) return null
    return (gradientSum / gradientCount / 24.0).coerceIn(0.0, 1.0)
}

private fun estimateExposureScore(
    frameJson: JSONObject,
    sample: YuvFusionV2LumaSample?
): Double? {
    frameJson.optDoubleOrNull("exposureScore")?.let { return it.coerceIn(0.0, 1.0) }
    val luma = sample ?: return null
    if (luma.values.isEmpty()) return null
    val mean = luma.values.average().toFloat()
    var shadows = 0
    var highlights = 0
    luma.values.forEach { value ->
        if (value <= 5f) shadows++
        if (value >= 250f) highlights++
    }
    val shadowRatio = shadows.toFloat() / luma.values.size
    val highlightRatio = highlights.toFloat() / luma.values.size
    val brightnessPenalty = (abs(mean - 115f) / 180f).coerceIn(0f, 0.55f)
    val clippingPenalty = (shadowRatio * 0.7f + highlightRatio * 0.9f).coerceIn(0f, 0.75f)
    return (1.0 - brightnessPenalty - clippingPenalty).coerceIn(0.0, 1.0)
}

private fun estimateMetadataScore(frameJson: JSONObject): Double? {
    frameJson.optDoubleOrNull("qualityScore")?.let { return it.coerceIn(0.0, 1.0) }
    val components = listOfNotNull(
        frameJson.optDoubleOrNull("sharpnessScore"),
        frameJson.optDoubleOrNull("exposureScore"),
        frameJson.optDoubleOrNull("motionScore")?.let { (1.0 - it).coerceIn(0.0, 1.0) }
    )
    if (components.isEmpty()) return null
    return components.average().coerceIn(0.0, 1.0)
}

private fun combineFrameQualityScores(
    jsonIndex: Int,
    displayFrameIndex: Int,
    fileName: String,
    sharpnessScore: Double?,
    exposureScore: Double?,
    metadataScore: Double?
): YuvFusionV2FrameQuality {
    val sharpness = sharpnessScore ?: V2_NEUTRAL_SCORE
    val exposure = exposureScore ?: V2_NEUTRAL_SCORE
    val metadata = metadataScore ?: V2_NEUTRAL_SCORE
    val combined = (sharpness * 0.40 + exposure * 0.35 + metadata * 0.25).coerceIn(0.0, 1.0)
    val finalWeight = (V2_NEUTRAL_WEIGHT * (0.35 + combined * 0.65)).coerceIn(0.15, 1.0)
    return YuvFusionV2FrameQuality(
        jsonIndex = jsonIndex,
        displayFrameIndex = displayFrameIndex,
        fileName = fileName,
        sharpnessScore = sharpnessScore,
        exposureScore = exposureScore,
        metadataScore = metadataScore,
        finalWeight = finalWeight
    )
}

private fun decodeV2LumaSample(file: File): YuvFusionV2LumaSample? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (
        max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > V2_QUALITY_MAX_SAMPLE_DIMENSION
    ) {
        sampleSize *= 2
    }
    val bitmap = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
    ) ?: return null
    return try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val values = FloatArray(pixels.size)
        pixels.forEachIndexed { index, color ->
            values[index] = (
                0.299f * Color.red(color) +
                    0.587f * Color.green(color) +
                    0.114f * Color.blue(color)
                )
        }
        YuvFusionV2LumaSample(bitmap.width, bitmap.height, values)
    } finally {
        bitmap.recycle()
    }
}

private fun clearYuvFusionV2Metadata(job: JSONObject) {
    V2_OWNED_METADATA_KEYS.forEach { key -> job.remove(key) }
}

private fun writeYuvFusionV2Metadata(
    jobDir: File,
    metadata: YuvFusionV2MetadataWrite
) {
    val job = loadJobJson(jobDir)
    clearYuvFusionV2Metadata(job)
    val experimentalVersion = when {
        metadata.dryRun -> YUV_FUSION_V2_DRY_RUN_VERSION
        metadata.weightedMergeUsed -> YUV_FUSION_V2_QUALITY_WEIGHTED_VERSION
        metadata.fallbackToV1 -> YUV_FUSION_V2_SKELETON_VERSION
        else -> YUV_FUSION_V2_SKELETON_VERSION
    }
    job.put("experimentalFusionVersion", experimentalVersion)
        .put("v2SkeletonUsed", metadata.dryRun || !metadata.weightedMergeUsed)
        .put("yuvFusionV2DryRun", metadata.dryRun)
        .put("yuvFusionV2ScoringRan", metadata.scoringRan)
        .put("yuvFusionV2ScoringFailed", metadata.scoringResult.scoringFailed)
        .put("yuvFusionV2MergePlanEmpty", metadata.mergePlan.empty)
        .put("yuvFusionV2MergePlanReason", metadata.mergePlan.reason)
        .put("yuvFusionV2WeightedMergeAttempted", metadata.weightedMergeAttempted)
        .put("yuvFusionV2WeightedMergeUsed", metadata.weightedMergeUsed)
        .put("yuvFusionV2FallbackToV1", metadata.fallbackToV1)
        .put("yuvFusionV2DryRunCalledClassicFusion", metadata.dryRunCalledClassicFusion)
        .put("yuvFusionV2ClassicFusionFailed", metadata.classicFusionFailed)
    metadata.classicFusionFailureType?.let { type ->
        job.put("yuvFusionV2ClassicFusionFailureType", type)
    }
    metadata.classicFusionFailureMessage?.let { message ->
        job.put("yuvFusionV2ClassicFusionFailureMessage", message)
    }
    metadata.classicFusionFailureStackTrace?.let { stackTrace ->
        job.put("yuvFusionV2ClassicFusionFailureStackTrace", stackTrace)
    }
    metadata.fallbackReason?.let { reason ->
        job.put("yuvFusionV2FallbackReason", reason)
    }
    if (metadata.scoringResult.scores.isNotEmpty()) {
        job.put(
            "yuvFusionV2FrameQualityScores",
            frameQualityScoresToJson(metadata.scoringResult.scores)
        )
    }
    if (metadata.scoringResult.skipped.isNotEmpty()) {
        job.put(
            "yuvFusionV2SkippedFrames",
            skippedFramesToJson(metadata.scoringResult.skipped)
        )
    }
    if (metadata.mergePlan.weights.isNotEmpty()) {
        job.put("yuvFusionV2MergePlanPreview", mergePlanPreviewToJson(metadata.mergePlan.weights))
    }
    saveJobJson(jobDir, job)
}

private fun frameQualityScoresToJson(scores: List<YuvFusionV2FrameQuality>): JSONArray =
    JSONArray().apply {
        scores.forEach { score ->
            put(
                putFrameIdentity(
                    JSONObject(),
                    jsonIndex = score.jsonIndex,
                    displayFrameIndex = score.displayFrameIndex
                )
                    .put("fileName", score.fileName)
                    .put("sharpnessScore", score.sharpnessScore ?: JSONObject.NULL)
                    .put("exposureScore", score.exposureScore ?: JSONObject.NULL)
                    .put("metadataScore", score.metadataScore ?: JSONObject.NULL)
                    .put("finalWeight", score.finalWeight)
            )
        }
    }

private fun skippedFramesToJson(skipped: List<YuvFusionV2SkippedFrame>): JSONArray =
    JSONArray().apply {
        skipped.forEach { frame ->
            put(
                putFrameIdentity(
                    JSONObject(),
                    jsonIndex = frame.jsonIndex,
                    displayFrameIndex = frame.displayFrameIndex
                )
                    .put("fileName", frame.fileName)
                    .put("reason", frame.reason)
            )
        }
    }

private fun mergePlanPreviewToJson(weights: List<YuvFusionV2MergeWeight>): JSONArray =
    JSONArray().apply {
        weights.forEach { weight ->
            put(
                putFrameIdentity(
                    JSONObject(),
                    jsonIndex = weight.jsonIndex,
                    displayFrameIndex = weight.displayFrameIndex
                )
                    .put("fileName", weight.fileName)
                    .put("normalizedWeight", weight.normalizedWeight)
                    .put("sourceFinalWeight", weight.sourceFinalWeight)
            )
        }
    }

private fun putFrameIdentity(
    target: JSONObject,
    jsonIndex: Int,
    displayFrameIndex: Int
): JSONObject = target
    .put("jsonIndex", jsonIndex)
    .put("displayFrameIndex", displayFrameIndex)
    .put("frameIndex", jsonIndex)

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return optDouble(key).takeIf { it.isFinite() }
}
