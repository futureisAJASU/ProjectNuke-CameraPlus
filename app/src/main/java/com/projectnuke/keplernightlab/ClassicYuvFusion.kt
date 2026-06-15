package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Rect
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private const val CLASSIC_FUSION_VERSION = "1.0"
private const val CLASSIC_FUSION_ALIGNMENT_MAX_DIMENSION = 512
private const val CLASSIC_FUSION_ALIGNMENT_SEARCH_RADIUS = 24
private const val CLASSIC_FUSION_ALIGNMENT_BAD_SCORE = 0.20f
private const val CLASSIC_FUSION_TILE_ROWS = 256
private const val CLASSIC_FUSION_REFERENCE_WEIGHT = 1.5f
private const val CLASSIC_FUSION_SHARPEN_AMOUNT = 0.28f
private const val CLASSIC_FUSION_DENOISE_STRENGTH = 0.24f
private const val CLASSIC_FUSION_GHOST_THRESHOLD = 34f

private data class ClassicFrame(
    val jsonIndex: Int,
    val file: File,
    val qualityScore: Float?,
    val sharpnessScore: Float?,
    var thumbnail: LumaThumbnail? = null,
    var alignDx: Int = 0,
    var alignDy: Int = 0,
    var alignmentScore: Float = 0f,
    var alignmentUsed: Boolean = true
)

private data class LumaThumbnail(
    val width: Int,
    val height: Int,
    val sampleSize: Int,
    val luma: ByteArray,
    val mean: Float
)

private data class AlignmentResult(
    val dx: Int,
    val dy: Int,
    val score: Float
)

private data class MergeResult(
    val bitmap: Bitmap,
    val rejectedPixels: Long,
    val comparedPixels: Long
)

internal fun processClassicYuvFusionJob(
    jobDir: File,
    onStatus: (String) -> Unit
): File {
    val jobFile = File(jobDir, "job.json")
    val job = JSONObject(jobFile.readText())
    var merged: Bitmap? = null
    var finalBitmap: Bitmap? = null
    try {
        onStatus("Classic YUV fusion: loading frames...")
        val candidateFrames = loadClassicFrames(jobDir, job)
        val totalFrames = job.optJSONArray("frames")?.length() ?: 0
        val frames = candidateFrames.mapNotNull { frame ->
            try {
                frame.thumbnail = decodeLumaThumbnail(frame.file)
                frame
            } catch (oom: OutOfMemoryError) {
                throw oom
            } catch (e: Exception) {
                val frameJson = job.optJSONArray("frames")?.optJSONObject(frame.jsonIndex)
                frameJson?.put("alignmentUsed", false)
                    ?.put("alignmentFailureReason", "${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
        if (frames.size < 2) error("Not enough enabled YUV frames to reprocess")
        val reference = selectClassicReference(frames)
        onStatus("Classic YUV fusion: selected reference frame ${reference.jsonIndex + 1}")

        val referenceThumbnail = requireNotNull(reference.thumbnail)
        frames.forEachIndexed { index, frame ->
            onStatus("Classic YUV fusion: aligning frame ${index + 1}/${frames.size}...")
            if (frame === reference) {
                frame.alignmentScore = 0f
                frame.alignmentUsed = true
            } else {
                val alignment = estimateTranslation(referenceThumbnail, requireNotNull(frame.thumbnail))
                val fullScaleX = frame.thumbnail!!.sampleSize.toFloat()
                val fullScaleY = frame.thumbnail!!.sampleSize.toFloat()
                frame.alignDx = (alignment.dx * fullScaleX).roundToInt()
                frame.alignDy = (alignment.dy * fullScaleY).roundToInt()
                frame.alignmentScore = alignment.score
                frame.alignmentUsed =
                    alignment.score.isFinite() &&
                        alignment.score <= CLASSIC_FUSION_ALIGNMENT_BAD_SCORE
            }
            updateAlignmentMetadata(job, frame)
        }

        val dimensions = decodeImageDimensions(reference.file)
        val compatibleFrames = frames.filter { decodeImageDimensions(it.file) == dimensions }
        if (compatibleFrames.size < 2) error("Not enough same-size YUV frames to fuse")
        val activeReference = compatibleFrames.find { it === reference } ?: compatibleFrames.first()

        val mergeResult = mergeClassicFrames(
            frames = compatibleFrames,
            reference = activeReference,
            width = dimensions.first,
            height = dimensions.second,
            onStatus = onStatus
        )
        merged = mergeResult.bitmap
        val averageFile = File(jobDir, "average_color_rotated.png")
        saveClassicBitmap(merged, averageFile)

        onStatus("Classic YUV fusion: tone/sharpen...")
        finalBitmap = finishClassicFusion(merged)
        val finalFile = File(jobDir, "sharpened_night_fusion.png")
        saveClassicBitmap(finalBitmap, finalFile)

        val rejectedRatio = if (mergeResult.comparedPixels > 0L) {
            mergeResult.rejectedPixels.toDouble() / mergeResult.comparedPixels
        } else {
            0.0
        }
        job.put("jobType", job.optString("jobType", "YUV_BURST_COLOR"))
            .put("processStatus", "CLASSIC_YUV_FUSION_V1_COMPLETE")
            .put("fusionEngine", "classic_yuv_v1")
            .put("fusionVersion", CLASSIC_FUSION_VERSION)
            .put("usedFrameCount", compatibleFrames.size)
            .put("excludedFrameCount", totalFrames - compatibleFrames.size)
            .put("referenceFrameIndex", activeReference.jsonIndex)
            .put("ghostSuppressionUsed", true)
            .put("ghostRejectedPixelRatio", rejectedRatio)
            .put("averageColorFile", averageFile.name)
            .put("finalNightFusionFile", finalFile.name)
            .put("finalFile", finalFile.name)
            .put("processedAt", System.currentTimeMillis())
            .put(
                "processingNotes",
                "Classic YUV Fusion v1: integer translation alignment, robust local weights, " +
                    "ghost suppression, mild chroma denoise, tone, and sharpen."
            )
        jobFile.writeText(job.toString(2))
        onStatus("Classic YUV fusion complete")
        return finalFile
    } catch (oom: OutOfMemoryError) {
        recordClassicFailure(jobFile, job, "OOM_FAILED_KEEPING_CACHE", "OutOfMemoryError")
        throw IllegalStateException("Classic YUV fusion failed: OutOfMemoryError; cache kept", oom)
    } catch (e: Exception) {
        recordClassicFailure(
            jobFile,
            job,
            "CLASSIC_YUV_FUSION_V1_FAILED_KEEPING_CACHE",
            "${e.javaClass.simpleName}: ${e.message}"
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
    return AlignmentResult(bestDx, bestDy, bestScore)
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
    onStatus: (String) -> Unit
): MergeResult {
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val decoders = frames.associateWith {
        BitmapRegionDecoder.newInstance(it.file.absolutePath, false)
    }
    var rejectedPixels = 0L
    var comparedPixels = 0L
    val reportedMergeFrames = mutableSetOf<Int>()
    try {
        var tileTop = 0
        while (tileTop < height) {
            val tileBottom = min(height, tileTop + CLASSIC_FUSION_TILE_ROWS)
            val tileHeight = tileBottom - tileTop
            val pixelCount = width * tileHeight
            val referenceBitmap = decoders.getValue(reference).decodeRegion(
                Rect(0, tileTop, width, tileBottom),
                BitmapFactory.Options()
            ) ?: error("Could not decode reference tile")
            val referencePixels = IntArray(pixelCount)
            referenceBitmap.getPixels(referencePixels, 0, width, 0, 0, width, tileHeight)
            referenceBitmap.recycle()

            val sumR = FloatArray(pixelCount)
            val sumG = FloatArray(pixelCount)
            val sumB = FloatArray(pixelCount)
            val sumW = FloatArray(pixelCount)
            for (pixel in 0 until pixelCount) {
                val color = referencePixels[pixel]
                sumR[pixel] = Color.red(color) * CLASSIC_FUSION_REFERENCE_WEIGHT
                sumG[pixel] = Color.green(color) * CLASSIC_FUSION_REFERENCE_WEIGHT
                sumB[pixel] = Color.blue(color) * CLASSIC_FUSION_REFERENCE_WEIGHT
                sumW[pixel] = CLASSIC_FUSION_REFERENCE_WEIGHT
            }

            frames.forEachIndexed { frameIndex, frame ->
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
                val frameWidth = frameBitmap.width
                val frameHeight = frameBitmap.height
                val framePixels = IntArray(frameWidth * frameHeight)
                frameBitmap.getPixels(
                    framePixels, 0, frameWidth, 0, 0, frameWidth, frameHeight
                )
                frameBitmap.recycle()

                val alignmentWeight = alignmentWeight(frame.alignmentScore)
                val gain = (
                    requireNotNull(reference.thumbnail).mean /
                        requireNotNull(frame.thumbnail).mean.coerceAtLeast(1f)
                    ).coerceIn(0.80f, 1.25f)
                val outputStartX = max(0, -frame.alignDx)
                val outputEndX = min(width, width - frame.alignDx)
                val outputStartY = max(tileTop, -frame.alignDy)
                val outputEndY = min(tileBottom, height - frame.alignDy)
                for (y in outputStartY until outputEndY) {
                    val tileY = y - tileTop
                    val sourceY = y + frame.alignDy - sourceTop
                    for (x in outputStartX until outputEndX) {
                        val outputIndex = tileY * width + x
                        val sourceX = x + frame.alignDx - sourceLeft
                        val color = framePixels[sourceY * frameWidth + sourceX]
                        val refColor = referencePixels[outputIndex]
                        val adjustedLuma = luma(color) * gain
                        val difference = abs(adjustedLuma - luma(refColor))
                        val localWeight = ghostWeight(difference) * alignmentWeight
                        comparedPixels++
                        if (localWeight < alignmentWeight * 0.25f) rejectedPixels++
                        sumR[outputIndex] += Color.red(color) * gain * localWeight
                        sumG[outputIndex] += Color.green(color) * gain * localWeight
                        sumB[outputIndex] += Color.blue(color) * gain * localWeight
                        sumW[outputIndex] += localWeight
                    }
                }
            }

            val outputPixels = IntArray(pixelCount)
            for (pixel in 0 until pixelCount) {
                val weight = sumW[pixel].coerceAtLeast(0.001f)
                outputPixels[pixel] = Color.rgb(
                    (sumR[pixel] / weight).roundToInt().coerceIn(0, 255),
                    (sumG[pixel] / weight).roundToInt().coerceIn(0, 255),
                    (sumB[pixel] / weight).roundToInt().coerceIn(0, 255)
                )
            }
            output.setPixels(outputPixels, 0, width, 0, tileTop, width, tileHeight)
            tileTop = tileBottom
        }
    } finally {
        decoders.values.forEach { it.recycle() }
    }
    return MergeResult(output, rejectedPixels, comparedPixels)
}

private fun alignmentWeight(score: Float): Float {
    if (!score.isFinite()) return 0.10f
    return (1f - score / CLASSIC_FUSION_ALIGNMENT_BAD_SCORE).coerceIn(0.12f, 1f)
}

private fun ghostWeight(lumaDifference: Float): Float {
    if (lumaDifference <= CLASSIC_FUSION_GHOST_THRESHOLD) return 1f
    val normalized = (
        (lumaDifference - CLASSIC_FUSION_GHOST_THRESHOLD) /
            (255f - CLASSIC_FUSION_GHOST_THRESHOLD)
        ).coerceIn(0f, 1f)
    return (1f - normalized).pow(3).coerceAtLeast(0.03f)
}

private fun finishClassicFusion(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    fun tone(value: Float): Int {
        val normalized = (value / 255f).coerceIn(0f, 1f)
        val curved = normalized.pow(0.94f)
        return (((curved - 0.5f) * 1.025f + 0.5f) * 255f).roundToInt().coerceIn(0, 255)
    }
    var tileTop = 0
    while (tileTop < height) {
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
                val sharpenedLuma =
                    centerLuma + (centerLuma - localLuma) * CLASSIC_FUSION_SHARPEN_AMOUNT
                val centerChromaR = Color.red(center) - centerLuma
                val centerChromaB = Color.blue(center) - centerLuma
                val chromaR = centerChromaR * (1f - CLASSIC_FUSION_DENOISE_STRENGTH) +
                    chromaRSum / 9f * CLASSIC_FUSION_DENOISE_STRENGTH
                val chromaB = centerChromaB * (1f - CLASSIC_FUSION_DENOISE_STRENGTH) +
                    chromaBSum / 9f * CLASSIC_FUSION_DENOISE_STRENGTH
                val tonedLuma = tone(sharpenedLuma).toFloat()
                outputPixels[(y - tileTop) * width + x] = Color.rgb(
                    (tonedLuma + chromaR).roundToInt().coerceIn(0, 255),
                    (tonedLuma - 0.5f * chromaR - 0.5f * chromaB).roundToInt().coerceIn(0, 255),
                    (tonedLuma + chromaB).roundToInt().coerceIn(0, 255)
                )
            }
        }
        outputBitmap.setPixels(
            outputPixels, 0, width, 0, tileTop, width, tileBottom - tileTop
        )
        tileTop = tileBottom
    }
    return outputBitmap
}

private fun updateAlignmentMetadata(job: JSONObject, frame: ClassicFrame) {
    val frameJson = job.optJSONArray("frames")?.optJSONObject(frame.jsonIndex) ?: return
    frameJson.put("alignDx", frame.alignDx)
        .put("alignDy", frame.alignDy)
        .put("alignmentScore", frame.alignmentScore.toDouble())
        .put("alignmentUsed", frame.alignmentUsed)
}

private fun decodeImageDimensions(file: File): Pair<Int, Int> {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    require(options.outWidth > 0 && options.outHeight > 0) { "Unreadable frame: ${file.name}" }
    return options.outWidth to options.outHeight
}

private fun recordClassicFailure(
    jobFile: File,
    job: JSONObject,
    status: String,
    reason: String
) {
    runCatching {
        job.put("processStatus", status)
            .put("fusionEngine", "classic_yuv_v1")
            .put("fusionVersion", CLASSIC_FUSION_VERSION)
            .put("processFailureReason", reason)
            .put("processedAt", System.currentTimeMillis())
        jobFile.writeText(job.toString(2))
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
