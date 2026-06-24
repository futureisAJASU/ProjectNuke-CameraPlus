package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val QUALITY_SCORING_VERSION = "v5b_native_cpp"
private const val QUALITY_NATIVE_BACKEND = "native_v5b"
private const val QUALITY_KOTLIN_BACKEND = "kotlin_v5a"
private const val QUALITY_MAX_SAMPLE_DIMENSION = 640
private const val QUALITY_GYRO_WINDOW_NS = 80_000_000L

private data class QualityLumaSample(
    val width: Int,
    val height: Int,
    val values: FloatArray
)

private data class QualityGyroSample(
    val timestampNs: Long,
    val magnitude: Float
)

private data class InternalFrameQualityMetrics(
    val scoringBackend: String,
    val sharpnessRaw: Float,
    val sharpnessScore: Float,
    val motionScore: Float?,
    val exposureScore: Float,
    val brightnessMean: Float,
    val brightnessStdDev: Float,
    val clippedShadowRatio: Float,
    val clippedHighlightRatio: Float,
    val qualityScore: Float,
    val qualityLabel: String,
    val recommendedExclude: Boolean,
    val qualityReason: String?
)

fun analyzeKeplerFrameQuality(
    jobDir: File,
    onStatus: (String) -> Unit,
    onComplete: () -> Unit
) {
    val main = Handler(Looper.getMainLooper())
    fun post(message: String) = main.post { onStatus(message) }
    val thread = HandlerThread("KeplerFrameQualityThread").apply { start() }
    Handler(thread.looper).post {
        try {
            val job = loadJobJson(jobDir)
            val frames = job.optJSONArray("frames") ?: JSONArray()
            val gyro = loadQualityGyroSamples(File(jobDir, "gyro.csv"))
            val scoringBackend = if (NativeFrameQuality.isAvailable()) {
                QUALITY_NATIVE_BACKEND
            } else {
                QUALITY_KOTLIN_BACKEND
            }
            val labelCounts = mutableMapOf<String, Int>()
            var recommendedCount = 0
            var scoreSum = 0.0

            repeat(frames.length()) { position ->
                post("Analyzing frame ${position + 1}/${frames.length()}...")
                val frame = frames.optJSONObject(position) ?: return@repeat
                val metrics = try {
                    analyzeFrame(jobDir, job, frame, gyro)
                } catch (oom: OutOfMemoryError) {
                    suspectMetrics(scoringBackend, "OutOfMemoryError while analyzing frame")
                } catch (e: Exception) {
                    suspectMetrics(scoringBackend, "${e.javaClass.simpleName}: ${e.message}")
                }
                writeMetrics(frame, metrics)
                labelCounts[metrics.qualityLabel] =
                    labelCounts.getOrDefault(metrics.qualityLabel, 0) + 1
                if (metrics.recommendedExclude) recommendedCount++
                scoreSum += metrics.qualityScore
            }

            val total = frames.length()
            val good = labelCounts.getOrDefault("GOOD", 0)
            val ok = labelCounts.getOrDefault("OK", 0)
            val suspect = total - good - ok
            job.put("qualityScored", true)
                .put("qualityScoredAt", System.currentTimeMillis())
                .put("qualityScoringVersion", QUALITY_SCORING_VERSION)
                .put("qualityScoringBackend", scoringBackend)
                .put(
                    "qualitySummary",
                    JSONObject()
                        .put("totalFrames", total)
                        .put("goodFrames", good)
                        .put("okFrames", ok)
                        .put("suspectFrames", suspect)
                        .put("recommendedExcludeFrames", recommendedCount)
                        .put("averageQualityScore", if (total > 0) scoreSum / total else 0.0)
                )
                .put("updatedAt", System.currentTimeMillis())
            saveJobJson(jobDir, job)
            post("Frame quality analysis complete: $total frames, $recommendedCount recommended exclude.")
        } catch (oom: OutOfMemoryError) {
            post("Frame quality analysis failed: OutOfMemoryError; job cache kept.")
        } catch (e: Exception) {
            post("Frame quality analysis failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            main.post(onComplete)
            thread.quitSafely()
        }
    }
}

private fun analyzeFrame(
    jobDir: File,
    job: JSONObject,
    frame: JSONObject,
    gyro: List<QualityGyroSample>
): InternalFrameQualityMetrics {
    val rawFileName = frame.optString("raw16File")
    val colorFileName = frame.optString("file")
    val sample = when {
        rawFileName.isNotBlank() -> loadRawLumaSample(jobDir, job, frame, rawFileName)
        colorFileName.isNotBlank() -> loadBitmapLumaSample(File(jobDir, colorFileName))
        else -> error("Frame source filename missing")
    }
    val timestamp = frame.optLong("timestampNs", 0L).takeIf { it > 0L }
    val motion = timestamp?.let { motionScoreNear(gyro, it) }
    val exposureTimeNs = frame.optLong("exposureTimeNs", 0L).takeIf { it > 0L }
    val sensitivityIso = frame.optInt("sensitivityIso", 0).takeIf { it > 0 }
    return scoreLumaSample(sample, motion, exposureTimeNs, sensitivityIso)
}

private fun loadRawLumaSample(
    jobDir: File,
    job: JSONObject,
    frame: JSONObject,
    fileName: String
): QualityLumaSample {
    val file = File(jobDir, fileName)
    require(file.isFile) { "RAW16 file missing: $fileName" }
    val width = frame.optInt("rawWidth", job.optInt("rawWidth", 0))
    val height = frame.optInt("rawHeight", job.optInt("rawHeight", 0))
    require(width > 0 && height > 0) { "RAW dimensions missing" }
    require(file.length() >= width.toLong() * height.toLong() * 2L) {
        "RAW16 file truncated: $fileName"
    }
    val step = max(1, ceil(max(width, height) / QUALITY_MAX_SAMPLE_DIMENSION.toDouble()).toInt())
    val sampledWidth = max(1, width / step)
    val sampledHeight = max(1, height / step)
    val values = FloatArray(sampledWidth * sampledHeight)
    val rowBytes = ByteArray(width * 2)
    val cfa = job.optInt("cfaPattern", 0)
    val black = frame.optJSONArray("dynamicBlackLevel")
        ?.let { array -> (0 until array.length()).map { array.optDouble(it, 0.0) }.average() }
        ?.takeIf { !it.isNaN() }
        ?: job.optJSONArray("blackLevelPattern")
            ?.let { array -> (0 until array.length()).map { array.optDouble(it, 0.0) }.average() }
            ?.takeIf { !it.isNaN() }
        ?: 0.0
    val white = frame.optDouble(
        "dynamicWhiteLevel",
        job.optDouble("whiteLevel", 16_383.0)
    ).coerceAtLeast(black + 1.0)

    RandomAccessFile(file, "r").use { input ->
        var out = 0
        var sourceY = 0
        while (sourceY < height && out < values.size) {
            input.seek(sourceY.toLong() * width.toLong() * 2L)
            input.readFully(rowBytes)
            var sourceX = greenAlignedX(cfa, sourceY, 0)
            var column = 0
            while (column < sampledWidth && out < values.size) {
                val x = sourceX.coerceAtMost(width - 1)
                val byteIndex = x * 2
                val raw = (rowBytes[byteIndex].toInt() and 0xFF) or
                    ((rowBytes[byteIndex + 1].toInt() and 0xFF) shl 8)
                values[out++] = (((raw - black) / (white - black)) * 255.0)
                    .coerceIn(0.0, 255.0).toFloat()
                sourceX += step
                sourceX = greenAlignedX(cfa, sourceY, sourceX)
                column++
            }
            sourceY += step
        }
    }
    return QualityLumaSample(sampledWidth, sampledHeight, values)
}

private fun greenAlignedX(cfa: Int, y: Int, proposedX: Int): Int {
    val greenWhenEvenParity = cfa == 1 || cfa == 2
    val isGreen = ((proposedX + y) and 1 == 0) == greenWhenEvenParity
    return if (isGreen) proposedX else proposedX + 1
}

private fun loadBitmapLumaSample(file: File): QualityLumaSample {
    require(file.isFile) { "Color frame missing: ${file.name}" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unreadable image: ${file.name}" }
    var sampleSize = 1
    while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > QUALITY_MAX_SAMPLE_DIMENSION) {
        sampleSize *= 2
    }
    val bitmap = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    ) ?: error("Unreadable image: ${file.name}")
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
        QualityLumaSample(bitmap.width, bitmap.height, values)
    } finally {
        bitmap.recycle()
    }
}

private fun scoreLumaSample(
    sample: QualityLumaSample,
    motionScore: Float?,
    exposureTimeNs: Long?,
    sensitivityIso: Int?
): InternalFrameQualityMetrics {
    require(sample.values.isNotEmpty()) { "No luminance samples" }
    val nativeMetrics = if (NativeFrameQuality.isAvailable()) {
        val luma = ByteArray(sample.values.size) { index ->
            sample.values[index].roundToInt().coerceIn(0, 255).toByte()
        }
        NativeFrameQuality.scoreLumaFrame(
            luma = luma,
            width = sample.width,
            height = sample.height,
            rowStride = sample.width
        ) ?: error("Native frame quality scoring failed")
    } else {
        null
    }
    val kotlinMetrics = if (nativeMetrics == null) scoreLumaKotlin(sample) else null
    val scoringBackend = if (nativeMetrics != null) QUALITY_NATIVE_BACKEND else QUALITY_KOTLIN_BACKEND
    val sharpnessRaw = nativeMetrics?.sharpnessRaw ?: requireNotNull(kotlinMetrics).sharpnessRaw
    val mean = nativeMetrics?.brightnessMean ?: requireNotNull(kotlinMetrics).brightnessMean
    val stdDev = nativeMetrics?.brightnessStdDev ?: requireNotNull(kotlinMetrics).brightnessStdDev
    val shadowRatio = nativeMetrics?.clippedShadowRatio
        ?: requireNotNull(kotlinMetrics).clippedShadowRatio
    val highlightRatio = nativeMetrics?.clippedHighlightRatio
        ?: requireNotNull(kotlinMetrics).clippedHighlightRatio
    val sharpness = (sharpnessRaw / 24f).coerceIn(0f, 1f)
    val brightnessPenalty = (abs(mean - 115f) / 180f).coerceIn(0f, 0.55f)
    val clippingPenalty = (shadowRatio * 0.7f + highlightRatio * 0.9f).coerceIn(0f, 0.75f)
    val exposure = (1f - brightnessPenalty - clippingPenalty).coerceIn(0f, 1f)
    val motionPenalty = (motionScore ?: 0f) * 0.22f
    val longExposurePenalty = exposureTimeNs
        ?.let { ((it / 1_000_000_000.0) / (1.0 / 15.0)).toFloat() }
        ?.coerceIn(0f, 1f)
        ?.times(0.05f)
        ?: 0f
    val highIsoPenalty = sensitivityIso
        ?.let { ((it - 1600) / 4800f).coerceIn(0f, 1f) * 0.04f }
        ?: 0f
    val quality = (
        sharpness * 0.62f +
            exposure * 0.30f +
            (stdDev / 64f).coerceIn(0f, 1f) * 0.08f -
            motionPenalty -
            longExposurePenalty -
            highIsoPenalty
        ).coerceIn(0f, 1f)

    val severeUnder = mean < 20f || shadowRatio > 0.82f
    val severeOver = mean > 238f || highlightRatio > 0.58f
    val severeSoft = sharpness < 0.10f
    val severeMotion = motionScore != null && motionScore > 0.88f && sharpness < 0.32f
    val label = when {
        severeUnder -> "UNDEREXPOSED"
        severeOver -> "OVEREXPOSED"
        severeMotion -> "SHAKY"
        severeSoft -> "SOFT"
        sharpness < 0.24f -> "SOFT"
        motionScore != null && motionScore > 0.72f -> "SHAKY"
        quality >= 0.72f -> "GOOD"
        quality >= 0.48f -> "OK"
        else -> "SUSPECT"
    }
    val recommended = severeUnder || severeOver || severeSoft || severeMotion
    val reason = when (label) {
        "UNDEREXPOSED" -> "Very dark frame or excessive clipped shadows."
        "OVEREXPOSED" -> "Very bright frame or excessive clipped highlights."
        "SHAKY" -> "High motion near capture timestamp."
        "SOFT" -> "Low sampled luminance-gradient sharpness."
        "SUSPECT" -> "Combined quality metrics are below conservative threshold."
        else -> null
    }
    return InternalFrameQualityMetrics(
        scoringBackend = scoringBackend,
        sharpnessRaw = sharpnessRaw,
        sharpnessScore = sharpness,
        motionScore = motionScore,
        exposureScore = exposure,
        brightnessMean = mean,
        brightnessStdDev = stdDev,
        clippedShadowRatio = shadowRatio,
        clippedHighlightRatio = highlightRatio,
        qualityScore = quality,
        qualityLabel = label,
        recommendedExclude = recommended,
        qualityReason = reason
    )
}

private fun scoreLumaKotlin(sample: QualityLumaSample): FrameQualityNativeMetrics {
    val mean = sample.values.average().toFloat()
    var variance = 0.0
    var shadows = 0
    var highlights = 0
    sample.values.forEach { value ->
        variance += (value - mean) * (value - mean)
        if (value <= 5f) shadows++
        if (value >= 250f) highlights++
    }
    var gradientSum = 0.0
    var gradientCount = 0
    for (y in 1 until sample.height - 1) {
        for (x in 1 until sample.width - 1) {
            val index = y * sample.width + x
            gradientSum += abs(sample.values[index + 1] - sample.values[index - 1])
            gradientSum += abs(sample.values[index + sample.width] - sample.values[index - sample.width])
            gradientCount += 2
        }
    }
    return FrameQualityNativeMetrics(
        sharpnessRaw = (gradientSum / gradientCount.coerceAtLeast(1)).toFloat(),
        brightnessMean = mean,
        brightnessStdDev = sqrt(variance / sample.values.size).toFloat(),
        clippedShadowRatio = shadows.toFloat() / sample.values.size,
        clippedHighlightRatio = highlights.toFloat() / sample.values.size
    )
}

private fun loadQualityGyroSamples(file: File): List<QualityGyroSample> {
    if (!file.isFile) return emptyList()
    return file.useLines { lines ->
        lines.drop(1).mapNotNull { line ->
            val parts = line.split(',')
            val timestamp = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val x = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
            val y = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
            val z = parts.getOrNull(3)?.toFloatOrNull() ?: 0f
            QualityGyroSample(timestamp, sqrt(x * x + y * y + z * z))
        }.toList()
    }
}

private fun motionScoreNear(samples: List<QualityGyroSample>, timestampNs: Long): Float? {
    val nearby = samples.filter { abs(it.timestampNs - timestampNs) <= QUALITY_GYRO_WINDOW_NS }
    if (nearby.isEmpty()) return null
    val meanMagnitude = nearby.map { it.magnitude }.average().toFloat()
    return (meanMagnitude / 1.5f).coerceIn(0f, 1f)
}

private fun suspectMetrics(backend: String, reason: String): InternalFrameQualityMetrics = InternalFrameQualityMetrics(
    scoringBackend = backend,
    sharpnessRaw = 0f,
    sharpnessScore = 0f,
    motionScore = null,
    exposureScore = 0f,
    brightnessMean = 0f,
    brightnessStdDev = 0f,
    clippedShadowRatio = 0f,
    clippedHighlightRatio = 0f,
    qualityScore = 0f,
    qualityLabel = "SUSPECT",
    recommendedExclude = true,
    qualityReason = reason
)

private fun writeMetrics(frame: JSONObject, metrics: InternalFrameQualityMetrics) {
    frame.put("qualityScoringBackend", metrics.scoringBackend)
        .put("sharpnessRaw", metrics.sharpnessRaw.toDouble())
        .put("sharpnessScore", metrics.sharpnessScore.toDouble())
        .put("motionScore", metrics.motionScore?.toDouble() ?: JSONObject.NULL)
        .put("exposureScore", metrics.exposureScore.toDouble())
        .put("brightnessMean", metrics.brightnessMean.toDouble())
        .put("brightnessStdDev", metrics.brightnessStdDev.toDouble())
        .put("clippedShadowRatio", metrics.clippedShadowRatio.toDouble())
        .put("clippedHighlightRatio", metrics.clippedHighlightRatio.toDouble())
        .put("qualityScore", metrics.qualityScore.toDouble())
        .put("qualityLabel", metrics.qualityLabel)
        .put("recommendedExclude", metrics.recommendedExclude)
        .put("qualityReason", metrics.qualityReason ?: JSONObject.NULL)
}

// TODO(v5c): Optional AI frame-quality model.
// TODO(future): Ghosting risk prediction and semantic frame selection.
// TODO(future): Star, sky, face, aesthetic, and denoise-aware scoring.
