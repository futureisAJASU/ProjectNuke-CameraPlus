package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private const val CLASSIC_FUSION_VERSION = "1.1"
private const val CLASSIC_FUSION_ALIGNMENT_MAX_DIMENSION = 512
private const val CLASSIC_FUSION_ALIGNMENT_SEARCH_RADIUS = 24
private const val CLASSIC_FUSION_TILE_ROWS = 256
private const val CLASSIC_FUSION_DEBUG_MAX_DIMENSION = 1024
private const val EXTERNAL_FRAME_WEIGHT_MIN = 0.15f
private const val EXTERNAL_FRAME_WEIGHT_MAX = 1.25f

private data class ClassicFrame(
    val jsonIndex: Int,
    val file: File,
    val qualityScore: Float?,
    val sharpnessScore: Float?,
    var thumbnail: LumaThumbnail? = null,
    var alignDx: Int = 0,
    var alignDy: Int = 0,
    var alignmentScore: Float = 0f,
    var alignmentConfidence: Float = 1f,
    var alignIntegerDx: Int = 0,
    var alignIntegerDy: Int = 0,
    var alignSubpixelDx: Float = 0f,
    var alignSubpixelDy: Float = 0f,
    var alignmentBackend: String = "kotlin_integer_v1",
    var alignmentUsedSubpixel: Boolean = false,
    var alignmentFallbackUsed: Boolean = false,
    var alignmentUsed: Boolean = true,
    var isReference: Boolean = false
)

private data class LumaThumbnail(
    val width: Int,
    val height: Int,
    val sampleSize: Int,
    val luma: ByteArray,
    val mean: Float
)

private data class AlignmentResult(
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

private data class MergeResult(
    val bitmap: Bitmap,
    val rejectedPixels: Long,
    val comparedPixels: Long,
    val memoryPlan: FusionMemoryPlan
)

private data class ClassicYuvProcessingPreflight(
    val totalFrames: Int,
    val enabledFrames: Int,
    val existingFrameFiles: Int,
    val missingFrameFiles: Int,
    val decodeProbePassed: Int,
    val decodeProbeFailed: Int
)

private data class ClassicYuvProcessingFailureCounts(
    val totalFrames: Int,
    val enabledFrames: Int,
    val decodedUsableFrames: Int,
    val sameSizeFrames: Int?,
    val compatibleFrames: Int?
)

internal fun processClassicYuvFusionJob(
    jobDir: File,
    requestedParams: ClassicYuvFusionParams? = null,
    externalFrameWeights: Map<Int, Float>? = null,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    metadataPolicy: ReprocessMetadataPolicy = ReprocessMetadataPolicy.NORMAL,
    operationLease: JobOperationLease? = null,
    onStatus: (String) -> Unit
): File {
    cancellation.throwIfCancelled()
    val processingStartedAt = System.currentTimeMillis()
    val jobFile = when (val resolved = NoFollowFileSystem.resolveDirectChildResult(
        jobDir, JOB_JSON_FILE_NAME, requireFile = true
    )) {
        is NoFollowInspection.Present -> resolved.value
        NoFollowInspection.Absent -> error("YUV job metadata is absent")
        is NoFollowInspection.InspectionFailed -> throw resolved.exception
    }
    val job = JSONObject(NoFollowFileSystem.readTextVerified(jobFile))
    val processingAttempt = beginProcessingAttempt(
        jobDir,
        mode = "CLASSIC_YUV",
        additionalOwnedKeys = setOf(
            "averageColorFile", "finalNightFusionFile", "finalFile", "galleryDisplayFile",
            "galleryThumbnailFile", "referenceFrameDebugFile", "yuvReferencePreviewFile",
            "fusedClassicDebugFile", "yuvFusedClassicDebugFile"
        ),
        operationLease = operationLease
    )
    job.put("processingAttemptId", processingAttempt.id)
    val params = (requestedParams ?: loadClassicYuvFusionParams(job)).clamped()
    initializeClassicYuvRunMetadata(job, params, processingStartedAt, metadataPolicy)
    resetClassicFrameMetadataForCurrentRun(jobDir, job)

    // Initialize external-weight metadata from current invocation before processing
    if (externalFrameWeights != null && externalFrameWeights.isNotEmpty()) {
        job.put("yuvExternalFrameWeightsUsed", true)
            .put("yuvExternalFrameWeightsTarget", "NON_REFERENCE_FRAMES_ONLY")
    } else {
        job.put("yuvExternalFrameWeightsUsed", false)
        job.remove("yuvExternalFrameWeightsTarget")
    }

    var merged: Bitmap? = null
    var finalBitmap: Bitmap? = null
    var preflight: ClassicYuvProcessingPreflight? = null
    var decodedUsableFrameCount = 0
    var sameSizeFrameCount = 0
    var compatibleFrameCount = 0
    var sameSizeFrameCountKnown = false
    var compatibleFrameCountKnown = false
    var frames: List<ClassicFrame> = emptyList()
    var totalFrames = 0
    var referenceIndex: Int? = null
    var activeReferenceIndex: Int? = null
    var dimensions: Pair<Int, Int>? = null
    var mergeResult: MergeResult? = null
    var rejectedRatio: Double? = null
    var nativeAlignmentUsed = false
    var fallbackAlignmentCount = 0
    var lowConfidenceAlignmentCount = 0
    var excludedFrameCount = 0
    var primaryFailure: Throwable? = null
    try {
        fun markStage(stage: String, status: String) {
            job.put("currentPipelineStage", stage)
                .put("processStatus", status)
                .put("processingStartedAt", processingStartedAt)
            updateProcessingStage(jobDir, stage, status, mutate = { current ->
                current.put("processingStartedAt", processingStartedAt)
                    .put("yuvProcessingPolicy", metadataPolicy.name)
            }, attempt = processingAttempt)
            Log.i("KeplerYuvPipeline", "$stage: $status")
            onStatus(status)
        }

        markStage("YUV_ALIGNING", "YUV 프레임을 정렬하는 중입니다.")
        cancellation.throwIfCancelled()
        val preflightSummary = buildClassicYuvProcessingPreflight(jobDir, job)
        cancellation.throwIfCancelled()
        preflight = preflightSummary
        job.put("yuvProcessingPreflight", preflightSummary.toJson())
            .put("frameCount", preflightSummary.totalFrames)
            .put("yuvProcessingTotalFrames", preflightSummary.totalFrames)
            .put("yuvProcessingEnabledFrames", preflightSummary.enabledFrames)
        updateForProcessingAttempt(jobDir, processingAttempt) { current ->
            current.put("yuvProcessingPreflight", preflightSummary.toJson())
                .put("processingStartedAt", job.optLong("processingStartedAt"))
                .put("yuvProcessingPolicy", metadataPolicy.name)
                .put("frameCount", preflightSummary.totalFrames)
                .put("yuvProcessingTotalFrames", preflightSummary.totalFrames)
                .put("yuvProcessingEnabledFrames", preflightSummary.enabledFrames)
        }
        cancellation.throwIfCancelled()
        val candidateFrames = loadClassicFrames(jobDir, job)
        cancellation.throwIfCancelled()
        totalFrames = preflightSummary.totalFrames
        frames = candidateFrames.mapNotNull { frame ->
            try {
                cancellation.throwIfCancelled()
                val frameJson = job.optJSONArray("frames")?.optJSONObject(frame.jsonIndex)
                frameJson?.let { resetClassicFrameAlignmentFields(it) }
                frame.thumbnail = decodeLumaThumbnail(frame.file)
                cancellation.throwIfCancelled()
                frame
            } catch (oom: OutOfMemoryError) {
                throw oom
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val frameJson = job.optJSONArray("frames")?.optJSONObject(frame.jsonIndex)
                frameJson?.let { clearClassicFrameAlignmentOnDecodeFailure(jobDir, it, frame.file.name, "${e.javaClass.simpleName}: ${e.message}") }
                null
            }
        }
        decodedUsableFrameCount = frames.size
        job.put("yuvProcessingDecodedUsableFrames", decodedUsableFrameCount)
        updateForProcessingAttempt(jobDir, processingAttempt) { current ->
            current.put("yuvProcessingDecodedUsableFrames", decodedUsableFrameCount)
        }
        if (frames.isEmpty()) {
            error(
                "Not enough enabled YUV frames to reprocess: " +
                    "enabled=${preflight.enabledFrames}, total=${preflight.totalFrames}, usable=${frames.size}"
            )
        }
        val reference = selectClassicReference(frames)
        referenceIndex = reference.jsonIndex
        onStatus("Classic YUV fusion: selected reference frame ${reference.jsonIndex + 1}")

        val referenceThumbnail = requireNotNull(reference.thumbnail)
        frames.forEachIndexed { index, frame ->
            cancellation.throwIfCancelled()
            onStatus("YUV 프레임을 정렬하는 중입니다.")
            if (frame === reference) {
                frame.alignmentScore = 0f
                frame.alignmentUsed = true
            } else {
                val alignment = estimateTranslation(referenceThumbnail, requireNotNull(frame.thumbnail))
                val fullScaleX = frame.thumbnail!!.sampleSize.toFloat()
                val fullScaleY = frame.thumbnail!!.sampleSize.toFloat()
                frame.alignDx = (alignment.dx * fullScaleX).roundToInt()
                frame.alignDy = (alignment.dy * fullScaleY).roundToInt()
                frame.alignIntegerDx = (alignment.integerDx * fullScaleX).roundToInt()
                frame.alignIntegerDy = (alignment.integerDy * fullScaleY).roundToInt()
                frame.alignSubpixelDx = alignment.subpixelDx * fullScaleX
                frame.alignSubpixelDy = alignment.subpixelDy * fullScaleY
                frame.alignmentScore = alignment.score
                frame.alignmentConfidence = alignment.confidence
                frame.alignmentBackend = alignment.backend
                frame.alignmentUsedSubpixel = alignment.usedSubpixel
                frame.alignmentFallbackUsed = alignment.fallbackUsed
                if (alignment.backend == "native_subpixel_v1") nativeAlignmentUsed = true
                if (alignment.fallbackUsed) fallbackAlignmentCount++
                if (alignment.confidence < 0.35f) lowConfidenceAlignmentCount++
                frame.alignmentUsed =
                    alignment.dx.isFinite() && alignment.dy.isFinite() &&
                        alignment.integerDx.toFloat().isFinite() &&
                        alignment.integerDy.toFloat().isFinite() &&
                        alignment.subpixelDx.isFinite() && alignment.subpixelDy.isFinite() &&
                        alignment.score.isFinite() && alignment.confidence.isFinite() &&
                        alignment.score <= params.alignmentRejectThreshold
            }
            updateAlignmentMetadata(job, frame, params)
        }

        dimensions = decodeImageDimensions(reference.file)
        val sameSizeFrames = frames.filter { decodeImageDimensions(it.file) == dimensions!! }
        sameSizeFrameCount = sameSizeFrames.size
        sameSizeFrameCountKnown = true
        job.put("yuvProcessingSameSizeFrames", sameSizeFrameCount)
        updateForProcessingAttempt(jobDir, processingAttempt) { current ->
            current.put("yuvProcessingSameSizeFrames", sameSizeFrameCount)
        }
        val acceptedFrames = sameSizeFrames.filter { it === reference || it.alignmentUsed }
        val compatibleFrames: List<ClassicFrame>
        val singleReferenceFallback: Boolean
        if (acceptedFrames.size >= 2) {
            compatibleFrames = acceptedFrames
            singleReferenceFallback = false
        } else {
            // Single-reference fallback: only the reference is usable
            compatibleFrames = listOf(reference)
            singleReferenceFallback = true
        }
        compatibleFrameCount = compatibleFrames.size
        sameSizeFrameCountKnown = true
        compatibleFrameCountKnown = true
        job.put("yuvProcessingCompatibleFrames", compatibleFrames.size)
        job.put("yuvSingleReferenceFallback", singleReferenceFallback)
        updateForProcessingAttempt(jobDir, processingAttempt) { current ->
            current.put("yuvProcessingCompatibleFrames", compatibleFrames.size)
            current.put("yuvSingleReferenceFallback", singleReferenceFallback)
        }
        if (compatibleFrames.size < 2 && !singleReferenceFallback) {
            error(
                "Not enough same-size YUV frames to fuse: " +
                    "compatible=${compatibleFrames.size}, sameSize=${sameSizeFrames.size}, decoded=${frames.size}"
            )
        }
        val activeReference = compatibleFrames.find { it === reference }!!
        activeReferenceIndex = activeReference.jsonIndex
        activeReference.isReference = true
        compatibleFrames.forEach { frame ->
            updateAlignmentMetadata(job, frame, params, used = true, skipReason = null)
        }
        frames.filterNot { it in compatibleFrames }.forEach { frame ->
            updateAlignmentMetadata(
                job,
                frame,
                params,
                used = false,
                skipReason = if (frame !in sameSizeFrames) "DIMENSION_MISMATCH" else "LOW_ALIGNMENT_CONFIDENCE"
            )
        }

        val alignDoneAt = System.currentTimeMillis()
        markStage("YUV_MERGING", "YUV 프레임을 합성하는 중입니다.")
        cancellation.throwIfCancelled()
        mergeResult = mergeClassicFrames(
            frames = compatibleFrames,
            reference = activeReference,
            width = dimensions!!.first,
            height = dimensions!!.second,
            params = params,
            externalFrameWeights = externalFrameWeights,
            cancellation = cancellation,
            onStatus = onStatus
        )
        merged = mergeResult.bitmap
        cancellation.throwIfCancelled()
        val mergeDoneAt = System.currentTimeMillis()
        val averageFile = File(jobDir, "average_color_rotated.png")
        cancellation.throwIfCancelled()
        saveClassicBitmap(merged, averageFile, cancellation)

        markStage("YUV_DENOISE_SHARPEN", "노이즈와 선명도를 보정하는 중입니다.")
        cancellation.throwIfCancelled()
        finalBitmap = applyClassicYuvPostProcessing(merged, params, cancellation)
        cancellation.throwIfCancelled()
        val lookDoneAt = System.currentTimeMillis()
        markStage("YUV_EXPORTING", "결과를 저장하는 중입니다.")
        val finalFile = File(jobDir, "sharpened_night_fusion.png")
        cancellation.throwIfCancelled()
        saveClassicBitmap(finalBitmap, finalFile, cancellation, processingAttempt)
        markProcessingArtifactClaim(jobDir, processingAttempt, "finalFile", finalFile)
        val postCommitCancellation = cancellation.isCancelled
        if (postCommitCancellation) {
            markProcessingPostCommitCancellation(jobDir, processingAttempt)
        }
        val exportDoneAt = System.currentTimeMillis()
        val processingTimeMs = System.currentTimeMillis() - processingStartedAt
        excludedFrameCount = countExcludedFrames(job)
        val skippedFrameCount =
            (totalFrames - excludedFrameCount - compatibleFrames.size).coerceAtLeast(0)

        rejectedRatio = if (mergeResult!!.comparedPixels > 0L) {
            mergeResult!!.rejectedPixels.toDouble() / mergeResult!!.comparedPixels
        } else {
            0.0
        }
        job.put("jobType", "YUV_NIGHT_FUSION")
            .put("currentPipelineStage", "PIPELINE_COMPLETE")
            .put("userCanMoveDevice", true)
            .put("processingStartedAt", processingStartedAt)
            .put("processStatus", "PIPELINE_COMPLETE")
            .put("fusionEngine", "yuv_night_fusion_v0")
            .put("fusionVersion", CLASSIC_FUSION_VERSION)
            .put("yuvFusionVersion", "YUV_NIGHT_FUSION_V0")
            .put("fusionParamsVersion", CLASSIC_YUV_FUSION_PARAMS_VERSION)
            .put("fusionPresetName", params.presetName)
            .put("fusionParams", params.toJson())
            .put("nativeAlignmentAvailable", NativeFusionAlignment.isAvailable())
            .put("nativeAlignmentUsed", nativeAlignmentUsed)
            .put("alignmentVersion", if (nativeAlignmentUsed) "native_subpixel_v1" else "kotlin_integer_v1")
            .put("yuvAlignVersion", "YUV_GLOBAL_SHIFT_V0")
            .put("yuvMergeVersion", "YUV_TEMPORAL_GHOST_V0")
            .put("yuvDenoiseVersion", "YUV_LUMA_CHROMA_EDGE_AWARE_V0")
            .put("yuvDetailVersion", "YUV_LUMA_DETAIL_V0")
            .put("yuvSharpenVersion", "YUV_ADAPTIVE_LUMA_SHARPEN_V0")
            .put("yuvLookVersion", "YUV_NATURAL_NIGHT_LOOK_V0")
            .put("fallbackAlignmentCount", fallbackAlignmentCount)
            .put("lowConfidenceAlignmentCount", lowConfidenceAlignmentCount)
            .put("usedFrameCount", compatibleFrames.size)
            .put("acceptedFrameCount", compatibleFrames.size)
            .put("rejectedFrameCount", (sameSizeFrames.size - compatibleFrames.size).coerceAtLeast(0) + skippedFrameCount)
            .put("excludedFrameCount", excludedFrameCount)
            .put("skippedFrameCount", skippedFrameCount)
            .put("referenceFrameIndex", activeReferenceIndex!!)
            .put("yuvReferenceFrameIndex", activeReferenceIndex!!)
            .put("ghostSuppressionUsed", true)
            .put("ghostSuppressionEnabled", true)
            .put("ghostRejectedPixelRatio", rejectedRatio!!)
            .put("rejectedGhostSampleRatio", rejectedRatio!!)
            .put("memoryPlanTileRows", mergeResult!!.memoryPlan.tileRows)
            .put("memoryPlanCandidateBatchSize", mergeResult!!.memoryPlan.candidateBatchSize)
            .put("memoryPlanEstimatedPeakBytes", mergeResult!!.memoryPlan.estimatedPeakBytes)
            .put("memoryPlanFallbackReason", mergeResult!!.memoryPlan.fallbackReason ?: JSONObject.NULL)
            .put("averageColorFile", averageFile.name)
            .put("finalNightFusionFile", finalFile.name)
            .put("finalFile", finalFile.name)
            .put("finalOutputSource", "yuv_fusion_rgba")
            .put("galleryDisplayFile", finalFile.name)
            .put("galleryThumbnailFile", finalFile.name)
            .put("galleryDisplaySource", "yuv_final_file")
            .put("isDebugPreviewUsedAsFinal", false)
            .put("yuvFusionLooksWorseHint", JSONObject.NULL)
            .put("yuvQualityDiagnosticHints", JSONArray(listOf(
                "alignment blur",
                "over-denoise",
                "over-sharpen",
                "chroma plane shift",
                "wrong UV order",
                "output resize issue",
                "wrong 3x route"
            )))
            .put("processingTimeMs", processingTimeMs)
            .put("outputWidth", dimensions!!.first)
            .put("outputHeight", dimensions!!.second)
            .put("frameCount", totalFrames)
            .put("yuvWidth", dimensions!!.first)
            .put("yuvHeight", dimensions!!.second)
            .put("lumaDenoiseStrength", params.denoiseStrength.toDouble())
            .put("chromaDenoiseStrength", params.denoiseStrength.toDouble())
            .put("lowLightChromaBoost", true)
            .put("adaptiveSharpenUsed", true)
            .put("blackPoint", 0.018)
            .put("contrastCurve", "mild_s_curve")
            .put("saturationBoost", params.saturationBoost.toDouble())
            .put("vibranceBoost", 0.04)
            .put("localContrastAmount", params.localContrastAmount.toDouble())
            .put(
                "timing",
                (job.optJSONObject("timing") ?: JSONObject())
                    .put("yuvAlignMs", alignDoneAt - processingStartedAt)
                    .put("yuvMergeMs", mergeDoneAt - alignDoneAt)
                    .put("yuvDenoiseMs", lookDoneAt - mergeDoneAt)
                    .put("yuvLookMs", lookDoneAt - mergeDoneAt)
                    .put("yuvExportMs", exportDoneAt - lookDoneAt)
                    .put("totalPipelineMs", exportDoneAt - job.optLong("createdAt", processingStartedAt))
            )
            .put("processedAt", System.currentTimeMillis())
            .put(
                "processingNotes",
                "Classic YUV Fusion v1: integer translation alignment, robust local weights, " +
                    "ghost suppression, mild chroma denoise, tone, and sharpen."
            )
    // Set or remove external-weight metadata based on the current run
    if (externalFrameWeights != null && externalFrameWeights.isNotEmpty()) {
        job.put("yuvExternalFrameWeightsUsed", true)
        .put("yuvExternalFrameWeightsTarget", "NON_REFERENCE_FRAMES_ONLY")
    } else {
        job.put("yuvExternalFrameWeightsUsed", false)
        job.remove("yuvExternalFrameWeightsTarget")
    }
        val debugMetadataFailure = try {
            if (!postCommitCancellation) cancellation.throwIfCancelled()
            writeFusionDebugMetadata(
                jobDir = jobDir,
                job = job,
                frames = compatibleFrames,
                totalFrameCount = totalFrames,
                ghostRejectedPixelRatio = rejectedRatio,
                processingTimeMs = processingTimeMs,
                outputWidth = dimensions.first,
                outputHeight = dimensions.second,
                params = params,
                nativeAlignmentUsed = nativeAlignmentUsed,
                fallbackAlignmentCount = fallbackAlignmentCount,
                lowConfidenceAlignmentCount = lowConfidenceAlignmentCount
            )
            null
        } catch (ce: CancellationException) {
            throw ce
        } catch (fatal: Error) {
            throw fatal
        } catch (failure: Exception) {
            failure
        }
        if (!postCommitCancellation) {
            generateFusionDebugArtifacts(
                jobDir = jobDir,
                job = job,
                referenceFile = activeReference.file,
                mergedBitmap = merged,
                fusedBitmap = finalBitmap,
                params = params
            )
        } else {
            job.put("debugArtifactStatus", "SKIPPED_CANCELLED")
                .put("debugArtifactError", "Optional debug artifacts skipped after required output adoption")
        }
        if (debugMetadataFailure != null) {
            job.put("debugArtifactStatus", "FAILED")
                .put(
                    "debugArtifactError",
                    "${debugMetadataFailure.javaClass.simpleName}: ${debugMetadataFailure.message}".take(240)
                )
        }
        if (!postCommitCancellation) {
            cancellation.throwIfCancelled()
            writeVerifiedJsonArtifact(
                File(jobDir, "yuv_debug.json"),
                job.toString(2),
                processingArtifactSettlementObserver(jobDir, processingAttempt)
            )
        }
        persistClassicYuvSuccess(
            jobDir = jobDir,
            job = job,
            metadataPolicy = metadataPolicy,
            attempt = processingAttempt
        )
        if (postCommitCancellation) {
            markProcessingPostCommitCancellation(jobDir, processingAttempt)
        } else {
            cancellation.throwIfCancelled()
        }
        onStatus("처리가 완료되었습니다.")
        return finalFile
} catch (oom: OutOfMemoryError) {
        primaryFailure = oom
        try {
        val failurePreflight = preflight ?: buildClassicYuvProcessingPreflight(jobDir, job)
        val excludedFrameCount = countExcludedFrames(job)
        val usedFrameCount = if (compatibleFrameCountKnown) compatibleFrameCount else null
        val acceptedFrameCount = if (compatibleFrameCountKnown) compatibleFrameCount else null
        val rejectedFrameCount = if (sameSizeFrameCountKnown && compatibleFrameCountKnown) {
            (sameSizeFrameCount - compatibleFrameCount).coerceAtLeast(0)
        } else null
        val skippedFrameCount = if (compatibleFrameCountKnown) {
            (failurePreflight.enabledFrames - compatibleFrameCount).coerceAtLeast(0)
        } else null
        val ghostRejectedPixelRatio = if (mergeResult != null) {
            if (mergeResult.comparedPixels > 0L) {
                mergeResult.rejectedPixels.toDouble() / mergeResult.comparedPixels
            } else {
                0.0
            }
        } else null
        recordClassicFailure(
            jobFile = jobFile,
            job = job,
            status = "OOM_FAILED_KEEPING_CACHE",
            reason = "OutOfMemoryError",
            throwable = oom,
            failureCounts = resolveClassicFailureCounts(
                preflight = failurePreflight,
                decodedUsableFrameCount = decodedUsableFrameCount,
                sameSizeFrameCount = sameSizeFrameCount,
                compatibleFrameCount = compatibleFrameCount,
                sameSizeFrameCountKnown = sameSizeFrameCountKnown,
                compatibleFrameCountKnown = compatibleFrameCountKnown
            ),
            preflight = failurePreflight,
            params = params,
            processingStartedAt = processingStartedAt,
            metadataPolicy = metadataPolicy,
            nativeAlignmentUsed = nativeAlignmentUsed,
            fallbackAlignmentCount = fallbackAlignmentCount,
            lowConfidenceAlignmentCount = lowConfidenceAlignmentCount,
            externalFrameWeights = externalFrameWeights,
            referenceFrameIndex = activeReferenceIndex ?: referenceIndex,
            usedFrameCount = usedFrameCount,
            acceptedFrameCount = acceptedFrameCount,
            rejectedFrameCount = rejectedFrameCount,
            excludedFrameCount = excludedFrameCount,
            skippedFrameCount = skippedFrameCount,
            ghostRejectedPixelRatio = ghostRejectedPixelRatio,
            outputWidth = dimensions?.first,
            outputHeight = dimensions?.second,
            yuvWidth = dimensions?.first,
            yuvHeight = dimensions?.second,
            attempt = processingAttempt
        )
        } catch (secondary: Throwable) {
            throw requireNotNull(combineSettlementFailure(oom, secondary))
        }
        throw oom
} catch (ce: CancellationException) {
        primaryFailure = ce
        try {
        val failurePreflight = preflight ?: buildClassicYuvProcessingPreflight(jobDir, job)
        recordClassicFailure(
            jobFile = jobFile,
            job = job,
            status = "PIPELINE_CANCELLED",
            reason = ce.message?.takeIf { it.isNotBlank() } ?: "Classic YUV fusion cancelled",
            throwable = ce,
            failureCounts = resolveClassicFailureCounts(
                preflight = failurePreflight,
                decodedUsableFrameCount = decodedUsableFrameCount,
                sameSizeFrameCount = sameSizeFrameCount,
                compatibleFrameCount = compatibleFrameCount,
                sameSizeFrameCountKnown = sameSizeFrameCountKnown,
                compatibleFrameCountKnown = compatibleFrameCountKnown
            ),
            preflight = failurePreflight,
            params = params,
            processingStartedAt = processingStartedAt,
            metadataPolicy = metadataPolicy,
            nativeAlignmentUsed = nativeAlignmentUsed,
            fallbackAlignmentCount = fallbackAlignmentCount,
            lowConfidenceAlignmentCount = lowConfidenceAlignmentCount,
            externalFrameWeights = externalFrameWeights,
            referenceFrameIndex = activeReferenceIndex ?: referenceIndex,
            usedFrameCount = if (compatibleFrameCountKnown) compatibleFrameCount else null,
            acceptedFrameCount = if (compatibleFrameCountKnown) compatibleFrameCount else null,
            rejectedFrameCount = if (sameSizeFrameCountKnown && compatibleFrameCountKnown) {
                (sameSizeFrameCount - compatibleFrameCount).coerceAtLeast(0)
            } else null,
            excludedFrameCount = countExcludedFrames(job),
            skippedFrameCount = if (compatibleFrameCountKnown) {
                (failurePreflight.enabledFrames - compatibleFrameCount).coerceAtLeast(0)
            } else null,
            ghostRejectedPixelRatio = mergeResult?.let {
                if (it.comparedPixels > 0L) it.rejectedPixels.toDouble() / it.comparedPixels else 0.0
            },
            outputWidth = dimensions?.first,
            outputHeight = dimensions?.second,
            yuvWidth = dimensions?.first,
            yuvHeight = dimensions?.second,
            attempt = processingAttempt
        )
        } catch (secondary: Throwable) {
            throw requireNotNull(combineSettlementFailure(ce, secondary))
        }
        throw ce
} catch (fatal: Error) {
        primaryFailure = fatal
        try {
        val failurePreflight = preflight ?: buildClassicYuvProcessingPreflight(jobDir, job)
        recordClassicFailure(
            jobFile = jobFile,
            job = job,
            status = "FATAL_FAILED_KEEPING_CACHE",
            reason = fatal.message?.takeIf { it.isNotBlank() } ?: fatal.javaClass.simpleName,
            throwable = fatal,
            failureCounts = resolveClassicFailureCounts(
                preflight = failurePreflight,
                decodedUsableFrameCount = decodedUsableFrameCount,
                sameSizeFrameCount = sameSizeFrameCount,
                compatibleFrameCount = compatibleFrameCount,
                sameSizeFrameCountKnown = sameSizeFrameCountKnown,
                compatibleFrameCountKnown = compatibleFrameCountKnown
            ),
            preflight = failurePreflight,
            params = params,
            processingStartedAt = processingStartedAt,
            metadataPolicy = metadataPolicy,
            nativeAlignmentUsed = nativeAlignmentUsed,
            fallbackAlignmentCount = fallbackAlignmentCount,
            lowConfidenceAlignmentCount = lowConfidenceAlignmentCount,
            externalFrameWeights = externalFrameWeights,
            referenceFrameIndex = activeReferenceIndex ?: referenceIndex,
            attempt = processingAttempt
        )
        } catch (secondary: Throwable) {
            throw requireNotNull(combineSettlementFailure(fatal, secondary))
        }
        throw fatal
} catch (e: Exception) {
        primaryFailure = e
        try {
        val failurePreflight = preflight ?: buildClassicYuvProcessingPreflight(jobDir, job)
        val excludedFrameCount = countExcludedFrames(job)
        val usedFrameCount = if (compatibleFrameCountKnown) compatibleFrameCount else null
        val acceptedFrameCount = if (compatibleFrameCountKnown) compatibleFrameCount else null
        val rejectedFrameCount = if (sameSizeFrameCountKnown && compatibleFrameCountKnown) {
            (sameSizeFrameCount - compatibleFrameCount).coerceAtLeast(0)
        } else null
        val skippedFrameCount = if (compatibleFrameCountKnown) {
            (failurePreflight.enabledFrames - compatibleFrameCount).coerceAtLeast(0)
        } else null
        val ghostRejectedPixelRatio = if (mergeResult != null) {
            if (mergeResult.comparedPixels > 0L) {
                mergeResult.rejectedPixels.toDouble() / mergeResult.comparedPixels
            } else {
                0.0
            }
        } else null
        recordClassicFailure(
            jobFile,
            job,
            "CLASSIC_YUV_FUSION_V1_FAILED_KEEPING_CACHE",
            e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName,
            e,
            resolveClassicFailureCounts(
                preflight = failurePreflight,
                decodedUsableFrameCount = decodedUsableFrameCount,
                sameSizeFrameCount = sameSizeFrameCount,
                compatibleFrameCount = compatibleFrameCount,
                sameSizeFrameCountKnown = sameSizeFrameCountKnown,
                compatibleFrameCountKnown = compatibleFrameCountKnown
            ),
            preflight = failurePreflight,
            params = params,
            processingStartedAt = processingStartedAt,
            metadataPolicy = metadataPolicy,
            nativeAlignmentUsed = nativeAlignmentUsed,
            fallbackAlignmentCount = fallbackAlignmentCount,
            lowConfidenceAlignmentCount = lowConfidenceAlignmentCount,
            externalFrameWeights = externalFrameWeights,
            referenceFrameIndex = activeReferenceIndex ?: referenceIndex,
            usedFrameCount = usedFrameCount,
            acceptedFrameCount = acceptedFrameCount,
            rejectedFrameCount = rejectedFrameCount,
            excludedFrameCount = excludedFrameCount,
            skippedFrameCount = skippedFrameCount,
            ghostRejectedPixelRatio = ghostRejectedPixelRatio,
            outputWidth = dimensions?.first,
            outputHeight = dimensions?.second,
            yuvWidth = dimensions?.first,
            yuvHeight = dimensions?.second,
            attempt = processingAttempt
        )
        } catch (secondary: Throwable) {
            throw requireNotNull(combineSettlementFailure(e, secondary))
        }
        throw e
    } finally {
        var cleanupFailure: Throwable? = null
        try {
            finalBitmap?.recycle()
        } catch (failure: Throwable) {
            cleanupFailure = combineSettlementFailure(cleanupFailure, failure)
        }
        try {
            merged?.recycle()
        } catch (failure: Throwable) {
            cleanupFailure = combineSettlementFailure(cleanupFailure, failure)
        }
        try {
            processingAttempt.releaseOwnedLease()
        } catch (failure: Throwable) {
            cleanupFailure = combineSettlementFailure(cleanupFailure, failure)
        }
        val combined = combineSettlementFailure(primaryFailure, cleanupFailure)
        if (combined !== primaryFailure) throw requireNotNull(combined)
    }
}

private fun loadClassicFrames(jobDir: File, job: JSONObject): List<ClassicFrame> {
    val array = job.optJSONArray("frames") ?: return emptyList()
    return buildList {
        repeat(array.length()) { index ->
            val frame = array.optJSONObject(index) ?: return@repeat
            if (!frame.optBoolean("enabled", true) || frame.optBoolean("excludedByUser", false)) {
                return@repeat
            }
            val fileName = frame.optString("file")
            val file = when (val resolved = NoFollowFileSystem.resolveDirectChildResult(
                jobDir, fileName, requireFile = true
            )) {
                is NoFollowInspection.Present -> resolved.value
                else -> return@repeat
            }
            add(
                ClassicFrame(
                    jsonIndex = index,
                    file = file,
                    qualityScore = frame.optionalFloat("qualityScore"),
                    sharpnessScore = frame.optionalFloat("sharpnessScore")
                )
            )
        }
    }
}

private fun selectClassicReference(frames: List<ClassicFrame>): ClassicFrame {
    val scored = frames.filter { it.qualityScore != null || it.sharpnessScore != null }
    return scored.maxWithOrNull(
        compareBy<ClassicFrame> { it.qualityScore ?: -1f }
            .thenBy { it.sharpnessScore ?: -1f }
    ) ?: frames[frames.size / 2]
}

private fun decodeLumaThumbnail(file: File): LumaThumbnail {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    NoFollowFileSystem.decodeBitmapVerified(file, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unreadable frame: ${file.name}" }
    var sampleSize = 1
    while (
        max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) >
        CLASSIC_FUSION_ALIGNMENT_MAX_DIMENSION
    ) {
        sampleSize *= 2
    }
    val bitmap = NoFollowFileSystem.decodeBitmapVerified(
        file,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
    ) ?: error("Could not decode frame: ${file.name}")
    var primaryFailure: Throwable? = null
    return try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        val luma = ByteArray(pixels.size)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var sum = 0L
        pixels.forEachIndexed { index, color ->
            val value = luma(color)
            luma[index] = value.toByte()
            sum += value
        }
        LumaThumbnail(
            width = bitmap.width,
            height = bitmap.height,
            sampleSize = sampleSize,
            luma = luma,
            mean = sum.toFloat() / pixels.size.coerceAtLeast(1)
        )
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        var cleanupFailure: Throwable? = null
        try {
            bitmap.recycle()
        } catch (failure: Throwable) {
            cleanupFailure = failure
        }
        val combined = combineSettlementFailure(primaryFailure, cleanupFailure)
        if (combined !== primaryFailure) throw requireNotNull(combined)
    }
}

private fun estimateTranslation(
    reference: LumaThumbnail,
    candidate: LumaThumbnail
): AlignmentResult {
    require(reference.width == candidate.width && reference.height == candidate.height) {
        "Alignment thumbnail dimensions differ"
    }
    var bestDx = 0
    var bestDy = 0
    var bestScore = Float.MAX_VALUE
    for (dy in -CLASSIC_FUSION_ALIGNMENT_SEARCH_RADIUS..CLASSIC_FUSION_ALIGNMENT_SEARCH_RADIUS step 4) {
        for (dx in -CLASSIC_FUSION_ALIGNMENT_SEARCH_RADIUS..CLASSIC_FUSION_ALIGNMENT_SEARCH_RADIUS step 4) {
            val score = alignmentMad(reference, candidate, dx, dy, 4)
            if (score < bestScore) {
                bestScore = score
                bestDx = dx
                bestDy = dy
            }
        }
    }
    val refineMinX = max(-CLASSIC_FUSION_ALIGNMENT_SEARCH_RADIUS, bestDx - 3)
    val refineMaxX = min(CLASSIC_FUSION_ALIGNMENT_SEARCH_RADIUS, bestDx + 3)
    val refineMinY = max(-CLASSIC_FUSION_ALIGNMENT_SEARCH_RADIUS, bestDy - 3)
    val refineMaxY = min(CLASSIC_FUSION_ALIGNMENT_SEARCH_RADIUS, bestDy + 3)
    for (dy in refineMinY..refineMaxY) {
        for (dx in refineMinX..refineMaxX) {
            val score = alignmentMad(reference, candidate, dx, dy, 3)
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
            searchRadius = CLASSIC_FUSION_ALIGNMENT_SEARCH_RADIUS
        )
    } else {
        null
    }
    if (native != null && native.confidence >= 0.35f) {
        return AlignmentResult(
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
    return AlignmentResult(
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

private fun alignmentMad(
    reference: LumaThumbnail,
    candidate: LumaThumbnail,
    dx: Int,
    dy: Int,
    step: Int
): Float {
    val margin = CLASSIC_FUSION_ALIGNMENT_SEARCH_RADIUS + 8
    val left = margin
    val top = margin
    val right = reference.width - margin
    val bottom = reference.height - margin
    if (right <= left || bottom <= top) return Float.MAX_VALUE
    var difference = 0L
    var count = 0
    var y = top
    while (y < bottom) {
        var x = left
        while (x < right) {
            val ref = reference.luma[y * reference.width + x].toInt() and 0xFF
            val other = candidate.luma[(y + dy) * candidate.width + x + dx].toInt() and 0xFF
            difference += abs(ref - other)
            count++
            x += step
        }
        y += step
    }
    return difference.toFloat() / count.coerceAtLeast(1) / 255f
}

@Suppress("DEPRECATION")
private fun mergeClassicFrames(
    frames: List<ClassicFrame>,
    reference: ClassicFrame,
    width: Int,
    height: Int,
    params: ClassicYuvFusionParams,
    externalFrameWeights: Map<Int, Float>? = null,
    cancellation: KeplerPipelineCancellation,
    onStatus: (String) -> Unit
): MergeResult {
    var output: Bitmap? = null
    var outputReturned = false
    val decoders = linkedMapOf<ClassicFrame, BitmapRegionDecoder>()
    var rejectedPixels = 0L
    var comparedPixels = 0L
    val reportedMergeFrames = mutableSetOf<Int>()
    var primaryFailure: Throwable? = null
    try {
        val memoryPlan = planFusionMemory(
            FusionMemoryPlanRequest(
                width = width,
                tileRows = CLASSIC_FUSION_TILE_ROWS,
                candidateFrames = frames.size,
                availableBytes = currentAvailableJavaHeapBytes(),
                fullOutputBitmapBytes = checkedBitmapBytes(width, height),
                postprocessOutputBitmapBytes = checkedBitmapBytes(width, height)
            )
        )
        check(!memoryPlan.cannotFit) { memoryPlan.fallbackReason ?: "CannotFit" }
        frames.forEach { frame ->
            cancellation.throwIfCancelled()
            decoders[frame] = BitmapRegionDecoder.newInstance(frame.file.absolutePath, false)
        }
        output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val mergeTileRows = memoryPlan.tileRows
        var tileTop = 0
        val decodeOpts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }
        while (tileTop < height) {
            cancellation.throwIfCancelled()
            val tileBottom = min(height, tileTop + mergeTileRows)
            val tileHeight = tileBottom - tileTop
            val pixelCount = Math.multiplyExact(width, tileHeight)
            val referenceBitmap = decoders.getValue(reference).decodeRegion(
                Rect(0, tileTop, width, tileBottom),
                decodeOpts
            ) ?: error("Could not decode reference tile")
            var referencePixelsFailure: Throwable? = null
            val referencePixels = try {
                IntArray(pixelCount).also {
                    referenceBitmap.getPixels(it, 0, width, 0, 0, width, tileHeight)
                }
            } catch (failure: Throwable) {
                referencePixelsFailure = failure
                throw failure
            } finally {
                var cleanupFailure: Throwable? = null
                try {
                    referenceBitmap.recycle()
                } catch (failure: Throwable) {
                    cleanupFailure = failure
                }
                val combined = combineSettlementFailure(referencePixelsFailure, cleanupFailure)
                if (combined !== referencePixelsFailure) throw requireNotNull(combined)
            }

            val sumR = FloatArray(pixelCount)
            val sumG = FloatArray(pixelCount)
            val sumB = FloatArray(pixelCount)
            val sumW = FloatArray(pixelCount)
            for (pixel in 0 until pixelCount) {
                val color = referencePixels[pixel]
                val referenceWeight = if (params.fusionAlgorithm == FusionAlgorithm.MOTION_SAFE) {
                    params.referenceWeight * 1.75f
                } else params.referenceWeight
                sumR[pixel] = Color.red(color) * referenceWeight
                sumG[pixel] = Color.green(color) * referenceWeight
                sumB[pixel] = Color.blue(color) * referenceWeight
                sumW[pixel] = referenceWeight
            }

            val candidateFrames = frames.filterNot { it === reference }
            var batchStart = 0
            while (batchStart < candidateFrames.size) {
                val batchEnd = min(candidateFrames.size, batchStart + memoryPlan.candidateBatchSize)
                candidateFrames.subList(batchStart, batchEnd).forEachIndexed frameLoop@ { frameOffset, frame ->
        if (reportedMergeFrames.add(frame.jsonIndex)) {
            onStatus("Classic YUV fusion: merging frame ${batchStart + frameOffset + 2}/${frames.size}...")
        }
        val sourceLeft = max(0, frame.alignDx)
        val sourceTop = max(0, tileTop + frame.alignDy)
        val sourceRight = min(width, width + frame.alignDx)
        val sourceBottom = min(height, tileBottom + frame.alignDy)
        if (sourceRight <= sourceLeft || sourceBottom <= sourceTop) return@frameLoop
        val region = Rect(sourceLeft, sourceTop, sourceRight, sourceBottom)
        val frameBitmap = decoders.getValue(frame).decodeRegion(region, decodeOpts)
            ?: return@frameLoop
                var framePixelsFailure: Throwable? = null
                val (frameWidth, frameHeight, framePixels) = try {
                    val frameWidth = frameBitmap.width
                    val frameHeight = frameBitmap.height
                    val framePixels = IntArray(frameWidth * frameHeight)
                    frameBitmap.getPixels(
                        framePixels, 0, frameWidth, 0, 0, frameWidth, frameHeight
                    )
                    Triple(frameWidth, frameHeight, framePixels)
                } catch (failure: Throwable) {
                    framePixelsFailure = failure
                    throw failure
                } finally {
                    var cleanupFailure: Throwable? = null
                    try {
                        frameBitmap.recycle()
                    } catch (failure: Throwable) {
                        cleanupFailure = failure
                    }
                    val combined = combineSettlementFailure(framePixelsFailure, cleanupFailure)
                    if (combined !== framePixelsFailure) throw requireNotNull(combined)
                }

                val alignmentWeight = alignmentWeight(
                    frame.alignmentScore,
                    params.alignmentRejectThreshold
                )
                val externalWeight = resolveExternalFrameWeight(externalFrameWeights, frame.jsonIndex)
                val gain = (
                    requireNotNull(reference.thumbnail).mean /
                        requireNotNull(frame.thumbnail).mean.coerceAtLeast(1f)
                    ).coerceIn(0.80f, 1.25f)
                val outputStartX = max(0, -frame.alignDx)
                val outputEndX = min(width, width - frame.alignDx)
                val outputStartY = max(tileTop, -frame.alignDy)
                val outputEndY = min(tileBottom, height - frame.alignDy)
                for (y in outputStartY until outputEndY) {
                    if ((y and 31) == 0) cancellation.throwIfCancelled()
                    val tileY = y - tileTop
                    val sourceY = y + frame.alignDy - sourceTop
                    for (x in outputStartX until outputEndX) {
                        val outputIndex = tileY * width + x
                        val sourceX = x + frame.alignDx - sourceLeft
                        val color = framePixels[sourceY * frameWidth + sourceX]
                        val refColor = referencePixels[outputIndex]
                        val adjustedLuma = luma(color) * gain
                        val difference = abs(adjustedLuma - luma(refColor))
                        val ghost = ghostWeight(difference, params)
                        val brightness = (luma(refColor) / 255f).coerceIn(0f, 1f)
                        val noiseWeight = if (params.fusionAlgorithm == FusionAlgorithm.NOISE_AWARE) {
                            val signal = (brightness * 255f).coerceIn(0f, 255f)
                            val shotNoise = 0.025f * signal
                            val readNoise = 4f
                            val noiseVariance = (readNoise + shotNoise).coerceAtLeast(1f)
                            val normalizedDifference = difference * difference / noiseVariance
                            (1f / (1f + normalizedDifference)).coerceIn(0f, 1f)
                        } else 1f
                        val motionWeight = if (params.fusionAlgorithm == FusionAlgorithm.MOTION_SAFE) {
                            val referenceGradient = localGradient(
                                referencePixels, width, tileHeight, x, tileY
                            )
                            val candidateGradient = localGradient(
                                framePixels, frameWidth, frameHeight, sourceX, sourceY
                            )
                            val gradientDisagreement = abs(referenceGradient - candidateGradient)
                            if (difference > params.ghostThreshold * 0.8f ||
                                gradientDisagreement > 32f) 0.05f else 0.72f
                        } else 1f
                        val localWeight = ghost * alignmentWeight * externalWeight * noiseWeight * motionWeight
                        comparedPixels++
                        if (ghost < 0.25f) rejectedPixels++
                        sumR[outputIndex] += Color.red(color) * gain * localWeight
                        sumG[outputIndex] += Color.green(color) * gain * localWeight
                        sumB[outputIndex] += Color.blue(color) * gain * localWeight
                        sumW[outputIndex] += localWeight
                    }
                }
            }
                batchStart = batchEnd
            }

            val outputPixels = IntArray(pixelCount)
            for (pixel in 0 until pixelCount) {
                if ((pixel and 4095) == 0) cancellation.throwIfCancelled()
                val weight = sumW[pixel].coerceAtLeast(0.001f)
                outputPixels[pixel] = Color.rgb(
                    (sumR[pixel] / weight).roundToInt().coerceIn(0, 255),
                    (sumG[pixel] / weight).roundToInt().coerceIn(0, 255),
                    (sumB[pixel] / weight).roundToInt().coerceIn(0, 255)
                )
            }
            cancellation.throwIfCancelled()
            output.setPixels(outputPixels, 0, width, 0, tileTop, width, tileHeight)
            tileTop = tileBottom
        }
        outputReturned = true
        return MergeResult(requireNotNull(output), rejectedPixels, comparedPixels, memoryPlan)
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        var cleanupFailure: Throwable? = null
        decoders.values.forEach { decoder ->
            try {
                decoder.recycle()
            } catch (failure: Throwable) {
                cleanupFailure = combineSettlementFailure(cleanupFailure, failure)
            }
        }
        if (!outputReturned) {
            try {
                output?.recycle()
            } catch (failure: Throwable) {
                cleanupFailure = combineSettlementFailure(cleanupFailure, failure)
            }
        }
        val combined = combineSettlementFailure(primaryFailure, cleanupFailure)
        if (combined !== primaryFailure) throw requireNotNull(combined)
    }
}

private fun resolveExternalFrameWeight(
    externalFrameWeights: Map<Int, Float>?,
    frameIndex: Int
): Float {
    if (externalFrameWeights == null) return 1.0f
    val raw = externalFrameWeights[frameIndex] ?: 1.0f
    if (!raw.isFinite() || raw <= 0f) return 1.0f
    return raw.coerceIn(EXTERNAL_FRAME_WEIGHT_MIN, EXTERNAL_FRAME_WEIGHT_MAX)
}

private fun alignmentWeight(score: Float, rejectThreshold: Float): Float {
    if (!score.isFinite()) return 0f
    return (1f - score / rejectThreshold).coerceIn(0f, 1f)
}

private fun ghostWeight(lumaDifference: Float, params: ClassicYuvFusionParams): Float {
    if (lumaDifference <= params.ghostThreshold) return 1f
    val normalized = (
        (lumaDifference - params.ghostThreshold) /
            (255f - params.ghostThreshold)
        ).coerceIn(0f, 1f)
    return (1f - normalized).pow(3).coerceAtLeast(params.ghostWeight)
}

private fun localGradient(pixels: IntArray, width: Int, height: Int, x: Int, y: Int): Float {
    if (width <= 0 || height <= 0) return 0f
    val safeX = x.coerceIn(0, width - 1)
    val safeY = y.coerceIn(0, height - 1)
    val center = luma(pixels[safeY * width + safeX]).toFloat()
    val left = luma(pixels[safeY * width + (safeX - 1).coerceAtLeast(0)]).toFloat()
    val right = luma(pixels[safeY * width + (safeX + 1).coerceAtMost(width - 1)]).toFloat()
    val top = luma(pixels[(safeY - 1).coerceAtLeast(0) * width + safeX]).toFloat()
    val bottom = luma(pixels[(safeY + 1).coerceAtMost(height - 1) * width + safeX]).toFloat()
    return (abs(left - center) + abs(right - center) +
        abs(top - center) + abs(bottom - center)).coerceAtMost(1020f)
}

internal fun applyClassicYuvPostProcessing(
    source: Bitmap,
    params: ClassicYuvFusionParams,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
): Bitmap {
    val width = source.width
    val height = source.height
    if (isIdentityProcessing(params)) {
        return source.copy(Bitmap.Config.ARGB_8888, true)
            ?: error("Could not copy identity postprocess source")
    }
    NativeImageEngine.process(
        source = source,
        denoise = params.denoiseAlgorithm,
        tone = params.toneAlgorithm,
        denoiseStrength = params.denoiseStrength,
        sharpen = params.sharpenAmount,
        localContrast = params.localContrastAmount,
        shadowLift = params.shadowLift,
        highlightRollOff = params.highlightRollOff,
        saturation = params.saturationBoost,
        tileRows = CLASSIC_FUSION_TILE_ROWS,
        cancellation = cancellation
    )?.let { return it }
    val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    fun tone(value: Float): Int {
        var normalized = (value / 255f).coerceIn(0f, 1f)
        normalized = when (params.toneAlgorithm) {
            NativeToneAlgorithm.NATURAL ->
                normalized * (0.92f + 0.08f * normalized) + 0.015f * (1f - normalized)
            NativeToneAlgorithm.LOCAL_COMPRESSION ->
                (normalized * (1f + 0.35f * (1f - normalized))) /
                    (1f + 0.35f * normalized)
            NativeToneAlgorithm.NIGHT -> {
                val retained = (normalized - 0.012f).coerceAtLeast(0f) / 0.988f
                (retained / (retained + 0.22f * (1f - retained))).coerceAtMost(0.985f)
            }
        }
        val lifted = normalized + params.shadowLift * (1f - normalized).pow(2)
        val rolled = lifted -
            params.highlightRollOff * lifted.pow(2) * (1f - lifted)
        return (rolled.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
    }
    try {
    var tileTop = 0
    while (tileTop < height) {
        cancellation.throwIfCancelled()
        val tileBottom = min(height, tileTop + CLASSIC_FUSION_TILE_ROWS)
        val sourceTop = max(0, tileTop - 1)
        val sourceBottom = min(height, tileBottom + 1)
        val sourceHeight = sourceBottom - sourceTop
        val sourcePixels = IntArray(width * sourceHeight)
        val outputPixels = IntArray(width * (tileBottom - tileTop))
        source.getPixels(sourcePixels, 0, width, 0, sourceTop, width, sourceHeight)
        fun at(x: Int, y: Int): Int {
            val safeX = x.coerceIn(0, width - 1)
            val safeY = y.coerceIn(sourceTop, sourceBottom - 1)
            return sourcePixels[(safeY - sourceTop) * width + safeX]
        }
        for (y in tileTop until tileBottom) {
            if ((y and 31) == 0) cancellation.throwIfCancelled()
            for (x in 0 until width) {
                val center = at(x, y)
                var lumaSum = 0f
                var chromaRSum = 0f
                var chromaBSum = 0f
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val color = at(x + dx, y + dy)
                        val luminance = luma(color).toFloat()
                        lumaSum += luminance
                        chromaRSum += Color.red(color) - luminance
                        chromaBSum += Color.blue(color) - luminance
                    }
                }
                val centerLuma = luma(center).toFloat()
                val localLuma = lumaSum / 9f
                val detail = centerLuma - localLuma
                val flatRegionWeight = (1f - abs(detail) / 28f).coerceIn(0f, 1f)
                val lumaDenoiseWeight = params.denoiseStrength * flatRegionWeight
                val denoisedLuma =
                    centerLuma * (1f - lumaDenoiseWeight) + localLuma * lumaDenoiseWeight
                val sharpenSuppression = (1f - params.denoiseStrength * 0.65f).coerceIn(0.55f, 1f)
                val sharpenedLuma = denoisedLuma +
                    detail * params.localContrastAmount +
                    detail * params.sharpenAmount * sharpenSuppression
                val centerChromaR = Color.red(center) - centerLuma
                val centerChromaB = Color.blue(center) - centerLuma
                val chromaR = (
                    centerChromaR * (1f - params.denoiseStrength) +
                        chromaRSum / 9f * params.denoiseStrength
                    ) * params.saturationBoost
                val chromaB = (
                    centerChromaB * (1f - params.denoiseStrength) +
                        chromaBSum / 9f * params.denoiseStrength
                    ) * params.saturationBoost
                val tonedLuma = tone(sharpenedLuma).toFloat()
                outputPixels[(y - tileTop) * width + x] = Color.rgb(
                    (tonedLuma + chromaR).roundToInt().coerceIn(0, 255),
                    (tonedLuma - 0.5f * chromaR - 0.5f * chromaB).roundToInt().coerceIn(0, 255),
                    (tonedLuma + chromaB).roundToInt().coerceIn(0, 255)
                )
            }
        }
        cancellation.throwIfCancelled()
        outputBitmap.setPixels(
            outputPixels, 0, width, 0, tileTop, width, tileBottom - tileTop
        )
        tileTop = tileBottom
    }
    } catch (t: Throwable) {
        var cleanupFailure: Throwable? = null
        try {
            outputBitmap.recycle()
        } catch (secondary: Throwable) {
            cleanupFailure = secondary
        }
        throw requireNotNull(combineSettlementFailure(t, cleanupFailure))
    }
    return outputBitmap
}

internal fun isIdentityProcessing(params: ClassicYuvFusionParams): Boolean =
    params.denoiseStrength <= 0f &&
        params.sharpenAmount <= 0f &&
        params.localContrastAmount <= 0f &&
        params.shadowLift <= 0f &&
        params.highlightRollOff <= 0f &&
        params.saturationBoost == 1f

private fun initializeClassicYuvRunMetadata(
    job: JSONObject,
    params: ClassicYuvFusionParams,
    processingStartedAt: Long,
    metadataPolicy: ReprocessMetadataPolicy
) {
    classicYuvRunScopedKeys.forEach(job::remove)
    job.put("jobType", "YUV_NIGHT_FUSION")
        .put("fusionEngine", "classic_yuv_v1")
        .put("fusionVersion", CLASSIC_FUSION_VERSION)
        .put("yuvFusionVersion", "YUV_NIGHT_FUSION_V0")
        .put("fusionParamsVersion", CLASSIC_YUV_FUSION_PARAMS_VERSION)
        .put("fusionPresetName", params.presetName)
        .put("fusionParams", params.toJson())
        .put("nativeAlignmentAvailable", NativeFusionAlignment.isAvailable())
        .put("alignmentVersion", "kotlin_integer_v1")
        .put("yuvAlignVersion", "YUV_GLOBAL_SHIFT_V0")
        .put("yuvMergeVersion", "YUV_TEMPORAL_GHOST_V0")
        .put("yuvDenoiseVersion", "YUV_LUMA_CHROMA_EDGE_AWARE_V0")
        .put("yuvDetailVersion", "YUV_LUMA_DETAIL_V0")
        .put("yuvSharpenVersion", "YUV_ADAPTIVE_LUMA_SHARPEN_V0")
        .put("yuvLookVersion", "YUV_NATURAL_NIGHT_LOOK_V0")
        .put("processingStartedAt", processingStartedAt)
        .put("yuvProcessingPolicy", metadataPolicy.name)
}

/** Resets current-run alignment/fusion fields for every enabled, non-excluded frame. */
private fun resetClassicFrameMetadataForCurrentRun(jobDir: File, job: JSONObject) {
    val frames = job.optJSONArray("frames") ?: return
    repeat(frames.length()) { index ->
        val frame = frames.optJSONObject(index) ?: return@repeat
        if (!frame.optBoolean("enabled", true) || frame.optBoolean("excludedByUser", false)) {
            return@repeat
        }
        resetClassicFrameAlignmentFields(frame)
        val fileName = frame.optString("file")
        val sourceIsSafe = fileName.isNotBlank() && when (
            NoFollowFileSystem.resolveDirectChildResult(jobDir, fileName, requireFile = true)
        ) {
            is NoFollowInspection.Present -> true
            else -> false
        }
        if (!sourceIsSafe) {
            clearClassicFrameAlignmentOnDecodeFailure(jobDir, frame, fileName, "MISSING_FILE")
        }
    }
}

/** Resets all Classic-owned per-frame alignment/fusion fields before a new processing run. */
private fun resetClassicFrameAlignmentFields(frameJson: JSONObject) {
    classicYuvPerFrameAlignmentFields.forEach { field ->
        frameJson.remove(field)
    }
}

/** Clears stale alignment data on decode failure and sets the current failure reason. */
private fun clearClassicFrameAlignmentOnDecodeFailure(jobDir: File, frameJson: JSONObject, fileName: String, reason: String) {
    classicYuvPerFrameAlignmentFields.forEach { field ->
        frameJson.remove(field)
    }
    // Determine the appropriate failure reason based on the decode failure
    val sourceIsSafe = fileName.isNotBlank() && when (
        NoFollowFileSystem.resolveDirectChildResult(jobDir, fileName, requireFile = true)
    ) {
        is NoFollowInspection.Present -> true
        else -> false
    }
    val alignmentFailureReason = if (!sourceIsSafe) "MISSING_FILE" else reason
    val fusionSkipReason = if (!sourceIsSafe) "MISSING_FILE" else "DECODE_FAILED"

    frameJson.put("alignmentUsed", false)
        .put("fusionUsed", false)
        .put("alignmentFailureReason", alignmentFailureReason)
        .put("fusionSkipReason", fusionSkipReason)
}

/** Updates alignment metadata and clears stale failure reason on success. */
private fun updateAlignmentMetadata(
    job: JSONObject,
    frame: ClassicFrame,
    params: ClassicYuvFusionParams,
    used: Boolean = frame.alignmentUsed,
    skipReason: String? = null
) {
    val frameJson = job.optJSONArray("frames")?.optJSONObject(frame.jsonIndex) ?: return
    val globalWeight = globalWeightFor(frame, params)
    frameJson.put("alignDx", frame.alignDx)
        .put("alignDy", frame.alignDy)
        .put("alignIntegerDx", frame.alignIntegerDx)
        .put("alignIntegerDy", frame.alignIntegerDy)
        .put("alignSubpixelDx", frame.alignSubpixelDx.toDouble())
        .put("alignSubpixelDy", frame.alignSubpixelDy.toDouble())
        .put("alignmentScore", frame.alignmentScore.toDouble())
        .put("alignmentConfidence", frame.alignmentConfidence.toDouble())
        .put("alignmentBackend", frame.alignmentBackend)
        .put("alignmentUsedSubpixel", frame.alignmentUsedSubpixel)
        .put("alignmentFallbackUsed", frame.alignmentFallbackUsed)
        .put("alignmentUsed", frame.alignmentUsed)
        .put("globalWeight", globalWeight.toDouble())
        .put("fusionUsed", used)
        .put("fusionSkipReason", skipReason ?: JSONObject.NULL)
        .remove("alignmentFailureReason") // clear stale failure reason on success
}

/** Merges Classic-owned per-frame alignment/fusion fields into the LOCKED job.json's frames array
 *  by stable identity (JSON index field + file name), with unique-file fallback.
 *  Copies only Classic alignment/fusion fields; preserves exclusion, selection, quality,
 *  and unrelated frame metadata.
 *  Merges ALL frames that have alignment data in the local job, including rejected and decode-failed frames.
 *  For touched frames, also removes absent Classic-owned fields. */
private fun mergeClassicFrameAlignmentIntoLockedJob(
    jobDir: File,
    localJob: JSONObject,
    attempt: ProcessingAttempt? = null
) {
    // Build a map of local frames by stable identity: (index, file) -> frame JSON from local job
    val localFrames = localJob.optJSONArray("frames") ?: return
    val localFrameMap = mutableMapOf<Pair<Int, String>, JSONObject>()
    val localFileCount = mutableMapOf<String, Int>()
    val localFrameByFile = mutableMapOf<String, JSONObject>()
    repeat(localFrames.length()) { index ->
        val frameJson = localFrames.optJSONObject(index) ?: return@repeat
        if (!frameJson.optBoolean("enabled", true) || frameJson.optBoolean("excludedByUser", false)) {
            return@repeat
        }
        val file = frameJson.optString("file")
        val idx = frameJson.optInt("index", index)
        localFrameMap[idx to file] = frameJson
        if (file.isNotBlank()) {
            localFileCount[file] = localFileCount.getOrDefault(file, 0) + 1
            if (localFileCount[file] == 1) localFrameByFile[file] = frameJson
        }
    }

    // Merge into locked frames by stable identity, inside the update lock
    val update: ((JSONObject) -> Unit) -> Unit = { mutate ->
        if (attempt != null) updateForProcessingAttempt(jobDir, attempt, mutate)
        else KeplerJobMetadata.update(jobDir, mutate)
    }
    update { current ->
        val lockedFrames = current.optJSONArray("frames") ?: return@update
        val lockedFileCount = mutableMapOf<String, Int>()
        repeat(lockedFrames.length()) { index ->
            val lockedFrame = lockedFrames.optJSONObject(index) ?: return@repeat
            if (!lockedFrame.optBoolean("enabled", true) || lockedFrame.optBoolean("excludedByUser", false)) {
                return@repeat
            }
            val file = lockedFrame.optString("file")
            if (file.isNotBlank()) {
                lockedFileCount[file] = lockedFileCount.getOrDefault(file, 0) + 1
            }
        }

        repeat(lockedFrames.length()) { index ->
            val lockedFrame = lockedFrames.optJSONObject(index) ?: return@repeat

            // Skip metadata writes for frames that are disabled or user-excluded in the locked copy
            if (!lockedFrame.optBoolean("enabled", true) || lockedFrame.optBoolean("excludedByUser", false)) {
                return@repeat
            }

            val file = lockedFrame.optString("file")
            val idx = lockedFrame.optInt("index", index)
            val key = idx to file
            // Exact (index, file) match first
            val localFrame = localFrameMap[key]
                ?: if (file.isNotBlank() && localFileCount[file] == 1 && lockedFileCount[file] == 1) {
                    localFrameByFile[file]
                } else null
            if (localFrame == null) return@repeat

            // Copy only Classic-owned alignment/fusion fields; preserve everything else
            // For touched frames, also remove absent Classic-owned fields
            val touched = true
            classicYuvPerFrameAlignmentFields.forEach { field ->
                if (localFrame.has(field)) {
                    lockedFrame.put(field, localFrame.get(field))
                } else if (touched) {
                    lockedFrame.remove(field)
                }
            }
        }
    }
}

private fun writeFusionDebugMetadata(
    jobDir: File,
    job: JSONObject,
    frames: List<ClassicFrame>,
    totalFrameCount: Int,
    ghostRejectedPixelRatio: Double,
    processingTimeMs: Long,
    outputWidth: Int,
    outputHeight: Int,
    params: ClassicYuvFusionParams,
    nativeAlignmentUsed: Boolean,
    fallbackAlignmentCount: Int,
    lowConfidenceAlignmentCount: Int
) {
    val frameMap = frames.associateBy { it.jsonIndex }
    val sourceFrames = job.optJSONArray("frames") ?: JSONArray()
    val alignments = JSONArray()
    repeat(sourceFrames.length()) { index ->
        val source = sourceFrames.optJSONObject(index) ?: return@repeat
        val frame = frameMap[index]
        val enabled = source.optBoolean("enabled", true)
        val excluded = source.optBoolean("excludedByUser", false)
        val fileName = source.optString("file")
        val skipReason = when {
            frame != null -> null
            !enabled || excluded -> "USER_EXCLUDED"
            fileName.isBlank() || NoFollowFileSystem.resolveDirectChildResult(
                jobDir, fileName, requireFile = true
            ) !is NoFollowInspection.Present -> "MISSING_FILE"
            source.optString("alignmentFailureReason").isNotBlank() ->
                source.optString("alignmentFailureReason")
            else -> source.optString("fusionSkipReason").ifBlank { "SKIPPED" }
        }
        alignments.put(
            JSONObject()
                .put("frameIndex", source.optInt("index", index))
                .put("file", fileName)
                .put("alignDx", frame?.alignDx ?: source.optInt("alignDx", 0))
                .put("alignDy", frame?.alignDy ?: source.optInt("alignDy", 0))
                .put("alignIntegerDx", frame?.alignIntegerDx ?: source.optInt("alignIntegerDx", 0))
                .put("alignIntegerDy", frame?.alignIntegerDy ?: source.optInt("alignIntegerDy", 0))
                .put("alignSubpixelDx", frame?.alignSubpixelDx?.toDouble() ?: source.optDouble("alignSubpixelDx", 0.0))
                .put("alignSubpixelDy", frame?.alignSubpixelDy?.toDouble() ?: source.optDouble("alignSubpixelDy", 0.0))
                .put(
                    "alignmentScore",
                    frame?.alignmentScore?.toDouble()
                        ?: source.optDouble("alignmentScore", Double.NaN)
                )
                .put(
                    "globalWeight",
                    frame?.let { globalWeightFor(it, params).toDouble() }
                        ?: source.optDouble("globalWeight", 0.0)
                )
                .put("alignmentConfidence", frame?.alignmentConfidence?.toDouble() ?: source.optDouble("alignmentConfidence", 0.0))
                .put("alignmentBackend", frame?.alignmentBackend ?: source.optString("alignmentBackend", "none"))
                .put("alignmentUsedSubpixel", frame?.alignmentUsedSubpixel ?: source.optBoolean("alignmentUsedSubpixel", false))
                .put("alignmentFallbackUsed", frame?.alignmentFallbackUsed ?: source.optBoolean("alignmentFallbackUsed", false))
                .put("used", frame != null)
                .put("skipReason", skipReason ?: JSONObject.NULL)
        )
    }
    val debug = JSONObject()
        .put("fusionEngine", "classic_yuv_v1")
        .put("fusionVersion", CLASSIC_FUSION_VERSION)
        .put("fusionParamsVersion", CLASSIC_YUV_FUSION_PARAMS_VERSION)
        .put("fusionPresetName", params.presetName)
        .put("fusionParams", params.toJson())
        .put("nativeAlignmentAvailable", NativeFusionAlignment.isAvailable())
        .put("nativeAlignmentUsed", nativeAlignmentUsed)
        .put("alignmentVersion", if (nativeAlignmentUsed) "native_subpixel_v1" else "kotlin_integer_v1")
        .put("fallbackAlignmentCount", fallbackAlignmentCount)
        .put("lowConfidenceAlignmentCount", lowConfidenceAlignmentCount)
        .put("referenceFrameIndex", job.optInt("referenceFrameIndex"))
        .put("usedFrameCount", frames.size)
        .put("excludedFrameCount", countExcludedFrames(job))
        .put(
            "skippedFrameCount",
            (totalFrameCount - countExcludedFrames(job) - frames.size).coerceAtLeast(0)
        )
        .put("alignments", alignments)
        .put("ghostSuppressionUsed", true)
        .put("ghostRejectedPixelRatio", ghostRejectedPixelRatio)
        .put("processingTimeMs", processingTimeMs)
        .put("outputWidth", outputWidth)
        .put("outputHeight", outputHeight)
    writeVerifiedJsonArtifact(File(jobDir, "fusion_debug.json"), debug.toString(2))
    writeVerifiedJsonArtifact(File(jobDir, "yuv_debug.json"), debug.toString(2))
    job.put("fusionDebugFile", "fusion_debug.json")
        .put("yuvDebugFile", "yuv_debug.json")
        .put("fusionAlignmentSummary", alignments)
}

internal fun generateFusionDebugArtifacts(
    jobDir: File,
    job: JSONObject,
    referenceFile: File,
    mergedBitmap: Bitmap,
    fusedBitmap: Bitmap,
    params: ClassicYuvFusionParams
) {
    try {
        // Phase 7 policy + Phase-A corrective split: heavy diagnostic IMAGES
        // require explicit debug/diagnostic intent.  Bounded quality METRICS /
        // JSON evidence are UNCONDITIONAL production diagnostic contract and
        // are computed+persisted on BOTH paths; only expensive full-resolution
        // image generation is gated.
        if (!DebugArtifactPolicy.imageArtifactsEnabled(job)) {
            writeBoundedQualityEvidence(job, jobDir, referenceFile, mergedBitmap, fusedBitmap, params)
            job.put("debugArtifactStatus", DebugArtifactPolicy.STATUS_DISABLED)
                .put("debugArtifactImagesEnabled", false)
                .put("yuvFinalPreviewFile", "yuv_final_preview.png")
            return
        }
        val referenceOutput = File(jobDir, "reference_frame.png")
        copyVerifiedArtifact(referenceFile, referenceOutput)
        val yuvReferenceOutput = File(jobDir, "yuv_reference_preview.png")
        copyVerifiedArtifact(referenceFile, yuvReferenceOutput)
        val fusedOutput = File(jobDir, "fused_classic_yuv_v1.png")
        saveClassicBitmap(fusedBitmap, fusedOutput)
        val yuvFusedOutput = File(jobDir, "yuv_fused_preview.png")
        saveClassicBitmap(fusedBitmap, yuvFusedOutput)
        val presetOutput = File(
            jobDir,
            "fused_classic_yuv_v1_${params.presetName.lowercase()}.png"
        )
        saveClassicBitmap(fusedBitmap, presetOutput)

        val referencePreview = decodeDebugPreview(referenceFile)
        val yuvBeforeDenoisePreview = saveBoundedDiagnosticPreview(
            mergedBitmap,
            File(jobDir, "yuv_fused_before_denoise_preview.png")
        )
        val yuvNoSharpenPreview = applyClassicYuvPostProcessing(
            yuvBeforeDenoisePreview,
            params.copy(sharpenAmount = 0f, localContrastAmount = 0f)
        )
        saveClassicBitmap(yuvNoSharpenPreview, File(jobDir, "yuv_fused_after_denoise_no_sharpen_preview.png"))
        val yuvFinalPreview = saveBoundedDiagnosticPreview(
            fusedBitmap,
            File(jobDir, "yuv_final_preview.png")
        )
        var fusedPreview: Bitmap? = null
        var comparison: Bitmap? = null
        var debugPrimaryFailure: Throwable? = null
        try {
            fusedPreview = Bitmap.createScaledBitmap(
                fusedBitmap,
                referencePreview.width,
                referencePreview.height,
                true
            )
            comparison = Bitmap.createBitmap(
                referencePreview.width * 2,
                referencePreview.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(comparison)
            canvas.drawBitmap(referencePreview, 0f, 0f, null)
            canvas.drawBitmap(fusedPreview, referencePreview.width.toFloat(), 0f, null)
            saveClassicBitmap(comparison, File(jobDir, "compare_reference_vs_fused.png"))
            saveClassicBitmap(comparison, File(jobDir, "yuv_compare_reference_vs_fused.png"))
            writeFusionQualityDiagnostics(
                job = job,
                jobDir = jobDir,
                prefix = "yuv",
                reference = referencePreview,
                fused = yuvBeforeDenoisePreview,
                denoised = yuvNoSharpenPreview,
                finalImage = yuvFinalPreview,
                compareFileName = "yuv_compare_reference_vs_final.png"
            )
        } catch (failure: Throwable) {
            debugPrimaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            listOfNotNull(
                comparison,
                fusedPreview?.takeUnless { it === fusedBitmap },
                yuvBeforeDenoisePreview,
                yuvNoSharpenPreview,
                yuvFinalPreview,
                referencePreview
            ).forEach { bitmap ->
                try {
                    bitmap.recycle()
                } catch (failure: Throwable) {
                    cleanupFailure = combineSettlementFailure(cleanupFailure, failure)
                }
            }
            val combined = combineSettlementFailure(debugPrimaryFailure, cleanupFailure)
            if (combined !== debugPrimaryFailure) throw requireNotNull(combined)
        }
        job.put("referenceFrameDebugFile", referenceOutput.name)
            .put("yuvReferencePreviewFile", yuvReferenceOutput.name)
            .put("fusedClassicDebugFile", fusedOutput.name)
            .put("yuvFusedPreviewFile", yuvFusedOutput.name)
            .put("yuvFusedBeforeDenoisePreviewFile", "yuv_fused_before_denoise_preview.png")
            .put("yuvFusedAfterDenoiseNoSharpenPreviewFile", "yuv_fused_after_denoise_no_sharpen_preview.png")
            .put("yuvFinalPreviewFile", "yuv_final_preview.png")
            .put("fusedClassicPresetFile", presetOutput.name)
            .put("comparisonDebugFile", "compare_reference_vs_fused.png")
            .put("yuvComparePreviewFile", "yuv_compare_reference_vs_fused.png")
            .put("yuvCompareReferenceVsFinalFile", "yuv_compare_reference_vs_final.png")
            .put("debugArtifactStatus", "COMPLETE")
            .remove("debugArtifactError")
    } catch (fatal: Error) {
        throw fatal
    } catch (e: Exception) {
        job.put("debugArtifactStatus", "FAILED")
            .put("debugArtifactError", "${e.javaClass.simpleName}: ${e.message}".take(240))
    }
}

/**
 * UNCONDITIONAL bounded quality evidence for the image-artifacts-disabled path.
 * Computes the same BOUNDED previews the enabled path uses, derives the fusion
 * quality metrics JSON ([writeFusionQualityDiagnostics] merges them into the
 * job; the pipeline persists them via yuv_debug.json), and recycles every
 * intermediate.  NO heavy full-resolution diagnostic image is written here;
 * the compare/crop sheets inside [writeFusionQualityDiagnostics] stay gated by
 * [DebugArtifactPolicy].
 */
private fun writeBoundedQualityEvidence(
    job: JSONObject,
    jobDir: File,
    referenceFile: File,
    mergedBitmap: Bitmap,
    fusedBitmap: Bitmap,
    params: ClassicYuvFusionParams
) {
    val referencePreview = decodeDebugPreview(referenceFile)
    var yuvBeforeDenoisePreview: Bitmap? = null
    var yuvNoSharpenPreview: Bitmap? = null
    var yuvFinalPreview: Bitmap? = null
    try {
        yuvBeforeDenoisePreview = saveBoundedDiagnosticPreview(
            mergedBitmap,
            File(jobDir, "yuv_fused_before_denoise_preview.png")
        )
        yuvNoSharpenPreview = applyClassicYuvPostProcessing(
            yuvBeforeDenoisePreview,
            params.copy(sharpenAmount = 0f, localContrastAmount = 0f)
        )
        yuvFinalPreview = saveBoundedDiagnosticPreview(
            fusedBitmap,
            File(jobDir, "yuv_final_preview.png")
        )
        job.put("yuvFusedBeforeDenoisePreviewFile", "yuv_fused_before_denoise_preview.png")
            .put("yuvFusedAfterDenoiseNoSharpenPreviewFile", "yuv_fused_after_denoise_no_sharpen_preview.png")
        writeFusionQualityDiagnostics(
            job = job,
            jobDir = jobDir,
            prefix = "yuv",
            reference = referencePreview,
            fused = yuvBeforeDenoisePreview,
            denoised = yuvNoSharpenPreview,
            finalImage = yuvFinalPreview,
            compareFileName = "yuv_compare_reference_vs_final.png"
        )
    } finally {
        var cleanupFailure: Throwable? = null
        listOfNotNull(referencePreview, yuvBeforeDenoisePreview, yuvNoSharpenPreview, yuvFinalPreview)
            .forEach { bitmap ->
                try {
                    bitmap.recycle()
                } catch (failure: Throwable) {
                    cleanupFailure = combineSettlementFailure(cleanupFailure, failure)
                }
            }
        // Recycling failures are diagnostics-only on this path: never fail the
        // required output pipeline for a debug-bitmap cleanup.
        cleanupFailure?.let { android.util.Log.w("ClassicYuvFusion", "quality evidence bitmap recycle failed", it) }
    }
}

private fun decodeDebugPreview(file: File): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    NoFollowFileSystem.decodeBitmapVerified(file, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unreadable debug image" }
    var sampleSize = 1
    while (
        max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) >
        CLASSIC_FUSION_DEBUG_MAX_DIMENSION
    ) {
        sampleSize *= 2
    }
    return NoFollowFileSystem.decodeBitmapVerified(
        file,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
    ) ?: error("Could not decode debug preview")
}

private fun countExcludedFrames(job: JSONObject): Int {
    val frames = job.optJSONArray("frames") ?: return 0
    var count = 0
    repeat(frames.length()) { index ->
        val frame = frames.optJSONObject(index) ?: return@repeat
        if (!frame.optBoolean("enabled", true) || frame.optBoolean("excludedByUser", false)) count++
    }
    return count
}

private fun globalWeightFor(
    frame: ClassicFrame,
    params: ClassicYuvFusionParams
): Float =
    if (frame.isReference) {
        params.referenceWeight
    } else {
        alignmentWeight(frame.alignmentScore, params.alignmentRejectThreshold)
    }

private fun decodeImageDimensions(file: File): Pair<Int, Int> {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    NoFollowFileSystem.decodeBitmapVerified(file, options)
    require(options.outWidth > 0 && options.outHeight > 0) { "Unreadable frame: ${file.name}" }
    return options.outWidth to options.outHeight
}

private fun buildClassicYuvProcessingPreflight(
    jobDir: File,
    job: JSONObject
): ClassicYuvProcessingPreflight {
    val frames = job.optJSONArray("frames")
    if (frames == null) {
        return ClassicYuvProcessingPreflight(
            totalFrames = 0,
            enabledFrames = 0,
            existingFrameFiles = 0,
            missingFrameFiles = 0,
            decodeProbePassed = 0,
            decodeProbeFailed = 0
        )
    }

    var enabledFrames = 0
    var existingFrameFiles = 0
    var missingFrameFiles = 0
    var decodeProbePassed = 0
    var decodeProbeFailed = 0

    repeat(frames.length()) { index ->
        val frame = frames.optJSONObject(index) ?: return@repeat
        if (!frame.optBoolean("enabled", true) || frame.optBoolean("excludedByUser", false)) {
            return@repeat
        }
        enabledFrames++
        val fileName = frame.optString("file")
        if (fileName.isBlank()) {
            missingFrameFiles++
            return@repeat
        }
        val file = when (val resolved = NoFollowFileSystem.resolveDirectChildResult(
            jobDir, fileName, requireFile = true
        )) {
            is NoFollowInspection.Present -> resolved.value
            else -> null
        }
        if (file == null) {
            missingFrameFiles++
            return@repeat
        }
        existingFrameFiles++
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        NoFollowFileSystem.decodeBitmapVerified(file, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            decodeProbePassed++
        } else {
            decodeProbeFailed++
        }
    }

    return ClassicYuvProcessingPreflight(
        totalFrames = frames.length(),
        enabledFrames = enabledFrames,
        existingFrameFiles = existingFrameFiles,
        missingFrameFiles = missingFrameFiles,
        decodeProbePassed = decodeProbePassed,
        decodeProbeFailed = decodeProbeFailed
    )
}

private fun ClassicYuvProcessingPreflight.toJson(): JSONObject =
    JSONObject()
        .put("totalFrames", totalFrames)
        .put("enabledFrames", enabledFrames)
        .put("existingFrameFiles", existingFrameFiles)
        .put("missingFrameFiles", missingFrameFiles)
        .put("decodeProbePassed", decodeProbePassed)
        .put("decodeProbeFailed", decodeProbeFailed)

private fun resolveClassicFailureCounts(
    preflight: ClassicYuvProcessingPreflight,
    decodedUsableFrameCount: Int,
    sameSizeFrameCount: Int,
    compatibleFrameCount: Int,
    sameSizeFrameCountKnown: Boolean,
    compatibleFrameCountKnown: Boolean
): ClassicYuvProcessingFailureCounts {
    return ClassicYuvProcessingFailureCounts(
        totalFrames = preflight.totalFrames,
        enabledFrames = preflight.enabledFrames,
        decodedUsableFrames = decodedUsableFrameCount,
        sameSizeFrames = if (sameSizeFrameCountKnown) sameSizeFrameCount else null,
        compatibleFrames = if (compatibleFrameCountKnown) compatibleFrameCount else null
    )
}

private fun recordClassicFailure(
    jobFile: File,
    job: JSONObject,
    status: String,
    reason: String,
    throwable: Throwable? = null,
    failureCounts: ClassicYuvProcessingFailureCounts? = null,
    preflight: ClassicYuvProcessingPreflight? = null,
    params: ClassicYuvFusionParams,
    processingStartedAt: Long,
    metadataPolicy: ReprocessMetadataPolicy = ReprocessMetadataPolicy.NORMAL,
    nativeAlignmentUsed: Boolean = false,
    fallbackAlignmentCount: Int = 0,
    lowConfidenceAlignmentCount: Int = 0,
    externalFrameWeights: Map<Int, Float>? = null,
    referenceFrameIndex: Int? = null,
    usedFrameCount: Int? = null,
    acceptedFrameCount: Int? = null,
    rejectedFrameCount: Int? = null,
    excludedFrameCount: Int? = null,
    skippedFrameCount: Int? = null,
    ghostRejectedPixelRatio: Double? = null,
    outputWidth: Int? = null,
    outputHeight: Int? = null,
    yuvWidth: Int? = null,
    yuvHeight: Int? = null,
    attempt: ProcessingAttempt? = null
) {
    try {
        val now = System.currentTimeMillis()
        job.put("currentPipelineStage", "PIPELINE_FAILED")
            .put("processStatus", "PIPELINE_FAILED")
            .put("pipelineFailed", true)
            .put("pipelineFailureStatusCode", status)
            .put("pipelineFailureSource", "processClassicYuvFusionJob")
            .put("pipelineFailureType", throwable?.javaClass?.name ?: "Unknown")
            .put("pipelineFailureMessage", formatClassicFailureMessage(throwable, reason))
            .put("pipelineFailureStackTrace", throwable?.stackTraceToString() ?: "")
            .put("processFailureReason", reason)
        .put("fusionEngine", "classic_yuv_v1")
        .put("fusionVersion", CLASSIC_FUSION_VERSION)
        .put("yuvFusionVersion", "YUV_NIGHT_FUSION_V0")
        .put("fusionParamsVersion", CLASSIC_YUV_FUSION_PARAMS_VERSION)
        .put("fusionPresetName", params.presetName)
        .put("fusionParams", params.toJson())
        // On NORMAL failure, persist userCanMoveDevice=true as the current terminal state
        .put("userCanMoveDevice", true)
        .put("processedAt", now)
        .put("processingStartedAt", processingStartedAt)
        .put("processingTimeMs", now - processingStartedAt)
        .put("timing", JSONObject().put("totalPipelineMs", now - processingStartedAt))
        .put("yuvProcessingPolicy", metadataPolicy.name)
        // Persist current-run alignment state
        .put("nativeAlignmentAvailable", NativeFusionAlignment.isAvailable())
        .put("nativeAlignmentUsed", nativeAlignmentUsed)
        .put("alignmentVersion", if (nativeAlignmentUsed) "native_subpixel_v1" else "kotlin_integer_v1")
        .put("yuvAlignVersion", "YUV_GLOBAL_SHIFT_V0")
        .put("yuvMergeVersion", "YUV_TEMPORAL_GHOST_V0")
        .put("yuvDenoiseVersion", "YUV_LUMA_CHROMA_EDGE_AWARE_V0")
        .put("yuvDetailVersion", "YUV_LUMA_DETAIL_V0")
        .put("yuvSharpenVersion", "YUV_ADAPTIVE_LUMA_SHARPEN_V0")
        .put("yuvLookVersion", "YUV_NATURAL_NIGHT_LOOK_V0")
        .put("fallbackAlignmentCount", fallbackAlignmentCount)
        .put("lowConfidenceAlignmentCount", lowConfidenceAlignmentCount)
        // Persist current-run frame counts (only when known)
        usedFrameCount?.let { job.put("usedFrameCount", it) }
        acceptedFrameCount?.let { job.put("acceptedFrameCount", it) }
        rejectedFrameCount?.let { job.put("rejectedFrameCount", it) }
        excludedFrameCount?.let { job.put("excludedFrameCount", it) }
        skippedFrameCount?.let { job.put("skippedFrameCount", it) }
        // Persist current-run reference frame index
        referenceFrameIndex?.let { job.put("referenceFrameIndex", it).put("yuvReferenceFrameIndex", it) }
        // Persist ghost suppression metrics (only when merge completed)
        ghostRejectedPixelRatio?.let {
            job.put("ghostSuppressionUsed", true)
                .put("ghostSuppressionEnabled", true)
                .put("ghostRejectedPixelRatio", it)
                .put("rejectedGhostSampleRatio", it)
        }
        // Persist output and YUV dimensions (only when known)
        outputWidth?.let { job.put("outputWidth", it) }
        outputHeight?.let { job.put("outputHeight", it) }
        yuvWidth?.let { job.put("yuvWidth", it) }
        yuvHeight?.let { job.put("yuvHeight", it) }
        // Initialize duplicated config metadata from current clamped params
        job.put("lumaDenoiseStrength", params.denoiseStrength.toDouble())
            .put("chromaDenoiseStrength", params.denoiseStrength.toDouble())
            .put("lowLightChromaBoost", true)
            .put("adaptiveSharpenUsed", true)
            .put("blackPoint", 0.018)
            .put("contrastCurve", "mild_s_curve")
            .put("saturationBoost", params.saturationBoost.toDouble())
            .put("vibranceBoost", 0.04)
            .put("localContrastAmount", params.localContrastAmount.toDouble())
        // Persist current external-weight state
        if (externalFrameWeights != null && externalFrameWeights.isNotEmpty()) {
            job.put("yuvExternalFrameWeightsUsed", true)
                .put("yuvExternalFrameWeightsTarget", "NON_REFERENCE_FRAMES_ONLY")
        } else {
            job.put("yuvExternalFrameWeightsUsed", false)
            job.remove("yuvExternalFrameWeightsTarget")
        }
        preflight?.let { job.put("yuvProcessingPreflight", it.toJson()) }
        failureCounts?.let { fc ->
            job.put("yuvProcessingTotalFrames", fc.totalFrames)
                .put("yuvProcessingEnabledFrames", fc.enabledFrames)
                .put("yuvProcessingDecodedUsableFrames", fc.decodedUsableFrames)
            if (fc.sameSizeFrames != null) {
                job.put("yuvProcessingSameSizeFrames", fc.sameSizeFrames)
            } else {
                job.remove("yuvProcessingSameSizeFrames")
            }
            if (fc.compatibleFrames != null) {
                job.put("yuvProcessingCompatibleFrames", fc.compatibleFrames)
            } else {
                job.remove("yuvProcessingCompatibleFrames")
            }
        }
        persistClassicYuvFailure(
            jobDir = jobFile.parentFile ?: error("Job directory missing"),
            job = job,
            metadataPolicy = metadataPolicy,
            attempt = attempt
        )
    } catch (metadataFailure: Error) {
        when {
            throwable is Error -> {
                if (throwable !== metadataFailure) throwable.addSuppressed(metadataFailure)
                throw throwable
            }
            else -> {
                metadataFailure.addSuppressed(throwable ?: IllegalStateException(reason))
                throw metadataFailure
            }
        }
    } catch (metadataFailure: CancellationException) {
        throw requireNotNull(combineSettlementFailure(throwable, metadataFailure))
    } catch (metadataFailure: Exception) {
        Log.e("KeplerYuvPipeline", "failure metadata persistence failed", metadataFailure)
    }
}





private val classicYuvRunScopedKeys: Set<String> = setOf(
        "timing", "processingTimeMs", "debugArtifactStatus", "debugArtifactError",
        "fusionDebugFile", "yuvDebugFile", "fusionAlignmentSummary",
        "referenceFrameDebugFile", "yuvReferencePreviewFile", "fusedClassicDebugFile",
        "yuvFusedPreviewFile", "yuvFusedBeforeDenoisePreviewFile",
        "yuvFusedAfterDenoiseNoSharpenPreviewFile", "yuvFinalPreviewFile",
        "fusedClassicPresetFile", "comparisonDebugFile", "yuvComparePreviewFile",
        "yuvCompareReferenceVsFinalFile",
        "nativeAlignmentUsed", "fallbackAlignmentCount", "lowConfidenceAlignmentCount",
        "usedFrameCount", "acceptedFrameCount", "rejectedFrameCount", "excludedFrameCount", "skippedFrameCount",
        "referenceFrameIndex", "yuvReferenceFrameIndex",
        "ghostSuppressionUsed", "ghostSuppressionEnabled", "ghostRejectedPixelRatio", "rejectedGhostSampleRatio",
        "outputWidth", "outputHeight", "yuvWidth", "yuvHeight",
        "averageColorFile", "finalNightFusionFile", "finalFile", "finalOutputSource",
        "galleryDisplayFile", "galleryThumbnailFile", "galleryDisplaySource",
        "isDebugPreviewUsedAsFinal", "yuvFusionLooksWorseHint", "yuvQualityDiagnosticHints",
        "lumaDenoiseStrength", "chromaDenoiseStrength", "lowLightChromaBoost", "adaptiveSharpenUsed",
        "blackPoint", "contrastCurve", "saturationBoost", "vibranceBoost", "localContrastAmount",
        "processedAt", "processingNotes",
        "yuvExternalFrameWeightsUsed", "yuvExternalFrameWeightsTarget",
        "yuvProcessingPolicy", "frameCount"
    )

// Final progress keys for REPROCESS_PROGRESS_ONLY: active progress minus terminal state (stage/status)
private const val CLASSIC_YUV_FINAL_PROGRESS_KEYS =
    "processingStartedAt,yuvProcessingPreflight,yuvProcessingPolicy," +
        "yuvProcessingTotalFrames,yuvProcessingEnabledFrames,yuvProcessingDecodedUsableFrames," +
        "yuvProcessingSameSizeFrames,yuvProcessingCompatibleFrames,timing,processingTimeMs," +
        "debugArtifactStatus,debugArtifactError,fusionDebugFile,yuvDebugFile,fusionAlignmentSummary"

private val classicYuvFinalProgressKeys: Set<String> = CLASSIC_YUV_FINAL_PROGRESS_KEYS.split(',').toSet()

// Classic-owned keys that should be present on NORMAL success (all terminal + progress + diagnostic)
private val classicYuvSuccessOwnedKeys: Set<String> = setOf(
    "jobType", "currentPipelineStage", "userCanMoveDevice", "processingStartedAt", "processStatus",
    "fusionEngine", "fusionVersion", "yuvFusionVersion", "fusionParamsVersion", "fusionPresetName",
    "fusionParams", "nativeAlignmentAvailable", "nativeAlignmentUsed", "alignmentVersion",
    "yuvAlignVersion", "yuvMergeVersion", "yuvDenoiseVersion", "yuvDetailVersion", "yuvSharpenVersion",
    "yuvLookVersion", "fallbackAlignmentCount", "lowConfidenceAlignmentCount",
    "usedFrameCount", "acceptedFrameCount", "rejectedFrameCount", "excludedFrameCount", "skippedFrameCount",
    "referenceFrameIndex", "yuvReferenceFrameIndex", "ghostSuppressionUsed", "ghostSuppressionEnabled",
    "ghostRejectedPixelRatio", "rejectedGhostSampleRatio", "averageColorFile", "finalNightFusionFile",
    "finalFile", "finalOutputSource", "galleryDisplayFile", "galleryThumbnailFile", "galleryDisplaySource",
    "isDebugPreviewUsedAsFinal", "yuvFusionLooksWorseHint", "yuvQualityDiagnosticHints",
    "processingTimeMs", "outputWidth", "outputHeight", "frameCount", "yuvWidth", "yuvHeight",
    "lumaDenoiseStrength", "chromaDenoiseStrength", "lowLightChromaBoost", "adaptiveSharpenUsed",
    "blackPoint", "contrastCurve", "saturationBoost", "vibranceBoost", "localContrastAmount",
    "timing", "processedAt", "processingNotes",
    "yuvProcessingPreflight", "yuvProcessingPolicy", "yuvProcessingTotalFrames", "yuvProcessingEnabledFrames",
    "yuvProcessingDecodedUsableFrames", "yuvProcessingSameSizeFrames", "yuvProcessingCompatibleFrames",
    "yuvExternalFrameWeightsUsed", "yuvExternalFrameWeightsTarget",
    "debugArtifactStatus", "debugArtifactError", "fusionDebugFile", "yuvDebugFile", "fusionAlignmentSummary",
    "referenceFrameDebugFile", "yuvReferencePreviewFile", "fusedClassicDebugFile", "yuvFusedPreviewFile",
    "yuvFusedBeforeDenoisePreviewFile", "yuvFusedAfterDenoiseNoSharpenPreviewFile", "yuvFinalPreviewFile",
    "fusedClassicPresetFile", "comparisonDebugFile", "yuvComparePreviewFile",
    "yuvCompareReferenceVsFinalFile"
)

// Classic-owned keys that should be present on NORMAL failure (terminal failure + progress + diagnostic)
private val classicYuvFailureOwnedKeys: Set<String> = setOf(
    "jobType", "currentPipelineStage", "processStatus", "pipelineFailed", "pipelineFailureStatusCode",
    "pipelineFailureSource", "pipelineFailureType", "pipelineFailureMessage", "pipelineFailureStackTrace",
    "processFailureReason", "fusionEngine", "fusionVersion", "yuvFusionVersion",
    "fusionParamsVersion", "fusionPresetName", "fusionParams", "userCanMoveDevice",
    "nativeAlignmentAvailable", "nativeAlignmentUsed", "alignmentVersion",
    "yuvAlignVersion", "yuvMergeVersion", "yuvDenoiseVersion", "yuvDetailVersion",
    "yuvSharpenVersion", "yuvLookVersion", "processedAt",
    "yuvProcessingPreflight", "yuvProcessingPolicy", "yuvProcessingTotalFrames", "yuvProcessingEnabledFrames",
    "yuvProcessingDecodedUsableFrames", "yuvProcessingSameSizeFrames", "yuvProcessingCompatibleFrames",
    "processingStartedAt", "debugArtifactStatus", "debugArtifactError", "timing", "processingTimeMs",
    "fusionDebugFile", "yuvDebugFile", "fusionAlignmentSummary", "referenceFrameDebugFile",
    "yuvReferencePreviewFile", "fusedClassicDebugFile", "yuvFusedPreviewFile",
    "yuvFusedBeforeDenoisePreviewFile", "yuvFusedAfterDenoiseNoSharpenPreviewFile",
    "yuvFinalPreviewFile", "fusedClassicPresetFile", "comparisonDebugFile",
    "yuvComparePreviewFile", "yuvCompareReferenceVsFinalFile"
)

// Keys that are opposite-type: failure keys to clear on success, success keys to clear on failure
private val classicYuvFailureTerminalKeys: Set<String> = setOf(
    "pipelineFailed", "pipelineFailureStatusCode", "pipelineFailureSource",
    "pipelineFailureType", "pipelineFailureMessage", "pipelineFailureStackTrace", "processFailureReason"
)

// Narrow set of stale final/output/gallery fields to clear on NORMAL failure.
// Preserves identity, diagnostic, current-run fields, timing, counters, params, algorithm versions.
private val classicYuvStaleFinalOutputKeys: Set<String> = setOf(
        "averageColorFile", "finalNightFusionFile", "finalFile", "finalOutputSource",
        "galleryDisplayFile", "galleryThumbnailFile", "galleryDisplaySource",
        "isDebugPreviewUsedAsFinal"
    )

// Classic run-produced result and diagnostic fields (written on NORMAL success/failure).
// On NORMAL failure: write current values that exist; remove absent keys from this set.
private val classicYuvRunResultKeys: Set<String> = setOf(
        "nativeAlignmentUsed", "fallbackAlignmentCount", "lowConfidenceAlignmentCount",
        "usedFrameCount", "acceptedFrameCount", "rejectedFrameCount", "excludedFrameCount", "skippedFrameCount",
        "referenceFrameIndex", "yuvReferenceFrameIndex",
        "ghostSuppressionUsed", "ghostSuppressionEnabled", "ghostRejectedPixelRatio", "rejectedGhostSampleRatio",
        "outputWidth", "outputHeight", "yuvWidth", "yuvHeight",
        "processingTimeMs", "processedAt",
        "lumaDenoiseStrength", "chromaDenoiseStrength", "lowLightChromaBoost", "adaptiveSharpenUsed",
        "blackPoint", "contrastCurve", "saturationBoost", "vibranceBoost", "localContrastAmount",
        "yuvExternalFrameWeightsUsed", "yuvExternalFrameWeightsTarget",
        "alignmentVersion", "yuvAlignVersion", "yuvMergeVersion", "yuvDenoiseVersion",
        "yuvDetailVersion", "yuvSharpenVersion", "yuvLookVersion",
        "fusionEngine", "fusionVersion", "yuvFusionVersion", "fusionParamsVersion",
        "fusionPresetName", "fusionParams", "nativeAlignmentAvailable",
        "yuvProcessingPreflight", "yuvProcessingPolicy", "yuvProcessingTotalFrames",
        "yuvProcessingEnabledFrames", "yuvProcessingDecodedUsableFrames",
        "yuvProcessingSameSizeFrames", "yuvProcessingCompatibleFrames",
        "frameCount", "yuvFusionLooksWorseHint", "yuvQualityDiagnosticHints", "processingNotes"
    )

// Classic-owned per-frame alignment/fusion fields to merge into locked frames array
private val classicYuvPerFrameAlignmentFields: Set<String> = setOf(
    "alignDx", "alignDy", "alignIntegerDx", "alignIntegerDy", "alignSubpixelDx", "alignSubpixelDy",
    "alignmentScore", "alignmentConfidence", "alignmentBackend", "alignmentUsedSubpixel",
    "alignmentFallbackUsed", "alignmentUsed", "globalWeight", "fusionUsed", "fusionSkipReason",
    "alignmentFailureReason"
)

private fun persistClassicYuvSuccess(
    jobDir: File,
    job: JSONObject,
    metadataPolicy: ReprocessMetadataPolicy,
    attempt: ProcessingAttempt? = null
) {
    // Merge per-frame alignment data into locked job's frames array for NORMAL
    if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
        mergeClassicFrameAlignmentIntoLockedJob(jobDir, job, attempt)
    }
    val keysToWrite = if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
        classicYuvSuccessOwnedKeys
    } else {
        classicYuvFinalProgressKeys
    }
    val failureKeysToClear = classicYuvFailureTerminalKeys

    val update: ((JSONObject) -> Unit) -> Unit = { mutate ->
        if (attempt != null) updateForProcessingAttempt(jobDir, attempt, mutate)
        else KeplerJobMetadata.update(jobDir, mutate)
    }
    update { current ->
        // Write owned keys that are present in job; remove absent owned keys
        keysToWrite.forEach { key ->
            if (job.has(key)) current.put(key, job.get(key))
            else current.remove(key)
        }
        // On NORMAL success, clear any stale failure-terminal keys
        if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
            failureKeysToClear.forEach { current.remove(it) }
        }
    }
}

private fun persistClassicYuvFailure(
    jobDir: File,
    job: JSONObject,
    metadataPolicy: ReprocessMetadataPolicy,
    attempt: ProcessingAttempt? = null
) {
    // Merge per-frame alignment data into locked job's frames array for NORMAL
    if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
        mergeClassicFrameAlignmentIntoLockedJob(jobDir, job, attempt)
    }
    val keysToWrite = if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
        classicYuvFailureOwnedKeys + classicYuvRunResultKeys
    } else {
        classicYuvFinalProgressKeys
    }

    val update: ((JSONObject) -> Unit) -> Unit = { mutate ->
        if (attempt != null) updateForProcessingAttempt(jobDir, attempt, mutate)
        else KeplerJobMetadata.update(jobDir, mutate)
    }
    update { current ->
        val currentAttemptClaimedOutput = attempt != null &&
            current.optBoolean("processingOutputCommitted", false) &&
            current.optString("processingArtifactClaimAttemptId") == attempt.id &&
            current.optString("processingAttemptId") == attempt.id &&
            current.optString("finalFile").isNotBlank() &&
            NoFollowFileSystem.resolveDirectChildResult(
                jobDir,
                current.optString("finalFile"),
                requireFile = true
            ) is NoFollowInspection.Present
        // Write owned keys that are present in job; remove absent owned keys
        keysToWrite.forEach { key ->
            if (job.has(key)) current.put(key, job.get(key))
            else current.remove(key)
        }
        if (metadataPolicy == ReprocessMetadataPolicy.NORMAL && currentAttemptClaimedOutput) {
            current.put("currentPipelineStage", "PIPELINE_COMPLETE_PARTIAL")
                .put("processStatus", "PIPELINE_COMPLETE_PARTIAL")
                .put("finalOutputAvailable", true)
                .put("galleryDisplayUnavailable", false)
        }
        // A new attempt may fail after its required final was durably claimed.
        // Preserve that exact current claim; only clear output paths for an
        // attempt which never claimed its required artifact.
        if (metadataPolicy == ReprocessMetadataPolicy.NORMAL && !currentAttemptClaimedOutput) {
            classicYuvStaleFinalOutputKeys.forEach { current.remove(it) }
        }
    }
}

private fun formatClassicFailureMessage(throwable: Throwable?, reason: String): String {
    if (throwable == null) return reason
    return when (throwable) {
        is OutOfMemoryError -> {
            if (throwable.message.isNullOrBlank()) {
                "OutOfMemoryError"
            } else {
                "OutOfMemoryError: ${throwable.message}"
            }
        }
        else -> {
            val message = throwable.message?.takeIf { it.isNotBlank() }
            if (message.isNullOrBlank()) {
                "${throwable.javaClass.simpleName}: $reason"
            } else {
                "${throwable.javaClass.simpleName}: $message"
            }
        }
    }
}

private fun saveClassicBitmap(
    bitmap: Bitmap,
    file: File,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    processingAttempt: ProcessingAttempt? = null
) {
    commitProcessingArtifact(
        finalFile = file,
        cancellation = cancellation,
        onSettlement = processingAttempt?.let { attempt ->
            processingArtifactSettlementObserver(file.parentFile ?: error("Artifact parent missing"), attempt)
        },
        writeTemp = { candidate ->
            FileOutputStream(candidate).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not save ${file.name}"
                }
                output.fd.sync()
            }
        },
        verifyFinal = { committed ->
            verifyPngArtifact(committed)
        },
        processingAttemptId = processingAttempt?.id,
        claimKey = processingAttempt?.let { "finalFile" }
    )
}

private fun luma(color: Int): Int = (
    0.299f * Color.red(color) +
        0.587f * Color.green(color) +
        0.114f * Color.blue(color)
    ).roundToInt()

private fun JSONObject.optionalFloat(name: String): Float? {
    if (!has(name) || isNull(name)) return null
    return optDouble(name, Double.NaN).takeIf { it.isFinite() }?.toFloat()
}
