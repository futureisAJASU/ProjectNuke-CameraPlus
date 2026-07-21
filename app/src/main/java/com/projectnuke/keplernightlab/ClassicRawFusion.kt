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

internal data class ClassicRawFusionResult(
    val success: Boolean,
    val mergedRawFile: File?,
    val alignmentFile: File?,
    val referenceIndex: Int,
    val referenceReason: String,
    val alignmentStatus: String,
    val debugMetadata: JSONObject?,
    val errorMessage: String?,
    val originalFailure: Throwable? = null
)

private data class ClassicRawFrame(
    val position: Int,
    val input: RawFrameInput,
    var proxy: RawProxy? = null,
    var dx: Int = 0,
    var dy: Int = 0,
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
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    onStatus: (String) -> Unit
): ClassicRawFusionResult {
    val startedAt = System.currentTimeMillis()
    return try {
        cancellation.throwIfCancelled()
        onStatus("Classic RAW fusion: loading frames...")
        val frames = preparedFrames.inputs.mapIndexed { index, input ->
            ClassicRawFrame(index, input)
        }.toMutableList()
        if (frames.size < MIN_RAW_FUSION_FRAMES) {
            error("Not enough enabled RAW frames to reprocess")
        }

        val alignStartedAt = System.currentTimeMillis()
        Log.i("KeplerRawPipeline", "ALIGN_STARTED jobDirAbsolutePath=${jobDir.absolutePath}")
        onStatus("RAW 프레임을 정렬하는 중입니다.")
        onStatus("Classic RAW fusion: building alignment proxies...")
        frames.toList().forEach { frame ->
            try {
                cancellation.throwIfCancelled()
                frame.proxy = buildRawProxy(frame.input.file, sensor, blackLevelEstimate, cancellation)
            } catch (oom: OutOfMemoryError) {
                throw oom
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                frame.skipReason = "${e.javaClass.simpleName}: ${e.message}"
                frames.remove(frame)
            }
        }
        if (frames.size < MIN_RAW_FUSION_FRAMES) {
            error("Not enough readable RAW frames for classic fusion")
        }

        val reference = selectRawReference(frames)
        reference.globalWeight = CLASSIC_RAW_REFERENCE_WEIGHT
        onStatus("Classic RAW fusion: selected reference frame ${reference.position + 1}")
        val refProxy = requireNotNull(reference.proxy)
        val refExposure = exposureProduct(reference.input.meta)
        var nativeAlignmentUsed = false
        var fallbackAlignmentCount = 0
        var lowConfidenceAlignmentCount = 0
        frames.forEachIndexed { index, frame ->
            cancellation.throwIfCancelled()
            onStatus("Classic RAW fusion: aligning frame ${index + 1}/${frames.size}...")
            frame.exposureScale = (refExposure / exposureProduct(frame.input.meta))
                .coerceIn(0.5f, 2.0f)
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
                frame.dx = (alignment.dx * refProxy.sampleStep).roundToInt()
                frame.dy = (alignment.dy * refProxy.sampleStep).roundToInt()
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
                frame.alignmentUsed = alignment.score <= CLASSIC_RAW_ALIGNMENT_REJECT_SCORE
                frame.globalWeight =
                    (1f - alignment.score / CLASSIC_RAW_ALIGNMENT_REJECT_SCORE)
                        .coerceIn(0.12f, 1f)
            }
            applyRawAlignmentToFrameJson(frame)
        }
        val nativeAlignMs = System.currentTimeMillis() - alignStartedAt
        Log.i("KeplerRawPipeline", "ALIGN_COMPLETE jobDirAbsolutePath=${jobDir.absolutePath} nativeAlignMs=$nativeAlignMs")

        val mergeStartedAt = System.currentTimeMillis()
        Log.i("KeplerRawPipeline", "MERGE_STARTED jobDirAbsolutePath=${jobDir.absolutePath}")
        onStatus("RAW 프레임을 병합하는 중입니다.")
        onStatus("Classic RAW fusion: merging RAW tiles...")
        val mergeStats = mergeClassicRawTiles(
            frames = frames,
            reference = reference,
            sensor = sensor,
            blackLevelEstimate = blackLevelEstimate,
            mergedRawFile = mergedRawFile,
            cancellation = cancellation,
            onStatus = onStatus
        )
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
        alignmentFile.writeText(debug.toString(2))
        cancellation.throwIfCancelled()
        // Debug preview generation is optional; cancellation is checked around it.
        writeRawFusionDebugPreviews(jobDir, reference, mergedRawFile, sensor, blackLevelEstimate, job, cancellation)
        cancellation.throwIfCancelled()

        job.put("rawFusionEngine", "classic_raw_v1")
            .put("rawFusionVersion", CLASSIC_RAW_FUSION_VERSION)
            .put("rawReferenceFrameIndex", reference.input.meta.optInt("index", reference.position))
            .put("rawReferenceFrameReason", selectRawReferenceReason(frames))
            .put("usedFrameCount", frames.size)
            .put("excludedFrameCount", countRawExcludedFrames(job))
            .put(
                "skippedFrameCount",
                ((job.optJSONArray("frames")?.length() ?: frames.size) -
                    countRawExcludedFrames(job) - frames.size).coerceAtLeast(0)
            )
            .put("rawGhostSuppressionUsed", true)
            .put("rawNoiseModelVersion", CLASSIC_RAW_NOISE_MODEL_VERSION)
            .put("shotCoeff", CLASSIC_RAW_SHOT_COEFF)
            .put("readNoiseCoeff", CLASSIC_RAW_READ_NOISE_COEFF)
            .put("rawOutlierRejectedRatio", mergeStats.rejectedRatio)
            .put("rawOutlierDownweightedRatio", mergeStats.downweightedRatio)
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
            .put("alignmentStatus", "CLASSIC_RAW_FUSION_V1_COMPLETE")
            .put("nativeRawMerge", false)
            .put("rawFusionNotes", "Classic RAW v1: downsampled green-channel alignment, tiled RAW-domain robust merge, signal-aware conservative outlier suppression.")

        ClassicRawFusionResult(
            success = true,
            mergedRawFile = mergedRawFile,
            alignmentFile = alignmentFile,
            referenceIndex = reference.position,
            referenceReason = selectRawReferenceReason(frames),
            alignmentStatus = "CLASSIC_RAW_FUSION_V1_COMPLETE",
            debugMetadata = debug,
            errorMessage = null
        )
    } catch (ce: CancellationException) {
        throw ce
    } catch (oom: OutOfMemoryError) {
        job.put("processStatus", "OOM_FAILED_KEEPING_CACHE")
            .put("rawFusionEngine", "classic_raw_v1")
            .put("processError", "OutOfMemoryError")
            .put("processedAt", System.currentTimeMillis())
        ClassicRawFusionResult(false, null, null, 0, "failed", "OOM_FAILED", null, "OutOfMemoryError", originalFailure = oom)
    } catch (e: Exception) {
        job.put("processStatus", "CLASSIC_RAW_FUSION_FAILED_KEEPING_CACHE")
            .put("rawFusionEngine", "classic_raw_v1")
            .put("processError", "${e.javaClass.simpleName}: ${e.message}")
            .put("processedAt", System.currentTimeMillis())
        ClassicRawFusionResult(
            false,
            null,
            null,
            0,
            "failed",
            "CLASSIC_RAW_FUSION_FAILED",
            null,
            "${e.javaClass.simpleName}: ${e.message}",
            originalFailure = e
        )
    }
}

private data class RawMergeStats(
    val rejectedPixels: Long,
    val comparedPixels: Long,
    val downweightedPixels: Long
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
    cancellation.throwIfCancelled()
    RandomAccessFile(file, "r").use { input ->
        var out = 0
        var y = 0
        while (y < sensor.height && out < luma.size) {
            if ((y and (step * 31)) == 0) cancellation.throwIfCancelled()
            readRawRow(input, sensor.width, y, row)
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
    }
    cancellation.throwIfCancelled()
    val mean = luma.fold(0L) { sum, value -> sum + (value.toInt() and 0xFF) }
        .toFloat() / luma.size.coerceAtLeast(1)
    return RawProxy(proxyWidth, proxyHeight, step, luma, mean)
}

private fun selectRawReference(frames: List<ClassicRawFrame>): ClassicRawFrame {
    val scored = frames.filter {
        it.input.meta.has("qualityScore") || it.input.meta.has("sharpnessScore")
    }
    return scored.maxWithOrNull(
        compareBy<ClassicRawFrame> {
            it.input.meta.optDouble("qualityScore", -1.0)
        }.thenBy {
            it.input.meta.optDouble("sharpnessScore", -1.0)
        }
    ) ?: frames[frames.size / 2]
}

private fun selectRawReferenceReason(frames: List<ClassicRawFrame>): String {
    return if (frames.any { it.input.meta.has("qualityScore") }) {
        "highest_quality_score"
    } else if (frames.any { it.input.meta.has("sharpnessScore") }) {
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
    cancellation: KeplerPipelineCancellation,
    onStatus: (String) -> Unit
): RawMergeStats {
    var rejected = 0L
    var downweighted = 0L
    var compared = 0L
    val whiteRange = (sensor.whiteLevel - sensor.blackLevel).coerceAtLeast(1)
    val frameInputs = linkedMapOf<ClassicRawFrame, RandomAccessFile>()
    val sourceRows = frames.associateWith { ShortArray(sensor.width) }
    val refRows = Array(CLASSIC_RAW_TILE_ROWS) { ShortArray(sensor.width) }
    val acc = FloatArray(sensor.width * CLASSIC_RAW_TILE_ROWS)
    val weights = FloatArray(sensor.width * CLASSIC_RAW_TILE_ROWS)
    val outRow = ByteArray(sensor.width * 2)

    try {
        frames.forEach { frame ->
            cancellation.throwIfCancelled()
            frameInputs[frame] = RandomAccessFile(frame.input.file, "r")
        }
        BufferedOutputStream(FileOutputStream(mergedRawFile)).use { output ->
            var tileTop = 0
            while (tileTop < sensor.height) {
                cancellation.throwIfCancelled()
                val tileRows = min(CLASSIC_RAW_TILE_ROWS, sensor.height - tileTop)
                acc.fill(0f, 0, sensor.width * tileRows)
                weights.fill(0f, 0, sensor.width * tileRows)
                for (row in 0 until tileRows) {
                    if ((row and 15) == 0) cancellation.throwIfCancelled()
                    readRawRow(
                        frameInputs.getValue(reference),
                        sensor.width,
                        tileTop + row,
                        refRows[row]
                    )
                }

                frames.forEachIndexed { frameIndex, frame ->
                    cancellation.throwIfCancelled()
                    onStatus("Classic RAW fusion: merging RAW tiles ${frameIndex + 1}/${frames.size}")
                    val raf = frameInputs.getValue(frame)
                    val rowBuffer = sourceRows.getValue(frame)
                    val globalWeight = frame.globalWeight
                    for (row in 0 until tileRows) {
                        if ((row and 15) == 0) cancellation.throwIfCancelled()
                        val y = tileTop + row
                        val sourceY = y + frame.dy
                        if (sourceY !in 0 until sensor.height) continue
                        readRawRow(raf, sensor.width, sourceY, rowBuffer)
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
                                if (normalizedResidual > 5.0f) {
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
                        val value = (acc[index] / weights[index].coerceAtLeast(0.001f))
                            .roundToInt()
                            .coerceIn(0, whiteRange)
                        outRow[out++] = (value and 0xFF).toByte()
                        outRow[out++] = ((value ushr 8) and 0xFF).toByte()
                    }
                    output.write(outRow)
                }
                tileTop += tileRows
            }
        }
    } finally {
        frameInputs.values.forEach { runCatching { it.close() } }
    }
    return RawMergeStats(rejected, compared, downweighted)
}

private fun readRawRow(input: RandomAccessFile, width: Int, y: Int, out: ShortArray) {
    input.seek(y.toLong() * width.toLong() * 2L)
    val bytes = ByteArray(width * 2)
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
        .put("rawAlignIntegerDx", frame.integerDx)
        .put("rawAlignIntegerDy", frame.integerDy)
        .put("rawAlignSubpixelDx", frame.subpixelDx.toDouble())
        .put("rawAlignSubpixelDy", frame.subpixelDy.toDouble())
        .put("rawAlignmentScore", frame.alignmentScore.toDouble())
        .put("rawAlignmentConfidence", frame.alignmentConfidence.toDouble())
        .put("rawAlignmentBackend", frame.alignmentBackend)
        .put("rawAlignmentUsedSubpixel", frame.alignmentUsedSubpixel)
        .put("rawAlignmentFallbackUsed", frame.alignmentFallbackUsed)
        .put("rawAlignmentUsed", frame.alignmentUsed)
        .put("rawGlobalWeight", frame.globalWeight.toDouble())
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
                .put("rawAlignIntegerDx", frame.integerDx)
                .put("rawAlignIntegerDy", frame.integerDy)
                .put("rawAlignSubpixelDx", frame.subpixelDx.toDouble())
                .put("rawAlignSubpixelDy", frame.subpixelDy.toDouble())
                .put("rawAlignmentScore", frame.alignmentScore.toDouble())
                .put("rawAlignmentConfidence", frame.alignmentConfidence.toDouble())
                .put("rawAlignmentBackend", frame.alignmentBackend)
                .put("rawAlignmentUsedSubpixel", frame.alignmentUsedSubpixel)
                .put("rawAlignmentFallbackUsed", frame.alignmentFallbackUsed)
                .put("rawGlobalWeight", frame.globalWeight.toDouble())
                .put("used", frame.skipReason == null)
                .put("skipReason", frame.skipReason ?: JSONObject.NULL)
        )
    }
    return JSONObject()
        .put("rawFusionEngine", "classic_raw_v1")
        .put("rawFusionVersion", CLASSIC_RAW_FUSION_VERSION)
        .put("rawReferenceFrameIndex", reference.input.meta.optInt("index", reference.position))
        .put("usedFrameCount", frames.size)
        .put("excludedFrameCount", countRawExcludedFrames(job))
        .put("skippedFrameCount", (preparedFrames.savedFrames - frames.size).coerceAtLeast(0))
        .put("rawGhostSuppressionUsed", true)
        .put("rawNoiseModelVersion", CLASSIC_RAW_NOISE_MODEL_VERSION)
        .put("shotCoeff", CLASSIC_RAW_SHOT_COEFF)
        .put("readNoiseCoeff", CLASSIC_RAW_READ_NOISE_COEFF)
        .put("rawOutlierRejectedRatio", mergeStats.rejectedRatio)
        .put("rawOutlierDownweightedRatio", mergeStats.downweightedRatio)
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
    try {
        cancellation.throwIfCancelled()
        val refProxy = requireNotNull(reference.proxy)
        refBitmap = rawProxyToBitmap(refProxy)
        saveClassicRawPng(refBitmap!!, File(jobDir, "raw_reference_preview.png"))
        val mergedProxy = buildRawProxy(
            mergedRawFile,
            sensor,
            BlackLevelEstimate(0, "merged_raw_zero"),
            cancellation
        )
        cancellation.throwIfCancelled()
        fusedBitmap = rawProxyToBitmap(mergedProxy)
        saveClassicRawPng(fusedBitmap!!, File(jobDir, "raw_fused_classic_v1_preview.png"))
        cancellation.throwIfCancelled()
        compare = Bitmap.createBitmap(fusedBitmap.width * 2, fusedBitmap.height, Bitmap.Config.ARGB_8888)
        referenceBitmap = rawProxyToBitmap(refProxy)
        val canvas = android.graphics.Canvas(compare!!)
        canvas.drawBitmap(referenceBitmap!!, 0f, 0f, null)
        canvas.drawBitmap(fusedBitmap!!, fusedBitmap!!.width.toFloat(), 0f, null)
        saveClassicRawPng(compare!!, File(jobDir, "raw_compare_reference_vs_fused.png"))
        cancellation.throwIfCancelled()
        job.put("rawReferencePreviewFile", "raw_reference_preview.png")
            .put("rawFusedPreviewFile", "raw_fused_classic_v1_preview.png")
            .put("rawComparePreviewFile", "raw_compare_reference_vs_fused.png")
            .put("rawDebugArtifactStatus", "COMPLETE")
            .remove("rawDebugArtifactError")
    } catch (ce: CancellationException) {
        throw ce
    } catch (error: Exception) {
        job.put("rawDebugArtifactStatus", "FAILED")
            .put("rawDebugArtifactError", "${error.javaClass.simpleName}: ${error.message}".take(240))
    } finally {
        refBitmap?.takeUnless { it.isRecycled }?.recycle()
        fusedBitmap?.takeUnless { it.isRecycled }?.recycle()
        referenceBitmap?.takeUnless { it.isRecycled }?.recycle()
        compare?.takeUnless { it.isRecycled }?.recycle()
    }
}

private fun rawProxyToBitmap(proxy: RawProxy): Bitmap {
    val pixels = IntArray(proxy.width * proxy.height) { index ->
        val v = proxy.luma[index].toInt() and 0xFF
        Color.rgb(v, v, v)
    }
    return Bitmap.createBitmap(pixels, proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
}

private fun saveClassicRawPng(bitmap: Bitmap, file: File) {
    FileOutputStream(file).use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            "Could not save ${file.name}"
        }
    }
}

private fun exposureProduct(meta: JSONObject): Float {
    val exposure = meta.optDouble("exposureTimeNs", 1.0).coerceAtLeast(1.0)
    val iso = meta.optDouble("sensitivityIso", 100.0).coerceAtLeast(1.0)
    return (exposure * iso).toFloat().coerceAtLeast(1f)
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
