package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

data class LatestSceneEstimate(
    val meanLuma: Double?,
    val motionScore: Double?
)

private data class LoadedColorFrame(
    val bitmap: Bitmap,
    val timestampNs: Long?
)

private data class GyroSampleForFusion(
    val timestampNs: Long,
    val magnitude: Double
)

fun processLatestNightFusionV02(
    context: Context,
    onStatus: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    fun postStatus(message: String) {
        mainHandler.post { onStatus(message) }
    }

    val workerThread = HandlerThread("KeplerNightFusionV02Thread").apply { start() }
    val workerHandler = Handler(workerThread.looper)

    workerHandler.post {
        try {
            postStatus("Night Fusion v0.2: loading latest burst...")

            val jobDir = findLatestColorBurstJobDir(context)
                ?: run {
                    postStatus("Night Fusion v0.2 failed: no KeplerColorBurst job found.")
                    workerThread.quitSafely()
                    return@post
                }
            val jobFile = File(jobDir, "job.json")
            val job = JSONObject(jobFile.readText())
            val frames = loadColorFrames(jobDir, job)

            if (frames.isEmpty()) {
                postStatus("Night Fusion v0.2 failed: no usable color frames.")
                workerThread.quitSafely()
                return@post
            }

            postStatus("Night Fusion v0.2: normalizing ${frames.size} frames...")

            val width = frames.first().bitmap.width
            val height = frames.first().bitmap.height
            val lumas = frames.map { computeMeanLuma(it.bitmap) }
            val referenceLuma = lumas.sorted()[lumas.size / 2].coerceAtLeast(1.0)
            val gyroSamples = readGyroSamples(File(jobDir, "gyro.csv"))
            val frameWeights = frames.mapIndexed { index, frame ->
                val motionScore = frame.timestampNs?.let { timestamp ->
                    motionScoreNear(gyroSamples, timestamp)
                } ?: 0.0
                val weight = 1.0 / (1.0 + motionScore * 8.0)
                weight.coerceIn(0.25, 1.0)
            }

            val average = weightedAverageFrames(
                frames = frames,
                frameLumas = lumas,
                frameWeights = frameWeights,
                referenceLuma = referenceLuma,
                width = width,
                height = height
            )

            val averageFile = File(jobDir, "average_color_rotated.png")
            saveBitmapPng(average, averageFile)

            postStatus("Night Fusion v0.2: denoising chroma...")
            val denoised = chromaDenoise3x3(average)
            val denoiseFile = File(jobDir, "denoise_color.png")
            saveBitmapPng(denoised, denoiseFile)

            postStatus("Night Fusion v0.2: sharpening and tone mapping...")
            val sharpened = sharpenAndToneMap(denoised)
            val finalFile = File(jobDir, "sharpened_night_fusion.png")
            saveBitmapPng(sharpened, finalFile)

            val notes = buildString {
                append("Night Fusion v0.2 weighted average, brightness normalization, chroma denoise, mild unsharp mask, tone curve.")
                append(" usedFrames=${frames.size}.")
                if (gyroSamples.isEmpty()) append(" Gyro missing; equal motion weights.")
                // TODO: Future gyro-based warp alignment should use gyro integration between frame timestamps.
                // TODO: Future image-based micro-alignment should reduce hand-shake ghosting before accumulation.
            }

            val updatedJob = JSONObject(job.toString())
                .put("processStatus", "NIGHT_FUSION_V0_2_COMPLETE")
                .put("averageColorFile", averageFile.name)
                .put("denoiseColorFile", denoiseFile.name)
                .put("finalNightFusionFile", finalFile.name)
                .put("usedFrameCount", frames.size)
                .put("processingNotes", notes)
                .put("processedAt", System.currentTimeMillis())

            jobFile.writeText(updatedJob.toString(2))

            frames.forEach { it.bitmap.recycle() }
            average.recycle()
            denoised.recycle()
            sharpened.recycle()

            postStatus(
                "Night Fusion v0.2 complete\n" +
                    "Frames: ${frames.size}\n" +
                    "Final: ${finalFile.name}\n" +
                    "Folder:\n${jobDir.absolutePath}"
            )
        } catch (e: Exception) {
            postStatus("Night Fusion v0.2 failed\n${e.stackTraceToString()}")
        } finally {
            workerThread.quitSafely()
        }
    }
}

fun estimateLatestColorBurstScene(context: Context): LatestSceneEstimate {
    val jobDir = findLatestColorBurstJobDir(context) ?: return LatestSceneEstimate(null, null)
    val jobFile = File(jobDir, "job.json")
    val firstFrame = runCatching {
        val job = JSONObject(jobFile.readText())
        val frames = job.optJSONArray("frames")
        val fileName = frames?.optJSONObject(0)?.optString("file").orEmpty()
        if (fileName.isBlank()) null else BitmapFactory.decodeFile(File(jobDir, fileName).absolutePath)
    }.getOrNull()
    val luma = firstFrame?.let { bitmap ->
        val value = computeMeanLuma(bitmap)
        bitmap.recycle()
        value
    }
    val gyro = readGyroSamples(File(jobDir, "gyro.csv")).map { it.magnitude }.average().takeIf { !it.isNaN() }
    return LatestSceneEstimate(luma, gyro)
}

fun processNightFusionJobV02Sync(
    jobDir: File,
    onStatus: (String) -> Unit
): File {
    onStatus("Processing Night Fusion...")
    val jobFile = File(jobDir, "job.json")
    val job = JSONObject(jobFile.readText())
    val frames = loadColorFrames(jobDir, job)
    val totalFrameCount = job.optJSONArray("frames")?.length() ?: 0
    val excludedFrameCount = totalFrameCount - frames.size

    if (frames.size < 2) {
        frames.forEach { it.bitmap.recycle() }
        error("Not enough enabled YUV frames to reprocess")
    }

    val width = frames.first().bitmap.width
    val height = frames.first().bitmap.height
    val lumas = frames.map { computeMeanLuma(it.bitmap) }
    val referenceLuma = lumas.sorted()[lumas.size / 2].coerceAtLeast(1.0)
    val gyroSamples = readGyroSamples(File(jobDir, "gyro.csv"))
    val frameWeights = frames.map { frame ->
        val motionScore = frame.timestampNs?.let { motionScoreNear(gyroSamples, it) } ?: 0.0
        (1.0 / (1.0 + motionScore * 8.0)).coerceIn(0.25, 1.0)
    }

    val average = weightedAverageFrames(
        frames = frames,
        frameLumas = lumas,
        frameWeights = frameWeights,
        referenceLuma = referenceLuma,
        width = width,
        height = height
    )
    val averageFile = File(jobDir, "average_color_rotated.png")
    saveBitmapPng(average, averageFile)

    val denoised = chromaDenoise3x3(average)
    val denoiseFile = File(jobDir, "denoise_color.png")
    saveBitmapPng(denoised, denoiseFile)

    val sharpened = sharpenAndToneMap(denoised)
    val finalFile = File(jobDir, "sharpened_night_fusion.png")
    saveBitmapPng(sharpened, finalFile)

    val notes = buildString {
        append("Night Fusion YUV pipeline v0.2. Weighted average, brightness normalization, chroma denoise, mild unsharp mask, tone curve.")
        append(" usedFrames=${frames.size}.")
        if (gyroSamples.isEmpty()) append(" Gyro missing; equal motion weights.")
    }

    val updatedJob = JSONObject(job.toString())
        .put("jobType", job.optString("jobType", "YUV_BURST_COLOR"))
        .put("processStatus", "NIGHT_FUSION_V0_2_COMPLETE")
        .put("averageColorFile", averageFile.name)
        .put("denoiseColorFile", denoiseFile.name)
        .put("finalNightFusionFile", finalFile.name)
        .put("usedFrameCount", frames.size)
        .put("excludedFrameCount", excludedFrameCount)
        .put("processingNotes", notes)
        .put("processedAt", System.currentTimeMillis())

    jobFile.writeText(updatedJob.toString(2))

    frames.forEach { it.bitmap.recycle() }
    average.recycle()
    denoised.recycle()
    sharpened.recycle()

    return finalFile
}

fun findLatestColorBurstJobDir(context: Context): File? {
    val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
    val colorRoot = File(picturesDir, "KeplerColorBurst")
    return colorRoot
        .listFiles()
        ?.filter { it.isDirectory && File(it, "job.json").exists() }
        ?.maxByOrNull { it.lastModified() }
}

private fun loadColorFrames(jobDir: File, job: JSONObject): List<LoadedColorFrame> {
    val framesArray = job.optJSONArray("frames") ?: return emptyList()
    val frames = mutableListOf<LoadedColorFrame>()
    var width: Int? = null
    var height: Int? = null

    for (i in 0 until framesArray.length()) {
        val frameObject = framesArray.optJSONObject(i) ?: continue
        if (
            !frameObject.optBoolean("enabled", true) ||
            frameObject.optBoolean("excludedByUser", false)
        ) {
            continue
        }
        val fileName = frameObject.optString("file")
        if (fileName.isBlank()) continue

        val bitmap = BitmapFactory.decodeFile(File(jobDir, fileName).absolutePath) ?: continue
        if (width == null || height == null) {
            width = bitmap.width
            height = bitmap.height
        }

        if (bitmap.width == width && bitmap.height == height) {
            frames.add(
                LoadedColorFrame(
                    bitmap = bitmap,
                    timestampNs = if (frameObject.has("timestampNs")) frameObject.optLong("timestampNs") else null
                )
            )
        } else {
            bitmap.recycle()
        }
    }

    return frames
}

private fun computeMeanLuma(bitmap: Bitmap): Double {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    var sum = 0.0
    pixels.forEach { color ->
        sum += 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
    }
    return sum / pixels.size.coerceAtLeast(1)
}

private fun weightedAverageFrames(
    frames: List<LoadedColorFrame>,
    frameLumas: List<Double>,
    frameWeights: List<Double>,
    referenceLuma: Double,
    width: Int,
    height: Int
): Bitmap {
    val pixelCount = width * height
    val accR = FloatArray(pixelCount)
    val accG = FloatArray(pixelCount)
    val accB = FloatArray(pixelCount)
    val accW = FloatArray(pixelCount)
    val pixels = IntArray(pixelCount)

    frames.forEachIndexed { index, frame ->
        frame.bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gain = (referenceLuma / frameLumas[index].coerceAtLeast(1.0)).coerceIn(0.75, 1.35)
        val weight = frameWeights[index].toFloat()

        for (p in 0 until pixelCount) {
            val color = pixels[p]
            accR[p] += (Color.red(color) * gain).toFloat() * weight
            accG[p] += (Color.green(color) * gain).toFloat() * weight
            accB[p] += (Color.blue(color) * gain).toFloat() * weight
            accW[p] += weight
        }
    }

    val out = IntArray(pixelCount)
    for (p in 0 until pixelCount) {
        val weight = accW[p].coerceAtLeast(0.001f)
        out[p] = Color.rgb(
            clampToByte((accR[p] / weight).toInt()),
            clampToByte((accG[p] / weight).toInt()),
            clampToByte((accB[p] / weight).toInt())
        )
    }

    return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
}

private fun chromaDenoise3x3(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    val out = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)

    fun index(x: Int, y: Int) = y * width + x

    for (y in 0 until height) {
        for (x in 0 until width) {
            val center = pixels[index(x, y)]
            val base = (Color.red(center) + Color.green(center) + Color.blue(center)) / 3.0
            var cR = 0.0
            var cG = 0.0
            var cB = 0.0
            var count = 0

            for (dy in -1..1) {
                for (dx in -1..1) {
                    val sx = (x + dx).coerceIn(0, width - 1)
                    val sy = (y + dy).coerceIn(0, height - 1)
                    val color = pixels[index(sx, sy)]
                    val neighborBase = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3.0
                    cR += Color.red(color) - neighborBase
                    cG += Color.green(color) - neighborBase
                    cB += Color.blue(color) - neighborBase
                    count++
                }
            }

            out[index(x, y)] = Color.rgb(
                clampToByte((base + cR / count).toInt()),
                clampToByte((base + cG / count).toInt()),
                clampToByte((base + cB / count).toInt())
            )
        }
    }

    return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
}

private fun sharpenAndToneMap(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    val blurred = boxBlur3x3(source)
    val blurredPixels = IntArray(width * height)
    val out = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    blurred.getPixels(blurredPixels, 0, width, 0, 0, width, height)
    blurred.recycle()

    fun curve(value: Int): Int {
        val x = (value / 255.0).coerceIn(0.0, 1.0)
        val gamma = x.pow(0.92)
        val contrast = ((gamma - 0.5) * 1.04 + 0.5).coerceIn(0.0, 1.0)
        val lifted = (contrast + 0.018 * (1.0 - contrast)).coerceIn(0.0, 1.0)
        return (lifted * 255.0).toInt().coerceIn(0, 255)
    }

    for (i in pixels.indices) {
        val color = pixels[i]
        val blur = blurredPixels[i]
        val amount = 0.42
        out[i] = Color.rgb(
            curve(clampToByte((Color.red(color) + amount * (Color.red(color) - Color.red(blur))).toInt())),
            curve(clampToByte((Color.green(color) + amount * (Color.green(color) - Color.green(blur))).toInt())),
            curve(clampToByte((Color.blue(color) + amount * (Color.blue(color) - Color.blue(blur))).toInt()))
        )
    }

    return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
}

private fun boxBlur3x3(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    val out = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)

    fun index(x: Int, y: Int) = y * width + x

    for (y in 0 until height) {
        for (x in 0 until width) {
            var r = 0
            var g = 0
            var b = 0
            var count = 0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val color = pixels[index((x + dx).coerceIn(0, width - 1), (y + dy).coerceIn(0, height - 1))]
                    r += Color.red(color)
                    g += Color.green(color)
                    b += Color.blue(color)
                    count++
                }
            }
            out[index(x, y)] = Color.rgb(r / count, g / count, b / count)
        }
    }

    return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
}

private fun readGyroSamples(file: File): List<GyroSampleForFusion> {
    if (!file.exists()) return emptyList()

    return file.readLines()
        .drop(1)
        .mapNotNull { line ->
            val parts = line.split(',')
            val timestamp = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val x = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            val y = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            val z = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.0
            GyroSampleForFusion(timestamp, sqrt(x * x + y * y + z * z))
        }
}

private fun motionScoreNear(
    gyroSamples: List<GyroSampleForFusion>,
    frameTimestampNs: Long
): Double {
    if (gyroSamples.isEmpty()) return 0.0

    val windowNs = 80_000_000L
    val nearby = gyroSamples.filter { abs(it.timestampNs - frameTimestampNs) <= windowNs }
    if (nearby.isEmpty()) return 0.0
    return nearby.map { it.magnitude }.average()
}

private fun saveBitmapPng(bitmap: Bitmap, file: File) {
    FileOutputStream(file).use { output ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
    }
}
