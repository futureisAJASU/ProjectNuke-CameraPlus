package com.projectnuke.keplernightlab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

internal data class RawFusionMemoryEstimate(
    val argbBytes: Long,
    val shortBytes: Long,
    val nativeFallbackBytes: Long,
    val totalMb: Double,
    val riskLevel: String
)

internal data class PreparedRawFusionFrames(
    val inputs: List<RawFrameInput>,
    val metadata: List<JSONObject>,
    val requestedFrames: Int,
    val savedFrames: Int,
    val partialCapture: Boolean,
    val captureCompleteness: String
)

internal data class RawMergePreparation(
    val exposureScales: FloatArray,
    val frameWeights: FloatArray,
    val motionScores: DoubleArray,
    val gyroSamples: List<RawGyro>
)

internal data class RawFrameInput(val file: File, val meta: JSONObject)

internal data class RawReferenceSelection(val index: Int, val reason: String)

internal data class RawGyro(val timestamp: Long, val magnitude: Double)

internal fun estimateRawFusionMemory(pixelCount: Long): RawFusionMemoryEstimate {
    val argbBytes = pixelCount * 4L
    val shortBytes = pixelCount * 2L
    val nativeFallbackBytes = pixelCount * (4L + 4L + 2L)
    val totalMb = (argbBytes + shortBytes + nativeFallbackBytes).toDouble() /
        (1024.0 * 1024.0)
    val riskLevel = when {
        pixelCount >= HIGH_RES_RAW_MIN_PIXELS || totalMb >= 512.0 -> "HIGH"
        totalMb >= 256.0 -> "MEDIUM"
        else -> "LOW"
    }
    return RawFusionMemoryEstimate(
        argbBytes,
        shortBytes,
        nativeFallbackBytes,
        totalMb,
        riskLevel
    )
}

internal fun prepareRawFusionFrames(
    jobDir: File,
    job: JSONObject,
    frames: JSONArray,
    pixelCount: Int
): PreparedRawFusionFrames {
    val inputs = mutableListOf<RawFrameInput>()
    val metadata = mutableListOf<JSONObject>()
    for (i in 0 until frames.length()) {
        val frame = frames.getJSONObject(i)
        val rawFile = File(jobDir, frame.getString("raw16File"))
        if (rawFile.exists() && rawFile.length() >= pixelCount * 2L) {
            inputs += RawFrameInput(rawFile, frame)
            metadata += frame
        }
    }
    require(inputs.size >= MIN_RAW_FUSION_FRAMES) {
        "Not enough RAW frames for fusion: ${inputs.size}/$MIN_RAW_FUSION_FRAMES"
    }
    val requestedFrames = job.optInt("requestedFrames", inputs.size)
    val savedFrames = job.optInt("savedFrames", inputs.size)
    val partialCapture = job.optBoolean("partialCapture", false)
    return PreparedRawFusionFrames(
        inputs = inputs,
        metadata = metadata,
        requestedFrames = requestedFrames,
        savedFrames = savedFrames,
        partialCapture = partialCapture,
        captureCompleteness = job.optString(
            "captureCompleteness",
            if (partialCapture || inputs.size < requestedFrames) "PARTIAL" else "FULL"
        )
    )
}

internal fun prepareRawMergeInputs(
    jobDir: File,
    frameInputs: List<RawFrameInput>
): RawMergePreparation {
    val refExposure = frameInputs.first().meta
        .optDouble("exposureTimeNs", 1.0)
        .coerceAtLeast(1.0)
    val refIso = frameInputs.first().meta
        .optDouble("sensitivityIso", 100.0)
        .coerceAtLeast(1.0)
    val gyroSamples = readRawGyroSamples(File(jobDir, "gyro.csv"))
    val exposureScales = FloatArray(frameInputs.size)
    val frameWeights = FloatArray(frameInputs.size)
    val motionScores = DoubleArray(frameInputs.size)
    frameInputs.forEachIndexed { index, input ->
        val exposure = input.meta
            .optDouble("exposureTimeNs", refExposure)
            .coerceAtLeast(1.0)
        val iso = input.meta
            .optDouble("sensitivityIso", refIso)
            .coerceAtLeast(1.0)
        exposureScales[index] =
            ((refExposure * refIso) / (exposure * iso)).coerceIn(0.5, 2.0).toFloat()
        val motionScore = if (gyroSamples.isNotEmpty()) {
            motionNearRaw(gyroSamples, input.meta.optLong("timestampNs", 0L))
        } else {
            0.0
        }
        motionScores[index] = motionScore
        frameWeights[index] =
            (1.0 / (1.0 + motionScore * 8.0)).coerceIn(0.25, 1.0).toFloat()
    }
    return RawMergePreparation(exposureScales, frameWeights, motionScores, gyroSamples)
}

internal fun chooseRawReferenceFrame(
    frameInputs: List<RawFrameInput>,
    motionScores: DoubleArray,
    hasGyro: Boolean
): RawReferenceSelection {
    if (frameInputs.isEmpty()) return RawReferenceSelection(0, "no_frames_default")
    if (hasGyro && motionScores.size >= frameInputs.size) {
        val best = motionScores.indices.minByOrNull { motionScores[it] } ?: 0
        return RawReferenceSelection(best, "lowest_gyro_motion")
    }
    return RawReferenceSelection(frameInputs.size / 2, "middle_frame_no_gyro")
}

private fun readRawGyroSamples(file: File): List<RawGyro> {
    if (!file.exists()) return emptyList()
    return file.readLines().drop(1).mapNotNull { line ->
        val parts = line.split(',')
        val timestamp = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val x = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        val y = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        val z = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.0
        RawGyro(timestamp, abs(x) + abs(y) + abs(z))
    }
}

private fun motionNearRaw(samples: List<RawGyro>, timestamp: Long): Double {
    val window = 80_000_000L
    return samples
        .filter { abs(it.timestamp - timestamp) <= window }
        .map { it.magnitude }
        .average()
        .takeIf { !it.isNaN() }
        ?: 0.0
}
