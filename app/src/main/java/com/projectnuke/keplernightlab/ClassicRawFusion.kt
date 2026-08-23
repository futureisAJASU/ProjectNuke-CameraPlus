package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private const val CLASSIC_RAW_FUSION_VERSION = "1.0"
private const val CLASSIC_RAW_PROXY_MAX_DIMENSION = 512
private const val CLASSIC_RAW_SEARCH_RADIUS = 24
private const val CLASSIC_RAW_ALIGNMENT_REJECT_SCORE = 0.20f
private const val CLASSIC_RAW_TILE_ROWS = 192
private const val CLASSIC_RAW_REFERENCE_WEIGHT = 1.65f
private const val CLASSIC_RAW_OUTLIER_THRESHOLD = 0.12f
private const val CLASSIC_RAW_OUTLIER_WEIGHT = 0.05f
private const val CLASSIC_RAW_NOISE_MODEL_VERSION = "classic_raw_noise_model_v0_2"
private const val CLASSIC_RAW_SHOT_COEFF = 0.025f
private const val CLASSIC_RAW_READ_NOISE_COEFF = 32.0f

internal fun clampRawOutputValue(normalized: Float, whiteRange: Int): Int {
    require(whiteRange in 1..65535)
    return (
        if (normalized.isFinite()) normalized.roundToInt() else 0
    ).coerceIn(0, whiteRange)
}

internal data class ClassicRawFusionResult(
    val success: Boolean,
    val mergedRawFile: File?,
    val alignmentFile: File?,
    val referenceIndex: Int,
    val referenceReason: String,
    val alignmentStatus: String,
    val debugMetadata: JSONObject?,
    val errorMessage: String?,
    val originalFailure: Throwable? = null,
    val outputCommitted: Boolean = false,
    val postCommitCancellationRequested: Boolean = false
)

private data class ClassicRawFrame(
    val position: Int,
    val input: RawFrameInput,
    var proxy: RawProxy? = null,
    var dx: Int = 0,
    var dy: Int = 0,
    var estimatedDx: Float = 0f,
    var estimatedDy: Float = 0f,
    var integerDx: Int = 0,
    var integerDy: Int = 0,
    var subpixelDx: Float = 0f,
    var subpixelDy: Float = 0f,
    var alignmentScore: Float = 0f,
    var alignmentConfidence: Float = 1f,
    var alignmentBackend: String = "kotlin_integer_v1",
    var alignmentUsedSubpixel: Boolean = false,
    var alignmentFallbackUsed: Boolean = false,
    var alignmentUsed: Boolean = true,
    var globalWeight: Float = 1f,
    var skipReason: String? = null,
    var exposureScale: Float = 1f
)

private data class RawProxy(
    val width: Int,
    val height: Int,
    val sampleStep: Int,
    val luma: ByteArray,
    val mean: Float
)

private data class RawAlignment(
    val dx: Float,
    val dy: Float,
    val integerDx: Int,
    val integerDy: Int,
    val subpixelDx: Float,
    val subpixelDy: Float,
    val score: Float,
    val confidence: Float,
    val backend: String,
    val usedSubpixel: Boolean,
    val fallbackUsed: Boolean
)

internal fun runClassicRawFusionMerge(
    jobDir: File,
    job: JSONObject,
    preparedFrames: PreparedRawFusionFrames,
    sensor: RawFusionSensorData,
    blackLevelEstimate: BlackLevelEstimate,
    mergedRawFile: File,
    alignmentFile: File,
    fusionAlgorithm: FusionAlgorithm = FusionAlgorithm.ROBUST_REFERENCE,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    operationLease: JobOperationLease? = null,
    onStatus: (String) -> Unit
): ClassicRawFusionResult {
    val startedAt = System.currentTimeMillis()
    val processingAttempt = beginProcessingAttempt(
        jobDir,
        mode = "CLASSIC_RAW",
        additionalOwnedKeys = setOf(
            "mergedRawFile", "rawFusionDebugFile", "rawReferencePreviewFile",
            "rawFusedPreviewFile", "rawComparePreviewFile", "rawDebugPreviewFile"
        ),
        operationLease = operationLease
    )
    job.put("processingAttemptId", processingAttempt.id)
    fun persistPostFailure(failure: Throwable, status: String) {
        val currentClaimed = try {
            currentProcessingAttemptHasRequiredOutputClaim(
                jobDir,
                expectedAttemptId = processingAttempt.id
            )
        } catch (claimFailure: Throwable) {
            throw requireNotNull(combineSettlementFailure(failure, claimFailure))
        }
        try {
            updateForProcessingAttempt(jobDir, processingAttempt) { current ->
                current.put("processStatus", if (currentClaimed) "PIPELINE_COMPLETE_PARTIAL" else status)
                    .put("currentPipelineStage", if (currentClaimed) "PIPELINE_COMPLETE_PARTIAL" else "FAILED")
                    .put("processError", "${failure.javaClass.simpleName}: ${failure.message}")
                    .put("processingFailureType", failure.javaClass.simpleName)
                    .put("processingFailureMessage", failure.message ?: status)
                    .put("finalOutputAvailable", currentClaimed)
                    .put("galleryDisplayUnavailable", !currentClaimed)
            }
        } catch (metadataFailure: Error) {
            if (failure is Error) {
                if (failure !== metadataFailure) failure.addSuppressed(metadataFailure)
                throw failure
            }
            metadataFailure.addSuppressed(failure)
            throw metadataFailure
        } catch (metadataFailure: Exception) {
            if (failure is Error) failure.addSuppressed(metadataFailure)
            else Log.e("KeplerRawPipeline", "RAW failure metadata persistence failed", metadataFailure)
            if (failure is Error) throw failure
        }
    }
    var primaryFailure: Throwable? = null
    return try {
        cancellation.throwIfCancelled()
        onStatus("Classic RAW fusion: loading frames...")
        val frames = preparedFrames.inputs.mapIndexed { index, input ->
            ClassicRawFrame(index, input)
        }.toMutableList()
        if (frames.isEmpty()) error("No enabled RAW frames to reprocess")
        frames.forEach { frame ->
            validateRawFrame(frame.input, sensor)?.let { reason ->
                frame.alignmentUsed = false
                frame.globalWeight = 0f
                frame.skipReason = reason
            }
        }
        if (frames.none { it.skipReason == null }) error("No structurally valid RAW frames remain")

        val alignStartedAt = System.currentTimeMillis()
        Log.i("KeplerRawPipeline", "ALIGN_STARTED jobDirAbsolutePath=${jobDir.absolutePath}")
        onStatus("RAW 프레임을 정렬하는 중입니다.")
        onStatus("Classic RAW fusion: building alignment proxies...")
        frames.filter { it.skipReason == null }.forEach { frame ->
            try {
                cancellation.throwIfCancelled()
                frame.proxy = buildRawProxy(frame.input.file, sensor, blackLevelEstimate, cancellation)
            } catch (oom: OutOfMemoryError) {
                throw oom
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                frame.skipReason = "${e.javaClass.simpleName}: ${e.message}"
                frame.alignmentUsed = false
                frame.globalWeight = 0f
            }
        }
        val validFrames = frames.filter { it.skipReason == null && it.proxy != null }
        if (validFrames.isEmpty()) error("No readable RAW frames remain")
        val reference = selectRawReference(validFrames)
        reference.globalWeight = CLASSIC_RAW_REFERENCE_WEIGHT
        onStatus("Classic RAW fusion: selected reference frame ${reference.position + 1}")
        val refProxy = requireNotNull(reference.proxy)
        val refExposure = exposureProduct(reference.input.meta)
        require(refExposure.isFinite() && refExposure > 0f) {
            "REFERENCE_ONLY_FALLBACK: invalid reference exposure product"
        }
        var nativeAlignmentUsed = false
        var fallbackAlignmentCount = 0
        var lowConfidenceAlignmentCount = 0
        validFrames.forEachIndexed { index, frame ->
            cancellation.throwIfCancelled()
            onStatus("Classic RAW fusion: aligning frame ${index + 1}/${frames.size}...")
            val candidateExposure = exposureProduct(frame.input.meta)
            frame.exposureScale = if (candidateExposure.isFinite() && candidateExposure > 0f) {
                (refExposure / candidateExposure).coerceIn(0.5f, 2.0f)
            } else {
                Float.NaN
            }
            if (frame === reference) {
                frame.dx = 0
                frame.dy = 0
                frame.integerDx = 0
                frame.integerDy = 0
                frame.alignmentScore = 0f
                frame.alignmentConfidence = 1f
                frame.alignmentUsed = true
                frame.globalWeight = CLASSIC_RAW_REFERENCE_WEIGHT
            } else {
                // Native alignment cannot stop mid-call; check on both boundaries.
                cancellation.throwIfCancelled()
                val alignment = estimateRawTranslation(refProxy, requireNotNull(frame.proxy))
                cancellation.throwIfCancelled()
                val estimatedDx = alignment.dx * refProxy.sampleStep
                val estimatedDy = alignment.dy * refProxy.sampleStep
                val cfaSafe = cfaSafeRawShift(estimatedDx, estimatedDy)
                frame.estimatedDx = estimatedDx
                frame.estimatedDy = estimatedDy
                frame.dx = cfaSafe.appliedDx
                frame.dy = cfaSafe.appliedDy
                frame.integerDx = alignment.integerDx * refProxy.sampleStep
                frame.integerDy = alignment.integerDy * refProxy.sampleStep
                frame.subpixelDx = alignment.subpixelDx * refProxy.sampleStep
                frame.subpixelDy = alignment.subpixelDy * refProxy.sampleStep
                frame.alignmentScore = alignment.score
                frame.alignmentConfidence = alignment.confidence
                frame.alignmentBackend = alignment.backend
                frame.alignmentUsedSubpixel = alignment.usedSubpixel
                frame.alignmentFallbackUsed = alignment.fallbackUsed
                if (alignment.backend == "native_subpixel_v1") nativeAlignmentUsed = true
                if (alignment.fallbackUsed) fallbackAlignmentCount++
                if (alignment.confidence < 0.35f) lowConfidenceAlignmentCount++
                frame.alignmentUsed = alignment.score.isFinite() &&
                    alignment.confidence.isFinite() &&
                    frame.estimatedDx.isFinite() && frame.estimatedDy.isFinite() &&
                    frame.exposureScale.isFinite() &&
                    alignment.score <= CLASSIC_RAW_ALIGNMENT_REJECT_SCORE
                frame.globalWeight = if (frame.alignmentUsed) {
                    (1f - alignment.score / CLASSIC_RAW_ALIGNMENT_REJECT_SCORE)
                        .coerceIn(0.12f, 1f)
                } else 0f
            }
            if (frame !== reference && (!frame.exposureScale.isFinite() || frame.exposureScale <= 0f)) {
                frame.alignmentUsed = false
                frame.globalWeight = 0f
                frame.skipReason = "NON_FINITE_EXPOSURE_SCALE"
            }
            applyRawAlignmentToFrameJson(frame)
        }
        val acceptedFrames = frames.filter { frame ->
            frame === reference || (
                frame.alignmentUsed && frame.globalWeight.isFinite() && frame.globalWeight > 0f &&
                    frame.exposureScale.isFinite() &&
                    frame.alignmentScore.isFinite() && frame.alignmentConfidence.isFinite()
                )
        }
        frames.filterNot { it in acceptedFrames }.forEach { rejectedFrame ->
            rejectedFrame.alignmentUsed = false
            rejectedFrame.globalWeight = 0f
            rejectedFrame.skipReason = rejectedFrame.skipReason ?: "NON_FINITE_OR_REJECTED_ALIGNMENT"
            applyRawAlignmentToFrameJson(rejectedFrame)
        }
        val referenceOnlyFallback = acceptedFrames.size < MIN_RAW_FUSION_FRAMES
        val nativeAlignMs = System.currentTimeMillis() - alignStartedAt
        Log.i("KeplerRawPipeline", "ALIGN_COMPLETE jobDirAbsolutePath=${jobDir.absolutePath} nativeAlignMs=$nativeAlignMs")

        val mergeStartedAt = System.currentTimeMillis()
        Log.i("KeplerRawPipeline", "MERGE_STARTED jobDirAbsolutePath=${jobDir.absolutePath}")
        onStatus("RAW 프레임을 병합하는 중입니다.")
        onStatus("Classic RAW fusion: merging RAW tiles...")
        var mergeStatsHolder: RawMergeStats? = null
        val mergeArtifact = commitProcessingArtifact(
            finalFile = mergedRawFile,
            cancellation = cancellation,
            writeTemp = { temp ->
                mergeStatsHolder = mergeClassicRawTiles(
                    frames = acceptedFrames,
                    reference = reference,
                    sensor = sensor,
                    blackLevelEstimate = blackLevelEstimate,
                    mergedRawFile = temp,
                    fusionAlgorithm = fusionAlgorithm,
                    cancellation = cancellation,
                    onStatus = onStatus
                )
            },
            verifyFinal = { committed -> verifyRaw16Artifact(committed, sensor) },
            onSettlement = processingArtifactSettlementObserver(jobDir, processingAttempt),
            processingAttemptId = processingAttempt.id,
            claimKey = "mergedRawFile"
        )
        val completedMergeStats = requireNotNull(mergeStatsHolder) { "Classic RAW merge did not produce statistics" }
        markProcessingArtifactClaim(jobDir, processingAttempt, "mergedRawFile", mergedRawFile)
        val postCommitCancellation = cancellation.isCancelled
        if (postCommitCancellation) {
            markProcessingPostCommitCancellation(jobDir, processingAttempt)
            return ClassicRawFusionResult(
                success = true,
                mergedRawFile = mergedRawFile,
                alignmentFile = null,
                referenceIndex = reference.position,
                referenceReason = selectRawReferenceReason(frames),
                alignmentStatus = "CLASSIC_RAW_MERGE_COMMITTED_CANCELLED",
                debugMetadata = null,
                errorMessage = "RAW merge committed before cancellation; optional processing skipped.",
                outputCommitted = true,
                postCommitCancellationRequested = true
            )
        }
        if (completedMergeStats.descriptorCleanupFailures.isNotEmpty()) {
            recordRawDescriptorCleanupFailures(job, completedMergeStats.descriptorCleanupFailures)
        }
        val mergeStats = completedMergeStats
        cancellation.throwIfCancelled()
        val nativeMergeMs = System.currentTimeMillis() - mergeStartedAt
        Log.i("KeplerRawPipeline", "MERGE_COMPLETE jobDirAbsolutePath=${jobDir.absolutePath} nativeMergeMs=$nativeMergeMs")
        onStatus("Native RAW ISP 렌더링 중입니다.")
        val debug = buildRawFusionDebug(
            job = job,
            frames = frames,
            preparedFrames = preparedFrames,
            reference = reference,
            sensor = sensor,
            mergeStats = mergeStats,
            processingTimeMs = System.currentTimeMillis() - startedAt,
            nativeAlignmentUsed = nativeAlignmentUsed,
            fallbackAlignmentCount = fallbackAlignmentCount,
            lowConfidenceAlignmentCount = lowConfidenceAlignmentCount
        )
        debug.put("nativeAlignMs", nativeAlignMs)
            .put("nativeMergeMs", nativeMergeMs)
            .put("mergeWeightMapAvailable", false)
            .put("mergeWeightMapFile", JSONObject.NULL)
            .put("mergeRejectMapAvailable", false)
            .put("mergeRejectMapFile", JSONObject.NULL)
        job.put("nativeAlignMs", nativeAlignMs)
            .put("nativeMergeMs", nativeMergeMs)
            .put("mergeWeightMapAvailable", false)
            .put("mergeWeightMapFile", JSONObject.NULL)
            .put("mergeRejectMapAvailable", false)
            .put("mergeRejectMapFile", JSONObject.NULL)
        cancellation.throwIfCancelled()
        writeVerifiedJsonArtifact(
            alignmentFile,
            debug.toString(2),
            processingArtifactSettlementObserver(jobDir, processingAttempt)
        )
        cancellation.throwIfCancelled()
        // Debug preview generation is optional; cancellation is checked around it.
        writeRawFusionDebugPreviews(jobDir, reference, mergedRawFile, sensor, blackLevelEstimate, job, cancellation)
        cancellation.throwIfCancelled()

        job.put("rawFusionEngine", "classic_raw_v1")
            .put("rawFusionVersion", CLASSIC_RAW_FUSION_VERSION)
            .put("rawReferenceFrameIndex", reference.input.meta.optInt("index", reference.position))
            .put("rawReferenceFrameReason", selectRawReferenceReason(frames))
            .put("fusionAlgorithm", fusionAlgorithm.name)
            .put("rawFusionFallback", if (referenceOnlyFallback) "REFERENCE_ONLY_FALLBACK" else JSONObject.NULL)
            .put("usedFrameCount", frames.count { it.skipReason == null && it.alignmentUsed && it.globalWeight > 0f })
            .put("excludedFrameCount", countRawExcludedFrames(job))
            .put(
                "skippedFrameCount",
                ((job.optJSONArray("frames")?.length() ?: frames.size) -
                    countRawExcludedFrames(job) - frames.count { it.skipReason == null && it.alignmentUsed && it.globalWeight > 0f }).coerceAtLeast(0)
            )
            .put("rawGhostSuppressionUsed", true)
            .put("rawNoiseModelVersion", CLASSIC_RAW_NOISE_MODEL_VERSION)
            .put("shotCoeff", CLASSIC_RAW_SHOT_COEFF)
            .put("readNoiseCoeff", CLASSIC_RAW_READ_NOISE_COEFF)
            .put("rawOutlierRejectedRatio", mergeStats.rejectedRatio)
            .put("rawOutlierDownweightedRatio", mergeStats.downweightedRatio)
            .put("memoryPlanTileRows", mergeStats.memoryPlan.tileRows)
            .put("memoryPlanCandidateBatchSize", mergeStats.memoryPlan.candidateBatchSize)
            .put("memoryPlanEstimatedPeakBytes", mergeStats.memoryPlan.estimatedPeakBytes)
            .put("memoryPlanFallbackReason", mergeStats.memoryPlan.fallbackReason ?: JSONObject.NULL)
            .put("rawAlignmentSummary", debug.optJSONArray("alignments") ?: JSONArray())
            .put("nativeAlignmentAvailable", NativeFusionAlignment.isAvailable())
            .put("nativeAlignmentUsed", nativeAlignmentUsed)
            .put("alignmentVersion", if (nativeAlignmentUsed) "native_subpixel_v1" else "kotlin_integer_v1")
            .put("fallbackAlignmentCount", fallbackAlignmentCount)
            .put("lowConfidenceAlignmentCount", lowConfidenceAlignmentCount)
            .put("rawFusionProcessedAt", System.currentTimeMillis())
            .put("rawFusionProcessingTimeMs", debug.optLong("processingTimeMs"))
            .put("mergedRawFile", mergedRawFile.name)
            .put("rawFusionDebugFile", alignmentFile.name)
            .put("alignmentFile", alignmentFile.name)
            .put(
                "alignmentStatus",
                if (referenceOnlyFallback) "REFERENCE_ONLY_FALLBACK" else "CLASSIC_RAW_FUSION_V1_COMPLETE"
            )
            .put("nativeRawMerge", false)
            .put("rawFusionNotes", "Classic RAW v1: downsampled green-channel alignment, tiled RAW-domain robust merge, signal-aware conservative outlier suppression.")

        ClassicRawFusionResult(
            success = true,
            mergedRawFile = mergedRawFile,
            alignmentFile = alignmentFile,
            referenceIndex = reference.position,
            referenceReason = selectRawReferenceReason(frames),
            alignmentStatus = if (referenceOnlyFallback) "REFERENCE_ONLY_FALLBACK" else "CLASSIC_RAW_FUSION_V1_COMPLETE",
            debugMetadata = debug,
            errorMessage = null
            ,outputCommitted = true
        )
    } catch (ce: CancellationException) {
        primaryFailure = ce
        try {
            persistPostFailure(ce, "CANCELLED")
        } catch (secondary: Throwable) {
            primaryFailure = requireNotNull(combineSettlementFailure(ce, secondary))
            throw primaryFailure!!
        }
        throw ce
    } catch (oom: OutOfMemoryError) {
        primaryFailure = oom
        try {
            persistPostFailure(oom, "OOM_FAILED_KEEPING_CACHE")
        } catch (secondary: Throwable) {
            primaryFailure = requireNotNull(combineSettlementFailure(oom, secondary))
            throw primaryFailure!!
        }
        throw oom
    } catch (fatal: Error) {
        primaryFailure = fatal
        try {
            persistPostFailure(fatal, "FATAL_FAILED_KEEPING_CACHE")
        } catch (secondary: Throwable) {
            primaryFailure = requireNotNull(combineSettlementFailure(fatal, secondary))
            throw primaryFailure!!
        }
        throw fatal
    } catch (e: Exception) {
        primaryFailure = e
        try {
            persistPostFailure(e, "CLASSIC_RAW_FUSION_FAILED_KEEPING_CACHE")
        } catch (secondary: Throwable) {
            primaryFailure = requireNotNull(combineSettlementFailure(e, secondary))
            throw primaryFailure!!
        }
        val currentClaimed = try {
            currentProcessingAttemptHasRequiredOutputClaim(
                jobDir,
                expectedAttemptId = processingAttempt.id
            )
        } catch (failure: Throwable) {
            throw requireNotNull(combineSettlementFailure(e, failure))
        }
        job.put("processStatus", if (currentClaimed) "PIPELINE_COMPLETE_PARTIAL" else "CLASSIC_RAW_FUSION_FAILED_KEEPING_CACHE")
            .put("rawFusionEngine", "classic_raw_v1")
            .put("processError", "${e.javaClass.simpleName}: ${e.message}")
            .put("processedAt", System.currentTimeMillis())
        ClassicRawFusionResult(
            currentClaimed,
            mergedRawFile.takeIf { currentClaimed },
            alignmentFile.takeIf { it.isFile },
            0,
            "failed",
            if (currentClaimed) "CLASSIC_RAW_MERGE_COMMITTED_PARTIAL" else "CLASSIC_RAW_FUSION_FAILED",
            null,
            "${e.javaClass.simpleName}: ${e.message}",
            originalFailure = e,
            outputCommitted = currentClaimed
        )
    } finally {
        var cleanupFailure: Throwable? = null
        try {
            processingAttempt.releaseOwnedLease()
        } catch (failure: Throwable) {
            cleanupFailure = failure
        }
        val combined = combineSettlementFailure(primaryFailure, cleanupFailure)
        if (combined !== primaryFailure) throw requireNotNull(combined)
    }
}

private data class RawMergeStats(
    val rejectedPixels: Long,
    val comparedPixels: Long,
    val downweightedPixels: Long,
    val memoryPlan: FusionMemoryPlan,
    val descriptorCleanupFailures: List<ProcessingResourceSettlementRecord> = emptyList()
) {
    val rejectedRatio: Double get() =
        if (comparedPixels > 0L) rejectedPixels.toDouble() / comparedPixels else 0.0
    val downweightedRatio: Double get() =
        if (comparedPixels > 0L) downweightedPixels.toDouble() / comparedPixels else 0.0
}

private fun buildRawProxy(
    file: File,
    sensor: RawFusionSensorData,
    blackLevelEstimate: BlackLevelEstimate,
    cancellation: KeplerPipelineCancellation
): RawProxy {
    val step = generateSequence(1) { it * 2 }
        .first { max(sensor.width / it, sensor.height / it) <= CLASSIC_RAW_PROXY_MAX_DIMENSION }
    val proxyWidth = max(1, sensor.width / step)
    val proxyHeight = max(1, sensor.height / step)
    val luma = ByteArray(proxyWidth * proxyHeight)
    val row = ShortArray(sensor.width)
    val rowBytes = ByteArray(sensor.width * 2)
    cancellation.throwIfCancelled()
    val inputHandle = VerifiedRandomAccessHandle.open(
        file,
        sensor.width.toLong() * sensor.height.toLong() * 2L
    )
    return inputHandle.use { input ->
        var out = 0
        var y = 0
        while (y < sensor.height && out < luma.size) {
            if ((y and (step * 31)) == 0) cancellation.throwIfCancelled()
            readRawRow(input, sensor.width, y, row, rowBytes)
            var x = greenAlignedRawX(sensor.cfa, y, 0)
            var col = 0
            while (col < proxyWidth && out < luma.size) {
                val safeX = x.coerceAtMost(sensor.width - 1)
                val raw = row[safeX].toInt() and 0xFFFF
                val black = blackLevelForPixel(safeX, y, sensor.cfa, blackLevelEstimate)
                val value = ((raw - black).coerceAtLeast(0).toFloat() /
                    (sensor.whiteLevel - sensor.blackLevel).coerceAtLeast(1) * 255f)
                    .roundToInt().coerceIn(0, 255)
                luma[out++] = value.toByte()
                x = greenAlignedRawX(sensor.cfa, y, x + step)
                col++
            }
            y += step
        }
        inputHandle.verifyPathStillMatches()
        cancellation.throwIfCancelled()
        val mean = luma.fold(0L) { sum, value -> sum + (value.toInt() and 0xFF) }
            .toFloat() / luma.size.coerceAtLeast(1)
        RawProxy(proxyWidth, proxyHeight, step, luma, mean)
    }
}

private fun selectRawReference(frames: List<ClassicRawFrame>): ClassicRawFrame {
    val scored = frames.filter {
        val quality = it.input.meta.optDouble("qualityScore", Double.NaN)
        val sharpness = it.input.meta.optDouble("sharpnessScore", Double.NaN)
        (quality.isFinite() || sharpness.isFinite()) && it.proxy != null && it.skipReason == null
    }
    return scored.maxWithOrNull(
        compareBy<ClassicRawFrame> {
            it.input.meta.optDouble("qualityScore", Double.NEGATIVE_INFINITY)
        }.thenBy {
            it.input.meta.optDouble("sharpnessScore", Double.NEGATIVE_INFINITY)
        }
    ) ?: frames[frames.size / 2]
}

private fun selectRawReferenceReason(frames: List<ClassicRawFrame>): String {
    return if (frames.any { it.input.meta.optDouble("qualityScore", Double.NaN).isFinite() }) {
        "highest_quality_score"
    } else if (frames.any { it.input.meta.optDouble("sharpnessScore", Double.NaN).isFinite() }) {
        "highest_sharpness_score"
    } else {
        "middle_frame"
    }
}

private fun estimateRawTranslation(reference: RawProxy, candidate: RawProxy): RawAlignment {
    require(reference.width == candidate.width && reference.height == candidate.height) {
        "Proxy dimensions differ"
    }
    var bestDx = 0
    var bestDy = 0
    var bestScore = Float.MAX_VALUE
    for (dy in -CLASSIC_RAW_SEARCH_RADIUS..CLASSIC_RAW_SEARCH_RADIUS step 4) {
        for (dx in -CLASSIC_RAW_SEARCH_RADIUS..CLASSIC_RAW_SEARCH_RADIUS step 4) {
            val score = rawProxyMad(reference, candidate, dx, dy, 4)
            if (score < bestScore) {
                bestScore = score
                bestDx = dx
                bestDy = dy
            }
        }
    }
    for (dy in max(-CLASSIC_RAW_SEARCH_RADIUS, bestDy - 3)..min(CLASSIC_RAW_SEARCH_RADIUS, bestDy + 3)) {
        for (dx in max(-CLASSIC_RAW_SEARCH_RADIUS, bestDx - 3)..min(CLASSIC_RAW_SEARCH_RADIUS, bestDx + 3)) {
            val score = rawProxyMad(reference, candidate, dx, dy, 3)
            if (score < bestScore) {
                bestScore = score
                bestDx = dx
                bestDy = dy
            }
        }
    }
    val native = if (NativeFusionAlignment.isAvailable()) {
        NativeFusionAlignment.alignLumaFrames(
            reference = reference.luma,
            candidate = candidate.luma,
            width = reference.width,
            height = reference.height,
            rowStride = reference.width,
            searchRadius = CLASSIC_RAW_SEARCH_RADIUS
        )
    } else {
        null
    }
    if (native != null && native.confidence >= 0.35f) {
        return RawAlignment(
            dx = native.dx,
            dy = native.dy,
            integerDx = native.integerDx,
            integerDy = native.integerDy,
            subpixelDx = native.subpixelDx,
            subpixelDy = native.subpixelDy,
            score = native.score,
            confidence = native.confidence,
            backend = native.backend,
            usedSubpixel = native.usedSubpixel,
            fallbackUsed = false
        )
    }
    return RawAlignment(
        dx = bestDx.toFloat(),
        dy = bestDy.toFloat(),
        integerDx = bestDx,
        integerDy = bestDy,
        subpixelDx = 0f,
        subpixelDy = 0f,
        score = bestScore,
        confidence = (1f - bestScore / 0.20f).coerceIn(0f, 1f),
        backend = "kotlin_integer_v1",
        usedSubpixel = false,
        fallbackUsed = native != null
    )
}

private fun rawProxyMad(reference: RawProxy, candidate: RawProxy, dx: Int, dy: Int, step: Int): Float {
    val margin = CLASSIC_RAW_SEARCH_RADIUS + 8
    val left = margin
    val top = margin
    val right = reference.width - margin
    val bottom = reference.height - margin
    if (right <= left || bottom <= top) return Float.MAX_VALUE
    var diff = 0L
    var count = 0
    var y = top
    while (y < bottom) {
        var x = left
        while (x < right) {
            val ref = reference.luma[y * reference.width + x].toInt() and 0xFF
            val other = candidate.luma[(y + dy) * candidate.width + x + dx].toInt() and 0xFF
            diff += abs(ref - other)
            count++
            x += step
        }
        y += step
    }
    return diff.toFloat() / count.coerceAtLeast(1) / 255f
}

private fun mergeClassicRawTiles(
    frames: List<ClassicRawFrame>,
    reference: ClassicRawFrame,
    sensor: RawFusionSensorData,
    blackLevelEstimate: BlackLevelEstimate,
    mergedRawFile: File,
    fusionAlgorithm: FusionAlgorithm,
    cancellation: KeplerPipelineCancellation,
    onStatus: (String) -> Unit
): RawMergeStats {
    var rejected = 0L
    var downweighted = 0L
    var compared = 0L
    val whiteRange = (sensor.whiteLevel - sensor.blackLevel).coerceAtLeast(1)
    require(sensor.blackLevel in 0..65535 && sensor.whiteLevel in 1..65535) {
        "RAW output range is outside the 16-bit contract"
    }
    require(whiteRange in 1..65535) { "RAW white range is invalid" }
    val frameInputs = linkedMapOf<ClassicRawFrame, VerifiedRandomAccessHandle>()
    val sourceRows = frames.associateWith { ShortArray(sensor.width) }
    val memoryPlan = planFusionMemory(
        FusionMemoryPlanRequest(
            width = sensor.width,
            tileRows = CLASSIC_RAW_TILE_ROWS,
            candidateFrames = frames.size,
            availableBytes = currentAvailableJavaHeapBytes(),
            javaBytesPerPixel = 6L,
            nativeBytesPerPixel = 4L
        )
    )
    check(!memoryPlan.cannotFit) { memoryPlan.fallbackReason ?: "CannotFit" }
    val mergeTileRows = memoryPlan.tileRows
    val tileArraySize = sensor.width.toLong() * mergeTileRows.toLong()
    require(tileArraySize in 1L..Int.MAX_VALUE) { "RAW tile array size exceeds JVM limits" }
    val refRows = Array(mergeTileRows) { ShortArray(sensor.width) }
    val acc = FloatArray(tileArraySize.toInt())
    val weights = FloatArray(tileArraySize.toInt())
    val outRow = ByteArray(sensor.width * 2)
    val rowBytes = ByteArray(sensor.width * 2)
    var descriptorCleanupFailures: List<ProcessingResourceSettlementRecord> = emptyList()
    var primaryFailure: Throwable? = null
    try {
        frames.forEach { frame ->
            cancellation.throwIfCancelled()
            frameInputs[frame] = VerifiedRandomAccessHandle.open(
                frame.input.file,
                sensor.width.toLong() * sensor.height.toLong() * 2L
            )
        }
        BufferedOutputStream(FileOutputStream(mergedRawFile)).use { output ->
            var tileTop = 0
            while (tileTop < sensor.height) {
                cancellation.throwIfCancelled()
                val tileRows = min(mergeTileRows, sensor.height - tileTop)
                acc.fill(0f, 0, sensor.width * tileRows)
                weights.fill(0f, 0, sensor.width * tileRows)
                for (row in 0 until tileRows) {
                    if ((row and 15) == 0) cancellation.throwIfCancelled()
                    readRawRow(
                        frameInputs.getValue(reference).randomAccess,
                        sensor.width,
                        tileTop + row,
                        refRows[row],
                        rowBytes
                    )
                }

                frames.forEachIndexed { frameIndex, frame ->
                    cancellation.throwIfCancelled()
                    onStatus("Classic RAW fusion: merging RAW tiles ${frameIndex + 1}/${frames.size}")
                    val raf = frameInputs.getValue(frame).randomAccess
                    val rowBuffer = sourceRows.getValue(frame)
                    val globalWeight = if (fusionAlgorithm == FusionAlgorithm.MOTION_SAFE && frame === reference) {
                        frame.globalWeight * 1.75f
                    } else {
                        frame.globalWeight
                    }
                    for (row in 0 until tileRows) {
                        if ((row and 15) == 0) cancellation.throwIfCancelled()
                        val y = tileTop + row
                        val sourceY = y + frame.dy
                        if (sourceY !in 0 until sensor.height) continue
                        readRawRow(raf, sensor.width, sourceY, rowBuffer, rowBytes)
                        for (x in 0 until sensor.width) {
                            if ((x and 1023) == 0) cancellation.throwIfCancelled()
                            val sourceX = x + frame.dx
                            if (sourceX !in 0 until sensor.width) continue
                            val index = row * sensor.width + x
                            val raw = rowBuffer[sourceX].toInt() and 0xFFFF
                            val black = blackLevelForPixel(sourceX, sourceY, sensor.cfa, blackLevelEstimate)
                            val corrected = (raw - black).coerceAtLeast(0) * frame.exposureScale
                            var localWeight = globalWeight
                            if (frame !== reference) {
                                val refRaw = refRows[row][x].toInt() and 0xFFFF
                                val refBlack = blackLevelForPixel(x, y, sensor.cfa, blackLevelEstimate)
                                val refCorrected = (refRaw - refBlack).coerceAtLeast(0).toFloat()
                                val diffAbs = abs(corrected - refCorrected)
                                val variance = CLASSIC_RAW_SHOT_COEFF *
                                    max(refCorrected, corrected).coerceAtLeast(0f) +
                                    CLASSIC_RAW_READ_NOISE_COEFF
                                val normalizedResidual = diffAbs / kotlin.math.sqrt(variance.coerceAtLeast(1f))
                                val diff = diffAbs / whiteRange
                                compared++
                                if (fusionAlgorithm == FusionAlgorithm.NOISE_AWARE) {
                                    localWeight *= (1f / (1f + normalizedResidual * normalizedResidual))
                                    if (normalizedResidual > 3.0f) downweighted++
                                } else if (normalizedResidual > 5.0f) {
                                    localWeight *= CLASSIC_RAW_OUTLIER_WEIGHT
                                    rejected++
                                } else if (normalizedResidual > 2.5f) {
                                    val t = ((normalizedResidual - 2.5f) / 2.5f).coerceIn(0f, 1f)
                                    localWeight *= (1f - t).pow(2).coerceAtLeast(0.15f)
                                    downweighted++
                                } else if (diff > CLASSIC_RAW_OUTLIER_THRESHOLD) {
                                    localWeight *= 0.35f
                                    downweighted++
                                }
                            }
                            if (!corrected.isFinite() || !localWeight.isFinite() || localWeight <= 0f) continue
                            acc[index] += corrected * localWeight
                            weights[index] += localWeight
                        }
                    }
                }
                for (row in 0 until tileRows) {
                    if ((row and 15) == 0) cancellation.throwIfCancelled()
                    var out = 0
                    for (x in 0 until sensor.width) {
                        if ((x and 1023) == 0) cancellation.throwIfCancelled()
                        val index = row * sensor.width + x
                        val normalized = acc[index] / weights[index].coerceAtLeast(0.001f)
                        val value = clampRawOutputValue(normalized, whiteRange)
                        outRow[out++] = (value and 0xFF).toByte()
                        outRow[out++] = ((value ushr 8) and 0xFF).toByte()
                    }
                    output.write(outRow)
                }
                tileTop += tileRows
            }
        }
        frameInputs.values.forEach { it.verifyPathStillMatches() }
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        descriptorCleanupFailures = settleVerifiedRawInputHandles(
            frameInputs.entries.map { entry ->
                "frameIndex=${entry.key.position},file=${entry.key.input.file.name}" to entry.value
            },
            primaryFailure = primaryFailure
        )
        if (descriptorCleanupFailures.isNotEmpty()) {
            onStatus("Classic RAW input descriptor cleanup failed: ${descriptorCleanupFailures.size} handle(s)")
            primaryFailure?.let { primary ->
                descriptorCleanupFailures.forEach { debt ->
                    debt.failure?.let(primary::addSuppressed)
                }
            }
        }
    }
    return RawMergeStats(rejected, compared, downweighted, memoryPlan, descriptorCleanupFailures)
}

internal fun settleVerifiedRawInputHandles(
    handles: Iterable<Pair<String, VerifiedRandomAccessHandle>>,
    primaryFailure: Throwable? = null
): List<ProcessingResourceSettlementRecord> {
    val records = ArrayList<ProcessingResourceSettlementRecord>()
    var cleanupFailure: Throwable? = null
    handles.forEach { (identity, handle) ->
        try {
            handle.close()?.let { failure ->
                records += ProcessingResourceSettlementRecord(
                    resource = "VERIFIED_RAW_INPUT_HANDLE",
                    status = "CLOSE_FAILED",
                    failure = failure,
                    identity = identity,
                    operation = "CLOSE"
                )
            }
        } catch (failure: Throwable) {
            cleanupFailure = combineSettlementFailure(cleanupFailure, failure)
        }
    }
    val combined = combineSettlementFailure(primaryFailure, cleanupFailure)
    if (combined !== primaryFailure) throw requireNotNull(combined)
    return records
}

private fun recordRawDescriptorCleanupFailures(
    job: JSONObject,
    failures: List<ProcessingResourceSettlementRecord>
) {
    job.put(
        "rawDescriptorCleanupFailures",
        JSONArray().apply {
            failures.forEach { failure ->
                put(JSONObject().apply {
                    put("resource", failure.resource)
                    put("operation", failure.operation ?: JSONObject.NULL)
                    put("identity", failure.identity ?: JSONObject.NULL)
                    put("status", failure.status)
                    put(
                        "failure",
                        failure.failure?.let { "${it.javaClass.simpleName}: ${it.message}" }
                            ?: JSONObject.NULL
                    )
                })
            }
        }
    )
}

private fun verifyRaw16Artifact(file: File, sensor: RawFusionSensorData) {
    val expected = sensor.width.toLong() * sensor.height.toLong() * 2L
    val evidence = NoFollowFileSystem.digestVerified(file)
    check(evidence.size == expected) {
        "Classic RAW final payload has invalid size: ${evidence.size}, expected=$expected"
    }
}

private fun readRawRow(
    input: RandomAccessFile,
    width: Int,
    y: Int,
    out: ShortArray,
    bytes: ByteArray
) {
    input.seek(y.toLong() * width.toLong() * 2L)
    require(bytes.size >= width * 2) { "RAW row buffer is too small" }
    input.readFully(bytes)
    var byteIndex = 0
    for (x in 0 until width) {
        val lo = bytes[byteIndex++].toInt() and 0xFF
        val hi = bytes[byteIndex++].toInt() and 0xFF
        out[x] = ((hi shl 8) or lo).toShort()
    }
}

private fun applyRawAlignmentToFrameJson(frame: ClassicRawFrame) {
    frame.input.meta.put("rawAlignDx", frame.dx)
        .put("rawAlignDy", frame.dy)
        .putFiniteNumber("rawAlignEstimatedDx", frame.estimatedDx)
        .putFiniteNumber("rawAlignEstimatedDy", frame.estimatedDy)
        .put("rawAlignAppliedCfaDx", frame.dx)
        .put("rawAlignAppliedCfaDy", frame.dy)
        .put("rawAlignIntegerDx", frame.integerDx)
        .put("rawAlignIntegerDy", frame.integerDy)
        .putFiniteNumber("rawAlignSubpixelDx", frame.subpixelDx)
        .putFiniteNumber("rawAlignSubpixelDy", frame.subpixelDy)
        .putFiniteNumber("rawAlignmentScore", frame.alignmentScore)
        .putFiniteNumber("rawAlignmentConfidence", frame.alignmentConfidence)
        .put("rawAlignmentBackend", frame.alignmentBackend)
        .put("rawAlignmentUsedSubpixel", frame.alignmentUsedSubpixel)
        .put("rawAlignmentFallbackUsed", frame.alignmentFallbackUsed)
        .put("rawAlignmentUsed", frame.alignmentUsed)
        .putFiniteNumber("rawGlobalWeight", frame.globalWeight)
}

private fun buildRawFusionDebug(
    job: JSONObject,
    frames: List<ClassicRawFrame>,
    preparedFrames: PreparedRawFusionFrames,
    reference: ClassicRawFrame,
    sensor: RawFusionSensorData,
    mergeStats: RawMergeStats,
    processingTimeMs: Long,
    nativeAlignmentUsed: Boolean,
    fallbackAlignmentCount: Int,
    lowConfidenceAlignmentCount: Int
): JSONObject {
    val alignments = JSONArray()
    frames.forEach { frame ->
        alignments.put(
            JSONObject()
                .put("frameIndex", frame.input.meta.optInt("index", frame.position))
                .put("file", frame.input.file.name)
                .put("rawAlignDx", frame.dx)
                .put("rawAlignDy", frame.dy)
                .putFiniteNumber("rawAlignEstimatedDx", frame.estimatedDx)
                .putFiniteNumber("rawAlignEstimatedDy", frame.estimatedDy)
                .put("rawAlignAppliedCfaDx", frame.dx)
                .put("rawAlignAppliedCfaDy", frame.dy)
                .put("rawAlignIntegerDx", frame.integerDx)
                .put("rawAlignIntegerDy", frame.integerDy)
                .putFiniteNumber("rawAlignSubpixelDx", frame.subpixelDx)
                .putFiniteNumber("rawAlignSubpixelDy", frame.subpixelDy)
                .putFiniteNumber("rawAlignmentScore", frame.alignmentScore)
                .putFiniteNumber("rawAlignmentConfidence", frame.alignmentConfidence)
                .put("rawAlignmentBackend", frame.alignmentBackend)
                .put("rawAlignmentUsedSubpixel", frame.alignmentUsedSubpixel)
                .put("rawAlignmentFallbackUsed", frame.alignmentFallbackUsed)
                .putFiniteNumber("rawGlobalWeight", frame.globalWeight)
                .put("used", frame.skipReason == null && frame.alignmentUsed && frame.globalWeight > 0f)
                .put("skipReason", frame.skipReason ?: JSONObject.NULL)
        )
    }
    return JSONObject()
        .put("rawFusionEngine", "classic_raw_v1")
        .put("rawFusionVersion", CLASSIC_RAW_FUSION_VERSION)
        .put("rawReferenceFrameIndex", reference.input.meta.optInt("index", reference.position))
        .put("usedFrameCount", frames.count { it.skipReason == null && it.alignmentUsed && it.globalWeight > 0f })
        .put("excludedFrameCount", countRawExcludedFrames(job))
        .put("skippedFrameCount", (preparedFrames.savedFrames - frames.count { it.skipReason == null && it.alignmentUsed && it.globalWeight > 0f }).coerceAtLeast(0))
        .put("rawGhostSuppressionUsed", true)
        .put("rawNoiseModelVersion", CLASSIC_RAW_NOISE_MODEL_VERSION)
        .put("shotCoeff", CLASSIC_RAW_SHOT_COEFF)
        .put("readNoiseCoeff", CLASSIC_RAW_READ_NOISE_COEFF)
        .put("rawOutlierRejectedRatio", mergeStats.rejectedRatio)
        .put("rawOutlierDownweightedRatio", mergeStats.downweightedRatio)
        .put("memoryPlanTileRows", mergeStats.memoryPlan.tileRows)
        .put("memoryPlanCandidateBatchSize", mergeStats.memoryPlan.candidateBatchSize)
        .put("memoryPlanEstimatedPeakBytes", mergeStats.memoryPlan.estimatedPeakBytes)
        .put("memoryPlanFallbackReason", mergeStats.memoryPlan.fallbackReason ?: JSONObject.NULL)
        .put("rawAlignmentSummary", alignments)
        .put("processingTimeMs", processingTimeMs)
        .put("nativeAlignmentAvailable", NativeFusionAlignment.isAvailable())
        .put("nativeAlignmentUsed", nativeAlignmentUsed)
        .put("alignmentVersion", if (nativeAlignmentUsed) "native_subpixel_v1" else "kotlin_integer_v1")
        .put("fallbackAlignmentCount", fallbackAlignmentCount)
        .put("lowConfidenceAlignmentCount", lowConfidenceAlignmentCount)
        .put("outputWidth", sensor.width)
        .put("outputHeight", sensor.height)
        .put("alignments", alignments)
}

private fun writeRawFusionDebugPreviews(
    jobDir: File,
    reference: ClassicRawFrame,
    mergedRawFile: File,
    sensor: RawFusionSensorData,
    blackLevelEstimate: BlackLevelEstimate,
    job: JSONObject,
    cancellation: KeplerPipelineCancellation
) {
    var refBitmap: Bitmap? = null
    var fusedBitmap: Bitmap? = null
    var referenceBitmap: Bitmap? = null
    var compare: Bitmap? = null
    var primaryFailure: Throwable? = null
    // Phase 7: heavy diagnostic images (reference preview + comparison sheet)
    // require explicit debug/diagnostic intent.  The bounded fused preview is a
    // reprocess candidate and stays required production output.
    val diagnosticImagesEnabled = DebugArtifactPolicy.imageArtifactsEnabled(job)
    try {
        cancellation.throwIfCancelled()
        val refProxy = requireNotNull(reference.proxy)
        refBitmap = rawProxyToBitmap(refProxy)
        if (diagnosticImagesEnabled) {
            saveClassicRawPng(refBitmap!!, File(jobDir, "raw_reference_preview.png"))
        }
        val mergedProxy = buildRawProxy(
            mergedRawFile,
            sensor,
            BlackLevelEstimate(0, "merged_raw_zero"),
            cancellation
        )
        cancellation.throwIfCancelled()
        fusedBitmap = rawProxyToBitmap(mergedProxy)
        saveClassicRawPng(fusedBitmap!!, File(jobDir, "raw_fused_classic_v1_preview.png"))
        if (diagnosticImagesEnabled) {
            cancellation.throwIfCancelled()
            compare = Bitmap.createBitmap(fusedBitmap.width * 2, fusedBitmap.height, Bitmap.Config.ARGB_8888)
            referenceBitmap = rawProxyToBitmap(refProxy)
            val canvas = android.graphics.Canvas(compare!!)
            canvas.drawBitmap(referenceBitmap!!, 0f, 0f, null)
            canvas.drawBitmap(fusedBitmap!!, fusedBitmap!!.width.toFloat(), 0f, null)
            saveClassicRawPng(compare!!, File(jobDir, "raw_compare_reference_vs_fused.png"))
        }
        cancellation.throwIfCancelled()
        job.put("rawFusedPreviewFile", "raw_fused_classic_v1_preview.png")
        if (diagnosticImagesEnabled) {
            job.put("rawReferencePreviewFile", "raw_reference_preview.png")
                .put("rawComparePreviewFile", "raw_compare_reference_vs_fused.png")
                .put("rawDebugArtifactStatus", "COMPLETE")
        } else {
            job.put("rawDebugArtifactStatus", DebugArtifactPolicy.STATUS_DISABLED)
                .put("debugArtifactImagesEnabled", false)
        }
        job.remove("rawDebugArtifactError")
    } catch (ce: CancellationException) {
        primaryFailure = ce
        throw ce
    } catch (fatal: Error) {
        primaryFailure = fatal
        throw fatal
    } catch (error: Exception) {
        primaryFailure = error
        job.put("rawDebugArtifactStatus", "FAILED")
            .put("rawDebugArtifactError", "${error.javaClass.simpleName}: ${error.message}".take(240))
    } finally {
        var cleanupFailure: Throwable? = null
        listOfNotNull(refBitmap, fusedBitmap, referenceBitmap, compare).forEach { bitmap ->
            try {
                bitmap.takeUnless { it.isRecycled }?.recycle()
            } catch (failure: Throwable) {
                cleanupFailure = combineSettlementFailure(cleanupFailure, failure)
            }
        }
        val combined = combineSettlementFailure(primaryFailure, cleanupFailure)
        if (combined !== primaryFailure) throw requireNotNull(combined)
    }
}

private fun rawProxyToBitmap(proxy: RawProxy): Bitmap {
    val pixels = IntArray(proxy.width * proxy.height) { index ->
        val v = proxy.luma[index].toInt() and 0xFF
        Color.rgb(v, v, v)
    }
    return Bitmap.createBitmap(pixels, proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
}

private fun saveClassicRawPng(
    bitmap: Bitmap,
    file: File,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
) {
    commitProcessingArtifact(
        finalFile = file,
        cancellation = cancellation,
        writeTemp = { temp ->
            FileOutputStream(temp).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not save ${file.name}"
                }
                output.fd.sync()
            }
        },
        verifyFinal = { committed ->
            verifyPngArtifact(committed)
        }
    )
}

private fun exposureProduct(meta: JSONObject): Float {
    val exposure = meta.optDouble("exposureTimeNs", Double.NaN)
    val iso = meta.optDouble("sensitivityIso", Double.NaN)
    if (!exposure.isFinite() || exposure <= 0.0 || !iso.isFinite() || iso <= 0.0) {
        return Float.NaN
    }
    val product = exposure * iso
    return if (product.isFinite() && product > 0.0 && product <= Float.MAX_VALUE) {
        product.toFloat()
    } else {
        Float.NaN
    }
}

private fun validateRawFrame(input: RawFrameInput, sensor: RawFusionSensorData): String? {
    val expectedBytes = sensor.pixelCount.toLong() * 2L
    if (sensor.width <= 0 || sensor.height <= 0 || sensor.pixelCount <= 0) {
        return "INVALID_RAW_DIMENSIONS"
    }
    if (input.file.length() != expectedBytes) return "INVALID_RAW_FILE_SIZE"
    val metaWidth = input.meta.optInt("rawWidth", sensor.width)
    val metaHeight = input.meta.optInt("rawHeight", sensor.height)
    if (metaWidth != sensor.width || metaHeight != sensor.height) return "RAW_DIMENSION_MISMATCH"
    val exposure = exposureProduct(input.meta)
    if (!exposure.isFinite() || exposure <= 0f) return "INVALID_EXPOSURE_PRODUCT"
    val black = input.meta.optDouble("blackLevel", sensor.blackLevel.toDouble())
    val white = input.meta.optDouble("whiteLevel", sensor.whiteLevel.toDouble())
    if (!black.isFinite() || !white.isFinite() || black < 0.0 || white <= black || white > 65535.0) {
        return "INVALID_RAW_LEVELS"
    }
    return null
}

private fun greenAlignedRawX(cfa: Int, y: Int, proposedX: Int): Int {
    val greenWhenEvenParity = cfa == 1 || cfa == 2
    val isGreen = (((proposedX + y) and 1) == 0) == greenWhenEvenParity
    return if (isGreen) proposedX else proposedX + 1
}

private fun countRawExcludedFrames(job: JSONObject): Int {
    val frames = job.optJSONArray("frames") ?: return 0
    var count = 0
    repeat(frames.length()) { index ->
        val frame = frames.optJSONObject(index) ?: return@repeat
        if (!frame.optBoolean("enabled", true) || frame.optBoolean("excludedByUser", false)) count++
    }
    return count
}
