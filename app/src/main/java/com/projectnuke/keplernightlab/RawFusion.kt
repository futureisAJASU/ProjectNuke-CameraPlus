package com.projectnuke.keplernightlab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class RawFusionProcessResult(
    val success: Boolean,
    val mergedRawFile: File?,
    val mergedDngFile: File?,
    val previewPngFile: File?,
    val finalPngFile: File?,
    val errorMessage: String?
)

@SuppressLint("MissingPermission")
fun captureRawBurstForFusion(
    context: Context,
    cameraId: String,
    frameCount: Int,
    resolutionMode: CaptureResolutionMode = CaptureResolutionMode.MP12,
    zoomRatio: Float = 1.0f,
    focusAeState: FocusAeState = FocusAeState(),
    onStatus: (String) -> Unit,
    onComplete: (File) -> Unit,
    onError: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    fun post(message: String) = mainHandler.post { onStatus(message) }
    fun fail(message: String) {
        post(message)
        onError(message)
    }

    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val thread = HandlerThread("KeplerRawFusionCaptureThread").apply { start() }
    val handler = Handler(thread.looper)
    var cameraDevice: CameraDevice? = null
    var session: CameraCaptureSession? = null
    var reader: ImageReader? = null
    var motionLogger: MotionLogger? = null
    val pendingResults = mutableMapOf<Long, TotalCaptureResult>()
    val frameObjects = JSONArray()
    var savedFrames = 0
    var finished = false

    fun cleanup() {
        try { session?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { motionLogger?.stop() } catch (_: Exception) {}
        try { thread.quitSafely() } catch (_: Exception) {}
    }

    try {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("Stream configuration map missing")
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
        if (rawSizes.isNullOrEmpty()) error("RAW_SENSOR not exposed for cameraId=$cameraId")
        val rawSize = chooseRawFusionSize(rawSizes, resolutionMode)
        val cropApplied = zoomRatio > 1f && buildCenterCropRegion(characteristics, zoomRatio) != null

        val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: error("Pictures dir unavailable")
        val root = File(picturesDir, "KeplerRawFusion").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val jobDir = File(root, "KPL_RAW_FUSION_$stamp").apply { mkdirs() }
        val jobFile = File(jobDir, "job.json")

        val baseJob = JSONObject()
            .put("app", "Kepler Night Lab")
            .put("jobType", "RAW_NIGHT_FUSION")
            .put("status", "CAPTURING")
            .put("processStatus", "NOT_PROCESSED")
            .put("cameraId", cameraId)
            .put("resolutionMode", resolutionMode.label)
            .put("zoomRatio", zoomRatio.toDouble())
            .put("cropApplied", cropApplied)
            .put("rawWidth", rawSize.width)
            .put("rawHeight", rawSize.height)
            .put("sensorOrientation", characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: JSONObject.NULL)
            .put("activeArray", characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toString() ?: JSONObject.NULL)
            .put("preCorrectionActiveArray", characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.toString() ?: JSONObject.NULL)
            .put("whiteLevel", characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: JSONObject.NULL)
            .put("cfaPattern", characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: JSONObject.NULL)
            .put("blackLevelPattern", characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.toString() ?: JSONObject.NULL)
            .put("requestedFrames", frameCount)
            .put("frames", frameObjects)
            .put("createdAt", System.currentTimeMillis())
            .put("notes", "True RAW fusion input. Stores RAW_SENSOR DNG backup plus compact raw16 per frame.")
        jobFile.writeText(baseJob.toString(2))

        val imageReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, frameCount + 2)
        reader = imageReader

        motionLogger = runCatching { MotionLogger(context).also { it.start() } }.getOrNull()

        imageReader.setOnImageAvailableListener({ r ->
            if (finished) return@setOnImageAvailableListener
            var image: Image? = null
            try {
                image = r.acquireNextImage()
                if (savedFrames >= frameCount) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                val timestamp = image.timestamp
                val result = pendingResults.remove(timestamp)
                if (result == null) {
                    image.close()
                    fail("RAW capture failed: no matching CaptureResult for timestamp=$timestamp")
                    finished = true
                    cleanup()
                    return@setOnImageAvailableListener
                }

                val index = savedFrames
                val raw16Name = "frame_${index.toString().padStart(2, '0')}.raw16"
                val dngName = "frame_${index.toString().padStart(2, '0')}.dng"
                writeCompactRaw16(image, File(jobDir, raw16Name))
                FileOutputStream(File(jobDir, dngName)).use { output ->
                    DngCreator(characteristics, result).use { creator ->
                        creator.writeImage(output, image)
                    }
                }

                val plane = image.planes[0]
                frameObjects.put(
                    JSONObject()
                        .put("index", index)
                        .put("raw16File", raw16Name)
                        .put("dngFile", dngName)
                        .put("timestampNs", timestamp)
                        .put("exposureTimeNs", result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: JSONObject.NULL)
                        .put("sensitivityIso", result.get(CaptureResult.SENSOR_SENSITIVITY) ?: JSONObject.NULL)
                        .put("frameDurationNs", result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: JSONObject.NULL)
                        .put("rawWidth", image.width)
                        .put("rawHeight", image.height)
                        .put("rowStride", plane.rowStride)
                        .put("pixelStride", plane.pixelStride)
                        .put("dynamicBlackLevel", result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.joinToString() ?: JSONObject.NULL)
                        .put("dynamicWhiteLevel", result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: JSONObject.NULL)
                        .put("colorCorrectionGains", result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.toString() ?: JSONObject.NULL)
                        .put("colorCorrectionTransform", result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.toString() ?: JSONObject.NULL)
                        .put("cameraId", cameraId)
                        .put("zoomRatio", zoomRatio.toDouble())
                        .put("cropApplied", cropApplied)
                )
                savedFrames++
                jobFile.writeText(JSONObject(baseJob.toString()).put("savedFrames", savedFrames).put("frames", frameObjects).toString(2))
                post("Capturing RAW burst... $savedFrames / $frameCount")

                if (savedFrames >= frameCount) {
                    val motionFiles = runCatching { motionLogger?.saveToDirectory(jobDir) }.getOrNull()
                    val completeJob = JSONObject(baseJob.toString())
                        .put("status", "CAPTURE_COMPLETE")
                        .put("savedFrames", savedFrames)
                        .put("frames", frameObjects)
                        .put("gyroFile", motionFiles?.first ?: JSONObject.NULL)
                        .put("rotationVectorFile", motionFiles?.second ?: JSONObject.NULL)
                        .put("capturedAt", System.currentTimeMillis())
                    jobFile.writeText(completeJob.toString(2))
                    finished = true
                    post("RAW burst capture complete.")
                    onComplete(jobDir)
                    cleanup()
                }
            } catch (e: Exception) {
                finished = true
                fail("RAW fusion capture failed\n${e.stackTraceToString()}")
                cleanup()
            } finally {
                try { image?.close() } catch (_: Exception) {}
            }
        }, handler)

        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    camera.createCaptureSession(
                        listOf(imageReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                session = configured
                                val requests = List(frameCount) {
                                    camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(imageReader.surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        applyZoomAndFocusAe(characteristics, zoomRatio, focusAeState)
                                    }.build()
                                }
                                configured.captureBurst(
                                    requests,
                                    object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(
                                            session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            result: TotalCaptureResult
                                        ) {
                                            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                                            if (timestamp != null) pendingResults[timestamp] = result
                                        }
                                    },
                                    handler
                                )
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                fail("RAW fusion session configure failed")
                                cleanup()
                            }
                        },
                        handler
                    )
                }

                override fun onDisconnected(camera: CameraDevice) {
                    fail("RAW fusion camera disconnected")
                    cleanup()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    fail("RAW fusion camera error: $error")
                    cleanup()
                }
            },
            handler
        )
    } catch (e: Exception) {
        fail("RAW fusion init failed\n${e.stackTraceToString()}")
        cleanup()
    }
}

fun processRawFusionJob(
    context: Context,
    jobDir: File,
    onStatus: (String) -> Unit
): RawFusionProcessResult {
    return try {
        val jobFile = File(jobDir, "job.json")
        val job = JSONObject(jobFile.readText())
        val frames = job.getJSONArray("frames")
        val width = job.getInt("rawWidth")
        val height = job.getInt("rawHeight")
        val pixelCount = width * height
        val rawFrames = mutableListOf<ShortArray>()
        val frameMeta = mutableListOf<JSONObject>()

        for (i in 0 until frames.length()) {
            val frame = frames.getJSONObject(i)
            val rawFile = File(jobDir, frame.getString("raw16File"))
            if (rawFile.exists() && rawFile.length() >= pixelCount * 2L) {
                rawFrames.add(readRaw16(rawFile, pixelCount))
                frameMeta.add(frame)
            }
        }
        if (rawFrames.isEmpty()) error("No usable raw16 frames.")

        onStatus("Processing RAW fusion...")
        val blackLevel = estimateBlackLevel(job, rawFrames)
        val whiteLevel = estimateWhiteLevel(job, rawFrames, blackLevel)
        val refExposure = frameMeta.first().optDouble("exposureTimeNs", 1.0).coerceAtLeast(1.0)
        val refIso = frameMeta.first().optDouble("sensitivityIso", 100.0).coerceAtLeast(1.0)
        val gyroSamples = readRawGyroSamples(File(jobDir, "gyro.csv"))
        val acc = FloatArray(pixelCount)
        val weights = FloatArray(pixelCount)

        rawFrames.forEachIndexed { frameIndex, raw ->
            val meta = frameMeta[frameIndex]
            val exposure = meta.optDouble("exposureTimeNs", refExposure).coerceAtLeast(1.0)
            val iso = meta.optDouble("sensitivityIso", refIso).coerceAtLeast(1.0)
            val exposureScale = ((refExposure * refIso) / (exposure * iso)).coerceIn(0.5, 2.0)
            val motionScore = if (gyroSamples.isNotEmpty()) {
                motionNearRaw(gyroSamples, meta.optLong("timestampNs", 0L))
            } else {
                0.0
            }
            val weight = (1.0 / (1.0 + motionScore * 8.0)).coerceIn(0.25, 1.0).toFloat()
            for (p in 0 until pixelCount) {
                val corrected = ((raw[p].toInt() and 0xFFFF) - blackLevel).coerceAtLeast(0)
                acc[p] += (corrected * exposureScale).toFloat() * weight
                weights[p] += weight
            }
        }

        val merged = ShortArray(pixelCount)
        for (p in 0 until pixelCount) {
            val value = (acc[p] / weights[p].coerceAtLeast(0.001f)).toInt().coerceIn(0, whiteLevel - blackLevel)
            merged[p] = value.toShort()
        }
        val mergedRawFile = File(jobDir, "merged_raw.raw16")
        writeRaw16(merged, mergedRawFile)

        onStatus("Demosaicing RAW fusion...")
        val cfa = job.optInt("cfaPattern", 0)
        val preview = demosaicBilinear(merged, width, height, cfa, whiteLevel - blackLevel)
        val previewFile = File(jobDir, "raw_fusion_preview.png")
        saveRawFusionPng(preview, previewFile)
        val finalBitmap = sharpenRawFusion(toneMapRawFusion(preview))
        val finalFile = File(jobDir, "raw_fusion_final.png")
        saveRawFusionPng(finalBitmap, finalFile)
        preview.recycle()
        finalBitmap.recycle()

        val notes = "RAW Fusion MVP: Bayer weighted merge, black-level correction, exposure/ISO normalization, simple bilinear demosaic, gray-world-ish tone. Merged DNG skipped because CaptureResult metadata was not available after process restart. TODO gyro Bayer alignment, image micro-alignment, ghost suppression, RAW super-resolution/detail fusion, proper Camera2 color matrices."
        val updated = JSONObject(job.toString())
            .put("processStatus", "RAW_FUSION_COMPLETE")
            .put("mergedRawFile", mergedRawFile.name)
            .put("mergedDngFile", JSONObject.NULL)
            .put("previewFile", previewFile.name)
            .put("finalFile", finalFile.name)
            .put("usedFrameCount", rawFrames.size)
            .put("rawFusionNotes", notes)
            .put("blackLevelUsed", blackLevel)
            .put("whiteLevelUsed", whiteLevel)
            .put("processedAt", System.currentTimeMillis())
        jobFile.writeText(updated.toString(2))

        RawFusionProcessResult(true, mergedRawFile, null, previewFile, finalFile, null)
    } catch (e: Exception) {
        RawFusionProcessResult(false, null, null, null, null, "${e.javaClass.simpleName}: ${e.message}")
    }
}

fun captureProcessExportRawNightFusion(
    context: Context,
    cameraId: String,
    frameCount: Int,
    resolutionMode: CaptureResolutionMode,
    zoomRatio: Float,
    focusAeState: FocusAeState = FocusAeState(),
    cleanupPolicy: CacheCleanupPolicy = CacheCleanupPolicy.KEEP_ALL,
    onStatus: (String) -> Unit
) {
    val main = Handler(Looper.getMainLooper())
    fun post(message: String) = main.post { onStatus(message) }
    post("Capturing RAW burst...")
    captureRawBurstForFusion(
        context = context,
        cameraId = cameraId,
        frameCount = frameCount,
        resolutionMode = resolutionMode,
        zoomRatio = zoomRatio,
        focusAeState = focusAeState,
        onStatus = { post(it) },
        onComplete = { jobDir ->
            val thread = HandlerThread("KeplerRawFusionPipelineThread").apply { start() }
            Handler(thread.looper).post {
                try {
                    val process = processRawFusionJob(context, jobDir) { post(it) }
                    if (!process.success || process.finalPngFile == null) {
                        updateRawExportFailure(jobDir, process.errorMessage ?: "RAW fusion process failed")
                        post("RAW Night Fusion failed; keeping RAW cache. ${process.errorMessage}")
                        return@post
                    }
                    post("Exporting RAW Night Fusion...")
                    val bitmap = BitmapFactory.decodeFile(process.finalPngFile.absolutePath)
                        ?: error("Final RAW fusion PNG decode failed")
                    val result = exportNightFusionBitmapToGallery(
                        context = context,
                        bitmap = bitmap,
                        displayNameBase = "Kepler_RAW_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}",
                        requestedFormat = OutputFormat.HEIF
                    )
                    bitmap.recycle()
                    if (!result.success || result.uriString.isNullOrBlank()) {
                        updateRawExportFailure(jobDir, result.errorMessage ?: "Export failed")
                        post("RAW export failed; keeping RAW cache. ${result.errorMessage}")
                        return@post
                    }
                    val verified = verifyGalleryExport(context, result.uriString)
                    updateRawExportMetadata(jobDir, result, verified, cleanupPolicy)
                    if (!verified) {
                        post("RAW export verification failed; keeping RAW cache.")
                        return@post
                    }
                    val album = "Pictures/Kepler/${result.displayName}"
                    post("Saved to Gallery: $album\nRAW cache kept for reprocessing.")
                } catch (e: Exception) {
                    post("RAW Night Fusion pipeline failed; keeping RAW cache.\n${e.stackTraceToString()}")
                } finally {
                    thread.quitSafely()
                }
            }
        },
        onError = { post("RAW capture failed; keeping cache.\n$it") }
    )
}

private fun chooseRawFusionSize(sizes: Array<Size>, mode: CaptureResolutionMode): Size {
    fun mp(size: Size) = size.width.toDouble() * size.height.toDouble() / 1_000_000.0
    return when (mode) {
        CaptureResolutionMode.MP50 -> sizes.filter { mp(it) >= 40.0 }.maxByOrNull { it.width * it.height }
        CaptureResolutionMode.MP24_FUSION -> sizes.minByOrNull { abs(mp(it) - 24.0) }
        CaptureResolutionMode.MP12 -> sizes.filter { mp(it) <= 14.0 }.maxByOrNull { it.width * it.height }
    } ?: sizes.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
}

private fun writeCompactRaw16(image: Image, file: File) {
    val plane = image.planes[0]
    val buffer = plane.buffer
    FileOutputStream(file).use { output ->
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (y in 0 until image.height) {
            val row = y * rowStride
            for (x in 0 until image.width) {
                val index = row + x * pixelStride
                output.write(buffer.get(index).toInt())
                output.write(buffer.get(index + 1).toInt())
            }
        }
    }
}

private fun readRaw16(file: File, pixelCount: Int): ShortArray {
    val bytes = file.readBytes()
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return ShortArray(pixelCount) { if (buffer.remaining() >= 2) buffer.short else 0 }
}

private fun writeRaw16(values: ShortArray, file: File) {
    val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    values.forEach { buffer.putShort(it) }
    file.writeBytes(buffer.array())
}

private fun estimateBlackLevel(job: JSONObject, frames: List<ShortArray>): Int {
    val dynamic = job.optJSONArray("dynamicBlackLevel")?.optDouble(0)?.toInt()
    if (dynamic != null) return dynamic
    val sample = frames.first().asSequence().take(4096).map { it.toInt() and 0xFFFF }.sorted().toList()
    return sample.getOrNull((sample.size * 0.01).toInt()) ?: 64
}

private fun estimateWhiteLevel(job: JSONObject, frames: List<ShortArray>, blackLevel: Int): Int {
    val white = job.optInt("whiteLevel", 0)
    if (white > blackLevel) return white
    val maxValue = frames.maxOf { frame -> frame.maxOf { it.toInt() and 0xFFFF } }
    return when {
        maxValue <= 1023 -> 1023
        maxValue <= 4095 -> 4095
        maxValue <= 16383 -> 16383
        else -> 65535
    }
}

private fun demosaicBilinear(raw: ShortArray, width: Int, height: Int, cfa: Int, whiteLevel: Int): Bitmap {
    val pixels = IntArray(width * height)
    fun rawAt(x: Int, y: Int): Int = raw[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)].toInt() and 0xFFFF
    fun colorAt(x: Int, y: Int): Char {
        val evenY = y % 2 == 0
        val evenX = x % 2 == 0
        return when (cfa) {
            1 -> if (evenY) if (evenX) 'G' else 'R' else if (evenX) 'B' else 'G' // GRBG
            2 -> if (evenY) if (evenX) 'G' else 'B' else if (evenX) 'R' else 'G' // GBRG
            3 -> if (evenY) if (evenX) 'B' else 'G' else if (evenX) 'G' else 'R' // BGGR
            else -> if (evenY) if (evenX) 'R' else 'G' else if (evenX) 'G' else 'B' // RGGB
        }
    }
    fun avg(points: List<Pair<Int, Int>>) = points.sumOf { rawAt(it.first, it.second) } / points.size
    for (y in 0 until height) {
        for (x in 0 until width) {
            val c = colorAt(x, y)
            val value = rawAt(x, y)
            val r: Int
            val g: Int
            val b: Int
            when (c) {
                'R' -> {
                    r = value
                    g = avg(listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1))
                    b = avg(listOf(x - 1 to y - 1, x + 1 to y - 1, x - 1 to y + 1, x + 1 to y + 1))
                }
                'B' -> {
                    b = value
                    g = avg(listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1))
                    r = avg(listOf(x - 1 to y - 1, x + 1 to y - 1, x - 1 to y + 1, x + 1 to y + 1))
                }
                else -> {
                    g = value
                    r = avg(listOf(x - 1 to y, x + 1 to y))
                    b = avg(listOf(x to y - 1, x to y + 1))
                }
            }
            pixels[y * width + x] = Color.rgb(curveRaw(r, whiteLevel), curveRaw(g, whiteLevel), curveRaw(b, whiteLevel))
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun curveRaw(value: Int, whiteLevel: Int): Int {
    val x = (value.toDouble() / max(1, whiteLevel)).coerceIn(0.0, 1.0)
    return (x.pow(1.0 / 2.2) * 255.0).toInt().coerceIn(0, 255)
}

private fun toneMapRawFusion(source: Bitmap): Bitmap = source.copy(Bitmap.Config.ARGB_8888, false)

private fun sharpenRawFusion(source: Bitmap): Bitmap {
    // Mild placeholder; RAW merge already denoises. TODO better chroma denoise/unsharp.
    return source.copy(Bitmap.Config.ARGB_8888, false)
}

private fun saveRawFusionPng(bitmap: Bitmap, file: File) {
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

private data class RawGyro(val timestamp: Long, val magnitude: Double)

private fun readRawGyroSamples(file: File): List<RawGyro> {
    if (!file.exists()) return emptyList()
    return file.readLines().drop(1).mapNotNull { line ->
        val p = line.split(',')
        val t = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val x = p.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        val y = p.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        val z = p.getOrNull(3)?.toDoubleOrNull() ?: 0.0
        RawGyro(t, abs(x) + abs(y) + abs(z))
    }
}

private fun motionNearRaw(samples: List<RawGyro>, timestamp: Long): Double {
    val window = 80_000_000L
    return samples.filter { abs(it.timestamp - timestamp) <= window }.map { it.magnitude }.average().takeIf { !it.isNaN() } ?: 0.0
}

private fun updateRawExportMetadata(jobDir: File, result: GalleryExportResult, verified: Boolean, cleanupPolicy: CacheCleanupPolicy) {
    val file = File(jobDir, "job.json")
    val job = JSONObject(file.readText())
    job.put("exportStatus", if (verified) "EXPORTED" else "EXPORT_UNVERIFIED")
        .put("exportVerified", verified)
        .put("exportUri", result.uriString ?: JSONObject.NULL)
        .put("exportDisplayName", result.displayName ?: JSONObject.NULL)
        .put("exportMimeType", result.mimeType ?: JSONObject.NULL)
        .put("exportFormatUsed", result.formatUsed.label)
        .put("exportFallbackUsed", result.fallbackUsed)
        .put("exportFileSizeBytes", result.fileSizeBytes)
        .put("cleanupPolicy", cleanupPolicy.name)
        .put("cleanupStatus", "RAW_CACHE_KEPT")
        .put("exportedAt", System.currentTimeMillis())
    file.writeText(job.toString(2))
}

private fun updateRawExportFailure(jobDir: File, error: String) {
    val file = File(jobDir, "job.json")
    val job = if (file.exists()) JSONObject(file.readText()) else JSONObject()
    job.put("exportStatus", "FAILED")
        .put("exportVerified", false)
        .put("exportError", error)
        .put("cleanupStatus", "SKIPPED_RAW_CACHE_KEPT")
    file.writeText(job.toString(2))
}
