package com.projectnuke.keplernightlab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CancellationException
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

private fun recycleLoadedColorFrames(frames: List<LoadedColorFrame>) {
    frames.forEach { frame ->
        runCatching {
            if (!frame.bitmap.isRecycled) {
                frame.bitmap.recycle()
            }
        }
    }
}

private inline fun <T> withLoadedColorFrames(
    jobDir: File,
    job: JSONObject,
    block: (List<LoadedColorFrame>) -> T
): T {
    val frames = loadColorFrames(jobDir, job)
    return try {
        block(frames)
    } finally {
        recycleLoadedColorFrames(frames)
    }
}

private data class GyroSampleForFusion(
    val timestampNs: Long,
    val magnitude: Double
)

private fun Throwable.shortMessage(): String {
    val message = message?.takeIf { it.isNotBlank() }
    return if (message == null) {
        javaClass.simpleName
    } else {
        "${javaClass.simpleName}: $message"
    }
}

fun processLatestNightFusionV02(
    context: Context,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    onStatus: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    fun postStatus(message: String) {
        mainHandler.post { onStatus(message) }
    }

    val workerThread = HandlerThread("KeplerNightFusionV02Thread").apply { start() }
    val workerHandler = Handler(workerThread.looper)

    workerHandler.post {
        var jobDir: File? = null
        try {
            cancellation.throwIfCancelled()
            jobDir = findLatestColorBurstJobDir(context)
                ?: run {
                    postStatus("PIPELINE_FAILED: No YUV fusion job found.")
                    return@post
                }
            processNightFusionJobV02Sync(jobDir, onStatus = { postStatus(it) }, cancellation = cancellation)
            cancellation.throwIfCancelled()
            postStatus("PIPELINE_COMPLETE: YUV Night Fusion processing complete.")
        } catch (_: CancellationException) {
            postStatus("PIPELINE_CANCELLED: YUV Night Fusion processing cancelled; cache kept.")
        } catch (e: Exception) {
            Log.e("KeplerYuvPipeline", "PIPELINE_FAILED in processLatestNightFusionV02", e)
            runCatching {
                val targetDir = jobDir ?: findLatestColorBurstJobDir(context) ?: return@runCatching
                val job = loadJobJson(targetDir)
                job.put("currentPipelineStage", "PIPELINE_FAILED")
                    .put("processStatus", "PIPELINE_FAILED")
                    .put("pipelineFailed", true)
                    .put("pipelineFailureSource", "processLatestNightFusionV02")
                    .put("pipelineFailureType", e.javaClass.name)
                    .put("pipelineFailureMessage", e.shortMessage())
                    .put("pipelineFailureStackTrace", e.stackTraceToString())
                    .put("updatedAt", System.currentTimeMillis())
                saveJobJson(targetDir, job)
            }
            postStatus(
                "PIPELINE_FAILED: YUV Night Fusion failed: ${e.shortMessage()}; cache kept. See logcat/job.json for details."
            )
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

private const val ENABLE_YUV_FUSION_V2 = false
// Scoring-only V2 adds expensive work without producing output; keep it opt-in for debug builds.
private const val ENABLE_YUV_FUSION_V2_DRY_RUN = false

fun processNightFusionJobV02Sync(
    jobDir: File,
    onStatus: (String) -> Unit,
    requestedParams: ClassicYuvFusionParams? = null,
    cancellation: KeplerPipelineCancellation = NoOpKeplerPipelineCancellation,
    metadataPolicy: ReprocessMetadataPolicy = ReprocessMetadataPolicy.NORMAL
): File {
    cancellation.throwIfCancelled()
    return when {
        ENABLE_YUV_FUSION_V2 -> try {
            cancellation.throwIfCancelled()
            processYuvFusionJobV2(
                jobDir = jobDir,
                onStatus = onStatus,
                requestedParams = requestedParams,
                dryRun = false,
                cancellation = cancellation,
                metadataPolicy = metadataPolicy
            )
        } catch (t: Exception) {
            cancellation.throwIfCancelled()
            onStatus("YUV Fusion V2 failed; falling back to classic V1: ${t.shortMessage()}")
            val finalFile = processClassicYuvFusionJob(
                jobDir,
                onStatus = onStatus,
                requestedParams = requestedParams,
                cancellation = cancellation,
                metadataPolicy = metadataPolicy
            )
            cancellation.throwIfCancelled()
            finalFile
        }
        ENABLE_YUV_FUSION_V2_DRY_RUN -> try {
            cancellation.throwIfCancelled()
            processYuvFusionJobV2(
                jobDir = jobDir,
                onStatus = onStatus,
                requestedParams = requestedParams,
                dryRun = true,
                cancellation = cancellation,
                metadataPolicy = metadataPolicy
            )
        } catch (t: YuvFusionV2DryRunClassicFusionFailedException) {
            throw t
        } catch (t: Exception) {
            cancellation.throwIfCancelled()
            onStatus("YUV Fusion V2 dry-run failed; falling back to classic V1: ${t.shortMessage()}")
            val finalFile = processClassicYuvFusionJob(
                jobDir,
                onStatus = onStatus,
                requestedParams = requestedParams,
                cancellation = cancellation,
                metadataPolicy = metadataPolicy
            )
            cancellation.throwIfCancelled()
            finalFile
        }
        else -> {
            cancellation.throwIfCancelled()
            val finalFile = processClassicYuvFusionJob(
                jobDir,
                onStatus = onStatus,
                requestedParams = requestedParams,
                cancellation = cancellation,
                metadataPolicy = metadataPolicy
            )
            cancellation.throwIfCancelled()
            finalFile
        }
    }
}

fun findLatestColorBurstJobDir(context: Context): File? {
    val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
    return listOf(File(picturesDir, "KeplerYuvFusion"), File(picturesDir, "KeplerColorBurst"))
        .flatMap { root ->
            root.listFiles()
                ?.filter { it.isDirectory && File(it, "job.json").exists() }
                .orEmpty()
        }
        .maxByOrNull { it.lastModified() }
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
