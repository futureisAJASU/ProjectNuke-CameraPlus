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
    val comparedPixels: Long
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
    val sameSizeFrames: Int,
    val compatibleFrames: Int
)

internal fun processClassicYuvFusionJob(
    jobDir: File,
    requestedParams: ClassicYuvFusionParams? = null,
    externalFrameWeights: Map<Int, Float>? = null,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    metadataPolicy: ReprocessMetadataPolicy = ReprocessMetadataPolicy.NORMAL,
    onStatus: (String) -> Unit
): File {
    cancellation.throwIfCancelled()
    val processingStartedAt = System.currentTimeMillis()
    val jobFile = File(jobDir, "job.json")
    val job = JSONObject(jobFile.readText())
    val params = (requestedParams ?: loadClassicYuvFusionParams(job)).clamped()
    var merged: Bitmap? = null
    var finalBitmap: Bitmap? = null
    var preflight: ClassicYuvProcessingPreflight? = null
    var decodedUsableFrameCount = 0
    var sameSizeFrameCount = 0
    var compatibleFrameCount = 0
    var sameSizeFrameCountKnown = false
    var compatibleFrameCountKnown = false
    var frames: List<ClassicFrame> = emptyList()
    try {
        fun markStage(stage: String, status: String) {
            job.put("currentPipelineStage", stage)
                .put("processStatus", status)
                .put("processingStartedAt", processingStartedAt)
            KeplerJobMetadata.update(jobDir) { current ->
                current.put("currentPipelineStage", stage)
                    .put("processStatus", status)
                    .put("processingStartedAt", processingStartedAt)
            }
            Log.i("KeplerYuvPipeline", "$stage: $status")
            onStatus(status)
        }

        markStage("YUV_ALIGNING", "YUV 프레임을 정렬하는 중입니다.")
        cancellation.throwIfCancelled()
        val preflightSummary = buildClassicYuvProcessingPreflight(jobDir, job)
        cancellation.throwIfCancelled()
        preflight = preflightSummary
        job.put("yuvProcessingPreflight", preflightSummary.toJson())
        KeplerJobMetadata.update(jobDir) { current ->
            current.put("yuvProcessingPreflight", preflightSummary.toJson())
                .put("processingStartedAt", job.optLong("processingStartedAt"))
                .put("yuvProcessingPolicy", metadataPolicy.name)
        }
        cancellation.throwIfCancelled()
        val candidateFrames = loadClassicFrames(jobDir, job)
        cancellation.throwIfCancelled()
        val totalFrames = preflightSummary.totalFrames
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
                frameJson?.let { clearClassicFrameAlignmentOnDecodeFailure(it, "${e.javaClass.simpleName}: ${e.message}") }
                null
            }
        }
        decodedUsableFrameCount = frames.size
        if (frames.size < 2) {
            error(
                "Not enough enabled YUV frames to reprocess: " +
                    "enabled=${preflight.enabledFrames}, total=${preflight.totalFrames}, usable=${frames.size}"
            )
        }
        val reference = selectClassicReference(frames)
        onStatus("Classic YUV fusion: selected reference frame ${reference.jsonIndex + 1}")

        val referenceThumbnail = requireNotNull(reference.thumbnail)
        var nativeAlignmentUsed = false
        var fallbackAlignmentCount = 0
        var lowConfidenceAlignmentCount = 0
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
                    alignment.score.isFinite() &&
                        alignment.score <= params.alignmentRejectThreshold
            }
            updateAlignmentMetadata(job, frame, params)
        }

        val dimensions = decodeImageDimensions(reference.file)
        val sameSizeFrames = frames.filter { decodeImageDimensions(it.file) == dimensions }
        sameSizeFrameCount = sameSizeFrames.size
        sameSizeFrameCountKnown = true
        val acceptedFrames = sameSizeFrames.filter { it === reference || it.alignmentUsed }
        val compatibleFrames = if (acceptedFrames.size >= 2) acceptedFrames else sameSizeFrames.take(2)
        compatibleFrameCount = compatibleFrames.size
        compatibleFrameCountKnown = true
        if (compatibleFrames.size < 2) {
            error(
                "Not enough same-size YUV frames to fuse: " +
                    "compatible=${compatibleFrames.size}, sameSize=${sameSizeFrames.size}, decoded=${frames.size}"
            )
        }
        val activeReference = compatibleFrames.find { it === reference } ?: compatibleFrames.first()
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
        val mergeResult = mergeClassicFrames(
            frames = compatibleFrames,
            reference = activeReference,
            width = dimensions.first,
            height = dimensions.second,
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
        saveClassicBitmap(merged, averageFile)

        markStage("YUV_DENOISE_SHARPEN", "노이즈와 선명도를 보정하는 중입니다.")
        cancellation.throwIfCancelled()
        finalBitmap = finishClassicFusion(merged, params, cancellation)
        cancellation.throwIfCancelled()
        val lookDoneAt = System.currentTimeMillis()
        markStage("YUV_EXPORTING", "결과를 저장하는 중입니다.")
        val finalFile = File(jobDir, "sharpened_night_fusion.png")
        cancellation.throwIfCancelled()
        saveClassicBitmap(finalBitmap, finalFile)
        val exportDoneAt = System.currentTimeMillis()
        val processingTimeMs = System.currentTimeMillis() - processingStartedAt
        val excludedFrameCount = countExcludedFrames(job)
        val skippedFrameCount =
            (totalFrames - excludedFrameCount - compatibleFrames.size).coerceAtLeast(0)

        val rejectedRatio = if (mergeResult.comparedPixels > 0L) {
            mergeResult.rejectedPixels.toDouble() / mergeResult.comparedPixels
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
            .put("referenceFrameIndex", activeReference.jsonIndex)
            .put("yuvReferenceFrameIndex", activeReference.jsonIndex)
            .put("ghostSuppressionUsed", true)
            .put("ghostSuppressionEnabled", true)
            .put("ghostRejectedPixelRatio", rejectedRatio)
            .put("rejectedGhostSampleRatio", rejectedRatio)
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
            .put("outputWidth", dimensions.first)
            .put("outputHeight", dimensions.second)
            .put("frameCount", totalFrames)
            .put("yuvWidth", dimensions.first)
            .put("yuvHeight", dimensions.second)
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
        if (externalFrameWeights != null && externalFrameWeights.isNotEmpty()) {
            job.put("yuvExternalFrameWeightsUsed", true)
                .put("yuvExternalFrameWeightsTarget", "NON_REFERENCE_FRAMES_ONLY")
        }
        val debugMetadataFailure = runCatching {
            cancellation.throwIfCancelled()
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
        }.exceptionOrNull()
        cancellation.throwIfCancelled()
        generateFusionDebugArtifacts(
            jobDir = jobDir,
            job = job,
            referenceFile = activeReference.file,
            mergedBitmap = merged,
            fusedBitmap = finalBitmap,
            params = params
        )
        if (debugMetadataFailure != null) {
            job.put("debugArtifactStatus", "FAILED")
                .put(
                    "debugArtifactError",
                    "${debugMetadataFailure.javaClass.simpleName}: ${debugMetadataFailure.message}".take(240)
                )
        }
        cancellation.throwIfCancelled()
        File(jobDir, "yuv_debug.json").writeText(job.toString(2))
        persistClassicYuvSuccess(
            jobDir = jobDir,
            job = job,
            metadataPolicy = metadataPolicy
        )
        cancellation.throwIfCancelled()
        onStatus("처리가 완료되었습니다.")
        return finalFile
    } catch (oom: OutOfMemoryError) {
        val failurePreflight = preflight ?: buildClassicYuvProcessingPreflight(jobDir, job)
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
            preflight = failurePreflight
            , metadataPolicy = metadataPolicy
        )
        throw IllegalStateException("Classic YUV fusion failed: OutOfMemoryError; cache kept", oom)
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        val failurePreflight = preflight ?: buildClassicYuvProcessingPreflight(jobDir, job)
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
            preflight = failurePreflight
            , metadataPolicy = metadataPolicy
        )
        throw e
    } finally {
        finalBitmap?.recycle()
        merged?.recycle()
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
            val file = File(jobDir, fileName)
            if (fileName.isBlank() || !file.isFile) return@repeat
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
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unreadable frame: ${file.name}" }
    var sampleSize = 1
    while (
        max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) >
        CLASSIC_FUSION_ALIGNMENT_MAX_DIMENSION
    ) {
        sampleSize *= 2
    }
    val bitmap = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    ) ?: error("Could not decode frame: ${file.name}")
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
    } finally {
        bitmap.recycle()
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
    try {
        frames.forEach { frame ->
            cancellation.throwIfCancelled()
            decoders[frame] = BitmapRegionDecoder.newInstance(frame.file.absolutePath, false)
        }
        output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var tileTop = 0
        while (tileTop < height) {
            cancellation.throwIfCancelled()
            val tileBottom = min(height, tileTop + CLASSIC_FUSION_TILE_ROWS)
            val tileHeight = tileBottom - tileTop
            val pixelCount = width * tileHeight
            val referenceBitmap = decoders.getValue(reference).decodeRegion(
                Rect(0, tileTop, width, tileBottom),
                BitmapFactory.Options()
            ) ?: error("Could not decode reference tile")
            val referencePixels = try {
                IntArray(pixelCount).also {
                    referenceBitmap.getPixels(it, 0, width, 0, 0, width, tileHeight)
                }
            } finally {
                referenceBitmap.recycle()
            }

            val sumR = FloatArray(pixelCount)
            val sumG = FloatArray(pixelCount)
            val sumB = FloatArray(pixelCount)
            val sumW = FloatArray(pixelCount)
            for (pixel in 0 until pixelCount) {
                val color = referencePixels[pixel]
                sumR[pixel] = Color.red(color) * params.referenceWeight
                sumG[pixel] = Color.green(color) * params.referenceWeight
                sumB[pixel] = Color.blue(color) * params.referenceWeight
                sumW[pixel] = params.referenceWeight
            }

            frames.forEachIndexed { frameIndex, frame ->
                cancellation.throwIfCancelled()
                if (frame === reference) return@forEachIndexed
                if (reportedMergeFrames.add(frame.jsonIndex)) {
                    onStatus("Classic YUV fusion: merging frame ${frameIndex + 1}/${frames.size}...")
                }
                val sourceLeft = max(0, frame.alignDx)
                val sourceTop = max(0, tileTop + frame.alignDy)
                val sourceRight = min(width, width + frame.alignDx)
                val sourceBottom = min(height, tileBottom + frame.alignDy)
                if (sourceRight <= sourceLeft || sourceBottom <= sourceTop) return@forEachIndexed
                val region = Rect(sourceLeft, sourceTop, sourceRight, sourceBottom)
                val frameBitmap = decoders.getValue(frame).decodeRegion(region, BitmapFactory.Options())
                    ?: return@forEachIndexed
                val (frameWidth, frameHeight, framePixels) = try {
                    val frameWidth = frameBitmap.width
                    val frameHeight = frameBitmap.height
                    val framePixels = IntArray(frameWidth * frameHeight)
                    frameBitmap.getPixels(
                        framePixels, 0, frameWidth, 0, 0, frameWidth, frameHeight
                    )
                    Triple(frameWidth, frameHeight, framePixels)
                } finally {
                    frameBitmap.recycle()
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
                        val localWeight = ghost * alignmentWeight * externalWeight
                        comparedPixels++
                        if (ghost < 0.25f) rejectedPixels++
                        sumR[outputIndex] += Color.red(color) * gain * localWeight
                        sumG[outputIndex] += Color.green(color) * gain * localWeight
                        sumB[outputIndex] += Color.blue(color) * gain * localWeight
                        sumW[outputIndex] += localWeight
                    }
                }
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
        return MergeResult(requireNotNull(output), rejectedPixels, comparedPixels)
    } finally {
        decoders.values.forEach { it.recycle() }
        if (!outputReturned) output?.recycle()
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
    if (!score.isFinite()) return 0.10f
    return (1f - score / rejectThreshold).coerceIn(0.12f, 1f)
}

private fun ghostWeight(lumaDifference: Float, params: ClassicYuvFusionParams): Float {
    if (lumaDifference <= params.ghostThreshold) return 1f
    val normalized = (
        (lumaDifference - params.ghostThreshold) /
            (255f - params.ghostThreshold)
        ).coerceIn(0f, 1f)
    return (1f - normalized).pow(3).coerceAtLeast(params.ghostWeight)
}

private fun finishClassicFusion(
    source: Bitmap,
    params: ClassicYuvFusionParams,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation
): Bitmap {
    val width = source.width
    val height = source.height
    val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    fun tone(value: Float): Int {
        val normalized = (value / 255f).coerceIn(0f, 1f)
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
                val sharpenedLuma = centerLuma +
                    detail * params.localContrastAmount +
                    detail * params.sharpenAmount
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
    return outputBitmap
    } catch (t: Throwable) {
        outputBitmap.recycle()
        throw t
    }
}

/** Resets all Classic-owned per-frame alignment/fusion fields before a new processing run. */
private fun resetClassicFrameAlignmentFields(frameJson: JSONObject) {
    classicYuvPerFrameAlignmentFields.forEach { field ->
        frameJson.remove(field)
    }
}

/** Clears stale alignment data on decode failure and sets the current failure reason. */
private fun clearClassicFrameAlignmentOnDecodeFailure(frameJson: JSONObject, reason: String) {
    classicYuvPerFrameAlignmentFields.forEach { field ->
        frameJson.remove(field)
    }
    frameJson.put("alignmentUsed", false)
        .put("fusionUsed", false)
        .put("alignmentFailureReason", reason)
        .put("fusionSkipReason", "DECODE_FAILED")
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
    localJob: JSONObject
) {
    // Build a map of local frames by stable identity: (index, file) -> frame JSON from local job
    val localFrames = localJob.optJSONArray("frames") ?: return
    val localFrameMap = mutableMapOf<Pair<Int, String>, JSONObject>()
    val localFrameByFile = mutableMapOf<String, JSONObject>()
    repeat(localFrames.length()) { index ->
        val frameJson = localFrames.optJSONObject(index) ?: return@repeat
        val file = frameJson.optString("file")
        val idx = frameJson.optInt("index", index)
        if (file.isNotBlank()) {
            localFrameMap[idx to file] = frameJson
            localFrameByFile[file] = frameJson
        }
    }

    // Merge into locked frames by stable identity, inside the update lock
    KeplerJobMetadata.update(jobDir) { current ->
        val lockedFrames = current.optJSONArray("frames") ?: return@update
        repeat(lockedFrames.length()) { index ->
            val lockedFrame = lockedFrames.optJSONObject(index) ?: return@repeat
            val file = lockedFrame.optString("file")
            val idx = lockedFrame.optInt("index", index)
            val key = idx to file
            if (file.isBlank()) return@repeat

            val localFrame = localFrameMap[key] ?: localFrameByFile[file]
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
            fileName.isBlank() || !File(jobDir, fileName).isFile -> "MISSING_FILE"
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
    File(jobDir, "fusion_debug.json").writeText(debug.toString(2))
    File(jobDir, "yuv_debug.json").writeText(debug.toString(2))
    job.put("fusionDebugFile", "fusion_debug.json")
        .put("yuvDebugFile", "yuv_debug.json")
        .put("fusionAlignmentSummary", alignments)
}

private fun generateFusionDebugArtifacts(
    jobDir: File,
    job: JSONObject,
    referenceFile: File,
    mergedBitmap: Bitmap,
    fusedBitmap: Bitmap,
    params: ClassicYuvFusionParams
) {
    try {
        val referenceOutput = File(jobDir, "reference_frame.png")
        referenceFile.copyTo(referenceOutput, overwrite = true)
        val yuvReferenceOutput = File(jobDir, "yuv_reference_preview.png")
        referenceFile.copyTo(yuvReferenceOutput, overwrite = true)
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
        val yuvNoSharpenPreview = finishClassicFusion(
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
        } finally {
            comparison?.recycle()
            if (fusedPreview != null && fusedPreview !== fusedBitmap) fusedPreview.recycle()
            yuvBeforeDenoisePreview.recycle()
            yuvNoSharpenPreview.recycle()
            yuvFinalPreview.recycle()
            referencePreview.recycle()
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
    } catch (oom: OutOfMemoryError) {
        job.put("debugArtifactStatus", "FAILED")
            .put("debugArtifactError", "OutOfMemoryError")
    } catch (e: Exception) {
        job.put("debugArtifactStatus", "FAILED")
            .put("debugArtifactError", "${e.javaClass.simpleName}: ${e.message}".take(240))
    }
}

private fun decodeDebugPreview(file: File): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unreadable debug image" }
    var sampleSize = 1
    while (
        max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) >
        CLASSIC_FUSION_DEBUG_MAX_DIMENSION
    ) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
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
    BitmapFactory.decodeFile(file.absolutePath, options)
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
        val file = File(jobDir, fileName)
        if (!file.isFile) {
            missingFrameFiles++
            return@repeat
        }
        existingFrameFiles++
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
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
    val resolvedSameSizeFrameCount =
        if (sameSizeFrameCountKnown) sameSizeFrameCount else decodedUsableFrameCount
    val resolvedCompatibleFrameCount =
        if (compatibleFrameCountKnown) compatibleFrameCount else resolvedSameSizeFrameCount
    return ClassicYuvProcessingFailureCounts(
        totalFrames = preflight.totalFrames,
        enabledFrames = preflight.enabledFrames,
        decodedUsableFrames = decodedUsableFrameCount,
        sameSizeFrames = resolvedSameSizeFrameCount,
        compatibleFrames = resolvedCompatibleFrameCount
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
    metadataPolicy: ReprocessMetadataPolicy = ReprocessMetadataPolicy.NORMAL
) {
    runCatching {
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
            .put("processedAt", System.currentTimeMillis())
        preflight?.let { job.put("yuvProcessingPreflight", it.toJson()) }
        failureCounts?.let {
            job.put("yuvProcessingTotalFrames", it.totalFrames)
                .put("yuvProcessingEnabledFrames", it.enabledFrames)
                .put("yuvProcessingDecodedUsableFrames", it.decodedUsableFrames)
                .put("yuvProcessingSameSizeFrames", it.sameSizeFrames)
                .put("yuvProcessingCompatibleFrames", it.compatibleFrames)
        }
        persistClassicYuvFailure(
            jobDir = jobFile.parentFile ?: error("Job directory missing"),
            job = job,
            metadataPolicy = metadataPolicy
        )
    }
}

private const val CLASSIC_YUV_ACTIVE_PROGRESS_KEYS =
    "currentPipelineStage,processStatus,processingStartedAt,yuvProcessingPreflight,yuvProcessingPolicy," +
        "yuvProcessingTotalFrames,yuvProcessingEnabledFrames,yuvProcessingDecodedUsableFrames," +
        "yuvProcessingSameSizeFrames,yuvProcessingCompatibleFrames,timing,processingTimeMs," +
        "debugArtifactStatus,debugArtifactError,fusionDebugFile,yuvDebugFile,fusionAlignmentSummary"

private val classicYuvActiveProgressKeys: Set<String> = CLASSIC_YUV_ACTIVE_PROGRESS_KEYS.split(',').toSet()

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
    "currentPipelineStage", "processStatus", "pipelineFailed", "pipelineFailureStatusCode",
    "pipelineFailureSource", "pipelineFailureType", "pipelineFailureMessage", "pipelineFailureStackTrace",
    "processFailureReason", "fusionEngine", "fusionVersion", "processedAt",
    "yuvProcessingPreflight", "yuvProcessingPolicy", "yuvProcessingTotalFrames", "yuvProcessingEnabledFrames",
    "yuvProcessingDecodedUsableFrames", "yuvProcessingSameSizeFrames", "yuvProcessingCompatibleFrames",
    "processingStartedAt", "debugArtifactStatus", "debugArtifactError", "timing", "processingTimeMs"
)

// Keys that are opposite-type: failure keys to clear on success, success keys to clear on failure
private val classicYuvFailureTerminalKeys: Set<String> = setOf(
    "pipelineFailed", "pipelineFailureStatusCode", "pipelineFailureSource",
    "pipelineFailureType", "pipelineFailureMessage", "pipelineFailureStackTrace", "processFailureReason"
)

// Narrow set of stale final/output/gallery fields to clear on NORMAL failure.
// Preserves identity, diagnostic, and current-run fields.
private val classicYuvStaleFinalOutputKeys: Set<String> = setOf(
    "userCanMoveDevice", "yuvFusionVersion", "fusionParamsVersion", "fusionPresetName",
    "fusionParams", "nativeAlignmentAvailable", "nativeAlignmentUsed", "alignmentVersion",
    "yuvAlignVersion", "yuvMergeVersion", "yuvDenoiseVersion", "yuvDetailVersion", "yuvSharpenVersion",
    "yuvLookVersion", "fallbackAlignmentCount", "lowConfidenceAlignmentCount", "usedFrameCount",
    "acceptedFrameCount", "rejectedFrameCount", "excludedFrameCount", "skippedFrameCount",
    "referenceFrameIndex", "yuvReferenceFrameIndex", "ghostSuppressionUsed", "ghostSuppressionEnabled",
    "ghostRejectedPixelRatio", "rejectedGhostSampleRatio", "averageColorFile", "finalNightFusionFile",
    "finalFile", "finalOutputSource", "galleryDisplayFile", "galleryThumbnailFile", "galleryDisplaySource",
    "isDebugPreviewUsedAsFinal", "yuvFusionLooksWorseHint", "yuvQualityDiagnosticHints",
    "outputWidth", "outputHeight", "frameCount", "yuvWidth", "yuvHeight",
    "lumaDenoiseStrength", "chromaDenoiseStrength", "lowLightChromaBoost", "adaptiveSharpenUsed",
    "blackPoint", "contrastCurve", "saturationBoost", "vibranceBoost", "localContrastAmount",
    "processingNotes",
    "yuvExternalFrameWeightsUsed", "yuvExternalFrameWeightsTarget",
    "referenceFrameDebugFile", "yuvReferencePreviewFile", "fusedClassicDebugFile", "yuvFusedPreviewFile",
    "yuvFusedBeforeDenoisePreviewFile", "yuvFusedAfterDenoiseNoSharpenPreviewFile", "yuvFinalPreviewFile",
    "fusedClassicPresetFile", "comparisonDebugFile", "yuvComparePreviewFile",
    "yuvCompareReferenceVsFinalFile"
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
    metadataPolicy: ReprocessMetadataPolicy
) {
    // Merge per-frame alignment data into locked job's frames array for NORMAL
    if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
        mergeClassicFrameAlignmentIntoLockedJob(jobDir, job)
    }
    val keysToWrite = if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
        classicYuvSuccessOwnedKeys
    } else {
        classicYuvFinalProgressKeys
    }
    val failureKeysToClear = classicYuvFailureTerminalKeys
    
    KeplerJobMetadata.update(jobDir) { current ->
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
    metadataPolicy: ReprocessMetadataPolicy
) {
    // Merge per-frame alignment data into locked job's frames array for NORMAL
    if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
        mergeClassicFrameAlignmentIntoLockedJob(jobDir, job)
    }
    val keysToWrite = if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
        classicYuvFailureOwnedKeys
    } else {
        classicYuvFinalProgressKeys
    }
    
    KeplerJobMetadata.update(jobDir) { current ->
        // Write owned keys that are present in job; remove absent owned keys
        keysToWrite.forEach { key ->
            if (job.has(key)) current.put(key, job.get(key))
            else current.remove(key)
        }
        // On NORMAL failure, clear only stale final/gallery/output keys; KEEP failure diagnostics
        if (metadataPolicy == ReprocessMetadataPolicy.NORMAL) {
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

private fun saveClassicBitmap(bitmap: Bitmap, file: File) {
    FileOutputStream(file).use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            "Could not save ${file.name}"
        }
    }
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
