package com.projectnuke.keplernightlab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private const val SAVE_RAW_FUSION_DNG_SIDECARS = false

@SuppressLint("MissingPermission")
fun captureRawBurstForFusion(
    context: Context,
    cameraId: String,
    frameCount: Int,
    resolutionMode: CaptureResolutionMode = CaptureResolutionMode.MP12,
    resolutionPlan: ResolutionCapturePlan? = null,
    zoomRatio: Float = 1.0f,
    physicalCameraId: String? = null,
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
    val frameObjects = JSONArray()
    var requestedFrames = frameCount
    var attemptedFrames = 0
    var savedFrames = 0
    var receivedImages = 0
    var completedResults = 0
    val finished = AtomicBoolean(false)
    val imagesByTimestamp = mutableMapOf<Long, Image>()
    val resultsByTimestamp = mutableMapOf<Long, TotalCaptureResult>()
    val savedTimestamps = mutableSetOf<Long>()
    var failedCaptures = 0
    var maxResolutionPixelModeFailure: String? = null
    var sensorPixelModeUsed = false
    var timeoutRunnable: Runnable? = null
    fun postCaptureProgress() {
        post("RAW capture: saved $savedFrames/$requestedFrames, images $receivedImages/$requestedFrames, results $completedResults/$requestedFrames, failed $failedCaptures")
    }

    fun cleanup() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        imagesByTimestamp.values.forEach { runCatching { it.close() } }
        imagesByTimestamp.clear()
        resultsByTimestamp.clear()
        try { session?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { motionLogger?.stop() } catch (_: Exception) {}
        try { thread.quitSafely() } catch (_: Exception) {}
    }

    fun writeJobStatus(jobFile: File?, baseJob: JSONObject?, status: String, error: String? = null) {
        if (jobFile == null || baseJob == null) return
        runCatching {
            val job = JSONObject(baseJob.toString())
                .put("status", status)
                .put("requestedFrames", requestedFrames)
                .put("attemptedFrames", attemptedFrames)
                .put("savedFrames", savedFrames)
                .put("receivedImages", receivedImages)
                .put("completedResults", completedResults)
                .put("failedCaptures", failedCaptures)
                .put("sensorPixelModeUsed", sensorPixelModeUsed)
                .put("captureCompleteness", if (savedFrames >= requestedFrames) "FULL" else if (savedFrames >= MIN_RAW_FUSION_FRAMES) "PARTIAL" else "FAILED")
                .put("partialCapture", savedFrames in MIN_RAW_FUSION_FRAMES until requestedFrames)
                .put("frames", frameObjects)
                .put("updatedAt", System.currentTimeMillis())
            if (error != null) job.put("error", error)
            if (maxResolutionPixelModeFailure != null) {
                job.put("resolutionFallbackReason", maxResolutionPixelModeFailure)
                job.put("maxResolutionPixelModeFailure", maxResolutionPixelModeFailure)
            }
            if (error != null) job.put("failureReason", error)
            jobFile.writeText(job.toString(2))
        }
    }

    try {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("Stream configuration map missing")
        val rawSelection = chooseRawFusionSizeV2(characteristics, resolutionMode, resolutionPlan)
        val rawSize = rawSelection.size
        val rawPixelCount = rawSize.width.toLong() * rawSize.height.toLong()
        val highResolutionRaw = rawPixelCount >= HIGH_RES_RAW_MIN_PIXELS
        val frameClampReason = if (highResolutionRaw && requestedFrames > HIGH_RES_RAW_FRAME_LIMIT) {
            requestedFrames = HIGH_RES_RAW_FRAME_LIMIT
            "50MP-class RAW input detected; clamped burst to $HIGH_RES_RAW_FRAME_LIMIT frames for memory safety."
        } else {
            null
        }
        if (frameClampReason != null) post(frameClampReason)
        val saveDngSidecars = SAVE_RAW_FUSION_DNG_SIDECARS && !highResolutionRaw
        val dngSidecarSkipReason = if (saveDngSidecars) {
            null
        } else if (!SAVE_RAW_FUSION_DNG_SIDECARS) {
            "Per-frame DNG sidecars disabled by default for RAW Night Fusion; compact raw16 retained."
        } else {
            "v0 memory/storage policy: per-frame DNG disabled for 50MP-class high-resolution RAW; compact raw16 retained."
        }
        val native24RawUsed = resolutionMode == CaptureResolutionMode.MP24_FUSION &&
            rawPixelCount in 20_000_000L..30_000_000L
        val highResRawInputUsed = rawPixelCount >= HIGH_RES_RAW_MIN_PIXELS
        val actualInputMode = when {
            resolutionMode == CaptureResolutionMode.MP24_FUSION && highResRawInputUsed ->
                CaptureResolutionMode.MP50
            resolutionMode == CaptureResolutionMode.MP24_FUSION && native24RawUsed ->
                CaptureResolutionMode.MP24_FUSION
            else -> resolutionPlan?.actualInputMode ?: resolutionMode
        }
        val outputMode = resolutionPlan?.outputMode ?: resolutionMode
        val selected24MpStrategy = when {
            resolutionMode != CaptureResolutionMode.MP24_FUSION -> null
            highResRawInputUsed -> "50MP_DETAIL_TO_24MP_FUSION_V0"
            native24RawUsed -> "native_24mp_raw_fallback"
            else -> resolutionPlan?.selected24MpStrategy
        }
        val selected24MpReason = when {
            selected24MpStrategy == "50MP_DETAIL_TO_24MP_FUSION_V0" &&
                resolutionPlan?.selected24MpStrategy == selected24MpStrategy ->
                resolutionPlan?.selected24MpReason
            selected24MpStrategy == "50MP_DETAIL_TO_24MP_FUSION_V0" ->
                "50MP-class public Camera2 RAW detail input selected for 24MP Fusion."
            selected24MpStrategy == "native_24mp_raw_fallback" &&
                resolutionPlan?.selected24MpStrategy == selected24MpStrategy ->
                resolutionPlan?.selected24MpReason
            selected24MpStrategy == "native_24mp_raw_fallback" ->
                "No 50MP-class RAW input is exposed; using native 20-30MP public Camera2 RAW fallback."
            else -> resolutionPlan?.selected24MpReason ?: rawSelection.fallbackReason
        }
        val fusion24Strategy = if (resolutionMode == CaptureResolutionMode.MP24_FUSION) {
            if (zoomRatio >= 1.9f) "50MP_2X_CROP_UPSCALE_V0" else "50MP_DETAIL_DOWNSAMPLE_V0"
        } else {
            null
        }
        val cropSelection = buildCenterCropRegionForPixelMode(
            characteristics = characteristics,
            zoomRatio = zoomRatio,
            useMaximumResolutionActiveArray = rawSelection.requiresMaximumResolutionPixelMode
        )
        val cropApplied = zoomRatio > 1f && cropSelection.region != null
        val blackLevelPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val blackLevelJson = blackLevelPattern?.let {
            JSONArray(listOf(
                it.getOffsetForIndex(0, 0),
                it.getOffsetForIndex(1, 0),
                it.getOffsetForIndex(0, 1),
                it.getOffsetForIndex(1, 1)
            ))
        }

        val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: error("Pictures dir unavailable")
        val root = File(picturesDir, "KeplerRawFusion").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val jobDir = File(root, "KPL_RAW_FUSION_$stamp").apply { mkdirs() }
        val jobFile = File(jobDir, JOB_JSON_FILE_NAME)

        val baseJob = JSONObject()
            .put("app", "Kepler Night Lab")
            .put("jobType", "RAW_NIGHT_FUSION")
            .put("status", "CAPTURING")
            .put("processStatus", "NOT_PROCESSED")
            .put("cameraId", cameraId)
            .put("physicalCameraId", JSONObject.NULL)
            .put("resolutionMode", resolutionMode.label)
            .put("requestedResolutionMode", resolutionMode.name)
            .put("actualInputResolutionMode", actualInputMode.name)
            .put("outputResolutionMode", outputMode.name)
            .put("selectedPlanReason", resolutionPlan?.reason ?: JSONObject.NULL)
            .put("native24RawAvailable", resolutionPlan?.native24RawAvailable ?: native24RawUsed)
            .put("native24YuvAvailable", resolutionPlan?.native24YuvAvailable ?: false)
            .put("native24JpegAvailable", resolutionPlan?.native24JpegAvailable ?: false)
            .put("highResRawInputAvailable", resolutionPlan?.highResRawInputAvailable
                ?: (megapixels(rawSize) >= HIGH_RES_RAW_INPUT_MIN_MP))
            .put("native24RawUsed", native24RawUsed)
            .put("highResRawInputUsed", highResRawInputUsed)
            .put("samsungInternal24ModeAccessible", "UNKNOWN_NOT_PUBLIC_API")
            .put("selected24MpStrategy", selected24MpStrategy ?: JSONObject.NULL)
            .put("selected24MpReason", selected24MpReason ?: JSONObject.NULL)
            .put("nativePostprocessRequired", resolutionMode == CaptureResolutionMode.MP24_FUSION && highResolutionRaw)
            .put("nativePostprocessUsed", false)
            .put("highResRawInputThresholdPixels", HIGH_RES_RAW_MIN_PIXELS)
            .put("highResRawInputThresholdMp", 40.0)
            .put("planInputWidth", resolutionPlan?.inputSize?.width ?: JSONObject.NULL)
            .put("planInputHeight", resolutionPlan?.inputSize?.height ?: JSONObject.NULL)
            .put("planUsesMaximumResolution", resolutionPlan?.usesMaximumResolution ?: rawSelection.requiresMaximumResolutionPixelMode)
            .put("planUsesHighResolutionSlowPath", resolutionPlan?.usesHighResolutionSlowPath ?: rawSelection.isHighResolutionSlowPath)
            .put("planIsFusionOrUpscale", resolutionPlan?.isFusionOrUpscale ?: (resolutionMode == CaptureResolutionMode.MP24_FUSION))
            .put("zoomRatio", zoomRatio.toDouble())
            .put("cropApplied", cropApplied)
            .put("cropActiveArraySource", cropSelection.activeArraySource)
            .put("cropRegion", cropSelection.region?.toString() ?: JSONObject.NULL)
            .put("rawWidth", rawSize.width)
            .put("rawHeight", rawSize.height)
            .put("inputWidth", rawSize.width)
            .put("inputHeight", rawSize.height)
            .put("usesMaximumResolution", rawSelection.requiresMaximumResolutionPixelMode)
            .put("usesHighResolutionSlowPath", rawSelection.isHighResolutionSlowPath)
            .put("fusion24Strategy", fusion24Strategy ?: JSONObject.NULL)
            .put("nativeCropMpApprox", if (resolutionMode == CaptureResolutionMode.MP24_FUSION && zoomRatio >= 1.9f) megapixels(rawSize) / (zoomRatio * zoomRatio) else JSONObject.NULL)
            .put("outputIsUpscaled", resolutionMode == CaptureResolutionMode.MP24_FUSION && zoomRatio >= 1.9f)
            .put("rawSizeSource", rawSelection.source)
            .put("requiresMaximumResolutionPixelMode", rawSelection.requiresMaximumResolutionPixelMode)
            .put("sensorPixelModeUsed", false)
            .put("isHighResolutionSlowPath", rawSelection.isHighResolutionSlowPath)
            .put("rawSizeFallbackReason", rawSelection.fallbackReason ?: JSONObject.NULL)
            .put("sensorOrientation", characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: JSONObject.NULL)
            .put("activeArray", characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toString() ?: JSONObject.NULL)
            .put(
                "maximumResolutionActiveArray",
                if (Build.VERSION.SDK_INT >= 31) {
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)?.toString()
                        ?: JSONObject.NULL
                } else {
                    JSONObject.NULL
                }
            )
            .put("preCorrectionActiveArray", characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.toString() ?: JSONObject.NULL)
            .put("whiteLevel", characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: JSONObject.NULL)
            .put("cfaPattern", characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: JSONObject.NULL)
            .put("blackLevelPattern", blackLevelJson ?: JSONObject.NULL)
            .put("requestedFrames", requestedFrames)
            .put("originalRequestedFrames", frameCount)
            .put("frameCountClamped", frameClampReason != null)
            .put("frameCountClampReason", frameClampReason ?: JSONObject.NULL)
            .put("requestedFramesOriginal", frameCount)
            .put("requestedFramesEffective", requestedFrames)
            .put("frameClampApplied", frameClampReason != null)
            .put("frameClampReason", frameClampReason ?: JSONObject.NULL)
            .put("highResRawFrameLimit", HIGH_RES_RAW_FRAME_LIMIT)
            .put("dngSidecarSaved", saveDngSidecars)
            .put("dngSidecarSkipReason", dngSidecarSkipReason ?: JSONObject.NULL)
            .put("attemptedFrames", attemptedFrames)
            .put("captureCompleteness", "FAILED")
            .put("partialCapture", false)
            .put("frames", frameObjects)
            .put("createdAt", System.currentTimeMillis())
            .put("notes", "True RAW fusion input. Stores RAW_SENSOR DNG backup plus compact raw16 per frame. TODO retry budget: targetFrames=8, maxAttempts=9 or 10, continue until target saved or maxAttempts/timeout.")
        jobFile.writeText(baseJob.toString(2))

        if (
            rawSelection.requiresMaximumResolutionPixelMode &&
            characteristics.getAvailableCaptureRequestKeys()
                ?.contains(CaptureRequest.SENSOR_PIXEL_MODE) != true
        ) {
            val reason =
                "50MP RAW exists only in maximum-resolution map, but SENSOR_PIXEL_MODE request key is unavailable."
            writeJobStatus(jobFile, baseJob, "CAPTURE_FAILED", reason)
            fail("PIPELINE_FAILED: $reason")
            cleanup()
            return
        }

        val imageReader = try {
            ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, requestedFrames + 2)
        } catch (e: Exception) {
            val reason = "ImageReader failed for selected RAW plan ${rawSize.width}x${rawSize.height}: ${e.javaClass.simpleName}: ${e.message}"
            post("RAW selected resolution plan failed: $reason")
            val failedJob = JSONObject(baseJob.toString())
                .put("status", "CAPTURE_FAILED")
                .put("resolutionFallbackReason", reason)
                .put("rawSizeFallbackReason", rawSelection.fallbackReason ?: reason)
                .put("updatedAt", System.currentTimeMillis())
            jobFile.writeText(failedJob.toString(2))
            throw e
        }
        reader = imageReader

        motionLogger = runCatching { MotionLogger(context).also { it.start() } }.getOrNull()
        postCaptureProgress()

        fun finishError(status: String, message: String) {
            if (!finished.compareAndSet(false, true)) return
            if (rawSelection.requiresMaximumResolutionPixelMode && maxResolutionPixelModeFailure == null) {
                maxResolutionPixelModeFailure = "Maximum-resolution RAW capture failed: $message"
            }
            writeJobStatus(jobFile, baseJob, status, message)
            post(message)
            mainHandler.post { onError(message) }
            cleanup()
        }

        fun finishSuccess(partial: Boolean = false, reason: String? = null) {
            if (!finished.compareAndSet(false, true)) return
            val motionFiles = runCatching { motionLogger?.saveToDirectory(jobDir) }.getOrNull()
            val status = if (partial) "CAPTURE_COMPLETE_PARTIAL" else "CAPTURE_COMPLETE"
            val completeness = if (partial) "PARTIAL" else "FULL"
            val partialReason = reason ?: "saved $savedFrames/$requestedFrames frames; failedCaptures=$failedCaptures"
            val completeJob = JSONObject(baseJob.toString())
                .put("status", status)
                .put("savedFrames", savedFrames)
                .put("requestedFrames", requestedFrames)
                .put("attemptedFrames", attemptedFrames)
                .put("receivedImages", receivedImages)
                .put("completedResults", completedResults)
                .put("failedCaptures", failedCaptures)
                .put("captureCompleteness", completeness)
                .put("partialCapture", partial)
                .put("frames", frameObjects)
                .put("gyroFile", motionFiles?.first ?: JSONObject.NULL)
                .put("rotationVectorFile", motionFiles?.second ?: JSONObject.NULL)
                .put("capturedAt", System.currentTimeMillis())
            if (partial) completeJob.put("partialReason", partialReason)
            jobFile.writeText(completeJob.toString(2))
            if (partial) {
                post("CAPTURE_COMPLETE_PARTIAL: RAW capture saved $savedFrames/$requestedFrames frames; processing partial fusion.")
            } else {
                post("CAPTURE_COMPLETE: RAW capture saved $savedFrames/$requestedFrames frames")
            }
            mainHandler.post { onComplete(jobDir) }
            cleanup()
        }

        fun trySaveReadyFrames() {
            if (finished.get()) return
            val ready = imagesByTimestamp.keys
                .filter { it !in savedTimestamps && resultsByTimestamp.containsKey(it) }
                .sorted()
            for (timestamp in ready) {
                if (savedFrames >= requestedFrames || finished.get()) return
                val image = imagesByTimestamp.remove(timestamp) ?: continue
                val result = resultsByTimestamp.remove(timestamp) ?: run {
                    imagesByTimestamp[timestamp] = image
                    continue
                }
                savedTimestamps.add(timestamp)
                val index = savedFrames
                val raw16Name = "frame_${index.toString().padStart(2, '0')}.raw16"
                val dngName = "frame_${index.toString().padStart(2, '0')}.dng"
                try {
                    post("RAW saving frame ${index + 1}/$requestedFrames...")
                    writeCompactRaw16(image, File(jobDir, raw16Name))
                    if (saveDngSidecars) {
                        FileOutputStream(File(jobDir, dngName)).use { output ->
                            DngCreator(characteristics, result).use { creator ->
                                creator.writeImage(output, image)
                            }
                        }
                    }
                    val dynamicBlackLevel = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                    val plane = image.planes[0]
                    frameObjects.put(
                        JSONObject()
                            .put("index", index)
                            .put("raw16File", raw16Name)
                            .put("dngFile", if (saveDngSidecars) dngName else JSONObject.NULL)
                            .put("timestampNs", timestamp)
                            .put("exposureTimeNs", result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: JSONObject.NULL)
                            .put("sensitivityIso", result.get(CaptureResult.SENSOR_SENSITIVITY) ?: JSONObject.NULL)
                            .put("frameDurationNs", result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: JSONObject.NULL)
                            .put("rawWidth", image.width)
                            .put("rawHeight", image.height)
                            .put("rowStride", plane.rowStride)
                            .put("pixelStride", plane.pixelStride)
                            .put("dynamicBlackLevel", dynamicBlackLevel?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
                            .put("dynamicWhiteLevel", result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: JSONObject.NULL)
                            .put("colorCorrectionGains", result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.toString() ?: JSONObject.NULL)
                            .put("colorCorrectionTransform", result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.toString() ?: JSONObject.NULL)
                            .put("cameraId", cameraId)
                            .put("zoomRatio", zoomRatio.toDouble())
                            .put("cropApplied", cropApplied)
                            .put("cropActiveArraySource", cropSelection.activeArraySource)
                            .put("cropRegion", cropSelection.region?.toString() ?: JSONObject.NULL)
                    )
                    savedFrames++
                    post("RAW saved frame $savedFrames/$requestedFrames")
                    writeJobStatus(jobFile, baseJob, "CAPTURING")
                    postCaptureProgress()
                    if (savedFrames >= requestedFrames) {
                        finishSuccess()
                        return
                    }
                } catch (e: Exception) {
                    finishError("CAPTURE_FAILED", "RAW fusion capture failed\n${e.stackTraceToString()}")
                    return
                } finally {
                    runCatching { image.close() }
                }
            }
        }

        timeoutRunnable = Runnable {
            if (savedFrames < requestedFrames) {
                if (savedFrames >= MIN_RAW_FUSION_FRAMES) {
                    finishSuccess(partial = true, reason = "saved $savedFrames/$requestedFrames frames; timeout; failedCaptures=$failedCaptures")
                } else {
                    val message = "CAPTURE_TIMEOUT: RAW capture saved $savedFrames/$requestedFrames, images $receivedImages/$requestedFrames, results $completedResults/$requestedFrames, failed $failedCaptures"
                    finishError("CAPTURE_TIMEOUT", message)
                }
            }
        }.also { handler.postDelayed(it, max(30_000L, requestedFrames * 8_000L)) }

        imageReader.setOnImageAvailableListener({ r ->
            if (finished.get()) return@setOnImageAvailableListener
            val image = try {
                r.acquireNextImage()
            } catch (e: Exception) {
                finishError("CAPTURE_FAILED", "PIPELINE_FAILED: acquireNextImage failed\n${e.stackTraceToString()}")
                return@setOnImageAvailableListener
            }
            if (image == null) {
                post("RAW capture: acquireNextImage returned null; waiting for next image")
                return@setOnImageAvailableListener
            }
            receivedImages++
            imagesByTimestamp[image.timestamp] = image
            postCaptureProgress()
            trySaveReadyFrames()
        }, handler)

        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createRoutedStillCaptureSession(
                        camera = camera,
                        surface = imageReader.surface,
                        cameraId = cameraId,
                        physicalCameraId = physicalCameraId,
                        handler = handler,
                        onConfigured = { configured, physicalRoute ->
                                session = configured
                                val requestZoomRatio = if (physicalRoute) {
                                    zoomRatio
                                } else if (physicalCameraId != null) {
                                    3.0f
                                } else {
                                    zoomRatio
                                }
                                val requests = List(requestedFrames) {
                                    camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(imageReader.surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        if (rawSelection.requiresMaximumResolutionPixelMode) {
                                            try {
                                                set(
                                                    CaptureRequest.SENSOR_PIXEL_MODE,
                                                    CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
                                                )
                                                sensorPixelModeUsed = true
                                                baseJob.put("sensorPixelModeUsed", true)
                                            } catch (error: Exception) {
                                                maxResolutionPixelModeFailure =
                                                    "SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION failed: " +
                                                        "${error.javaClass.simpleName}: ${error.message}"
                                                throw IllegalStateException(
                                                    maxResolutionPixelModeFailure,
                                                    error
                                                )
                                            }
                                        }
                                        applyZoomAndFocusAe(
                                            characteristics = characteristics,
                                            zoomRatio = requestZoomRatio,
                                            focusAeState = focusAeState,
                                            useMaximumResolutionActiveArray = rawSelection.requiresMaximumResolutionPixelMode,
                                            cameraId = cameraId
                                        )
                                    }.build()
                                }
                                attemptedFrames = requests.size
                                writeJobStatus(jobFile, baseJob, "CAPTURING")
                                configured.captureBurst(
                                    requests,
                                    object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(
                                            session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            result: TotalCaptureResult
                                        ) {
                                            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                                            Log.i(
                                                "KeplerPhysicalRoute",
                                                "capture completed path=${if (physicalRoute) "physical" else "cropFallback"} " +
                                                    "requestedPhysicalCameraId=$physicalCameraId " +
                                                    "activePhysicalId=${result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)} " +
                                                    "zoomRatio=$requestZoomRatio"
                                            )
                                            if (timestamp != null && !finished.get()) {
                                                resultsByTimestamp[timestamp] = result
                                                completedResults++
                                                postCaptureProgress()
                                                trySaveReadyFrames()
                                            }
                                        }

                                        override fun onCaptureFailed(
                                            session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            failure: android.hardware.camera2.CaptureFailure
                                        ) {
                                            failedCaptures++
                                            post("RAW capture failure: reason=${failure.reason}, saved $savedFrames/$requestedFrames")
                                            if (savedFrames + failedCaptures >= attemptedFrames) {
                                                if (savedFrames >= MIN_RAW_FUSION_FRAMES) {
                                                    finishSuccess(partial = true, reason = "saved $savedFrames/$requestedFrames frames; failedCaptures=$failedCaptures")
                                                } else {
                                                    finishError("CAPTURE_FAILED", "PIPELINE_FAILED: RAW capture failed; saved $savedFrames/$requestedFrames, failed $failedCaptures")
                                                }
                                            }
                                        }

                                        override fun onCaptureSequenceAborted(
                                            session: CameraCaptureSession,
                                            sequenceId: Int
                                        ) {
                                            if (savedFrames >= MIN_RAW_FUSION_FRAMES && savedFrames < requestedFrames) {
                                                finishSuccess(partial = true, reason = "saved $savedFrames/$requestedFrames frames; sequence aborted; failedCaptures=$failedCaptures")
                                            } else {
                                                finishError("CAPTURE_ABORTED", "PIPELINE_FAILED: RAW capture sequence aborted; saved $savedFrames/$requestedFrames")
                                            }
                                        }

                                        override fun onCaptureSequenceCompleted(
                                            session: CameraCaptureSession,
                                            sequenceId: Int,
                                            frameNumber: Long
                                        ) {
                                            post("RAW capture sequence done: saved $savedFrames/$requestedFrames, images $receivedImages/$requestedFrames, results $completedResults/$requestedFrames")
                                        }
                                    },
                                    handler
                                )
                            },
                        onFailed = { reason ->
                            finishError(
                                "CAPTURE_FAILED",
                                "PIPELINE_FAILED: RAW fusion session configure failed: $reason"
                            )
                        }
                    )
                }

                override fun onDisconnected(camera: CameraDevice) {
                    finishError("CAPTURE_FAILED", "PIPELINE_FAILED: RAW fusion camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    finishError("CAPTURE_FAILED", "PIPELINE_FAILED: RAW fusion camera error: $error")
                }
            },
            handler
        )
    } catch (e: Exception) {
        fail("RAW fusion init failed\n${e.stackTraceToString()}")
        cleanup()
    }
}

private object RawFusionExportCoordinator {
    fun export(context: RawFusionExportContext): RawFusionProcessResult {
        return if (
            context.outputMode == CaptureResolutionMode.MP24_FUSION &&
            context.highResolutionRaw
        ) {
            exportNativeMp24(context)
        } else {
            exportStandardBitmap(context)
        }
    }

    private fun exportNativeMp24(context: RawFusionExportContext): RawFusionProcessResult {
        val nativeRgbaFile = File(context.files.jobDir, "raw_fusion_24mp.rgba")
        val nativeMetadataFile = File(
            context.files.jobDir,
            NATIVE_POSTPROCESS_JSON_FILE_NAME
        )
        val outputWidth = 5664
        val outputHeight = 4248
        context.onStatus("Processing RAW fusion: native 50MP detail to 24MP output...")
        val postprocessStatus = runCatching {
            NativeRawEngine.processRaw16ToRgbOutput(
                mergedRawPath = context.files.mergedRawFile.absolutePath,
                width = context.sensor.width,
                height = context.sensor.height,
                cfaPattern = context.sensor.cfa,
                // Native merge already writes black-level-subtracted compact raw16.
                blackLevel = 0,
                whiteLevel = context.sensor.whiteLevel - context.sensor.blackLevel,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                outputPath = nativeRgbaFile.absolutePath,
                outputMetadataJsonPath = nativeMetadataFile.absolutePath
            )
        }.getOrElse { "ERROR: ${it.javaClass.simpleName}: ${it.message}" }
        val expectedRgbaBytes = outputWidth.toLong() * outputHeight.toLong() * 4L
        val nativePostprocessUsed = postprocessStatus.startsWith("OK:") &&
            nativeRgbaFile.exists() &&
            nativeRgbaFile.length() == expectedRgbaBytes &&
            nativeMetadataFile.exists()
        val nativePostprocessMetadata = if (nativePostprocessUsed) {
            runCatching { JSONObject(nativeMetadataFile.readText()) }.getOrNull()
        } else {
            null
        }
        if (!nativePostprocessUsed) {
            val failed = applyNativeMergeMetadata(
                target = JSONObject(context.job.toString()),
                alignment = context.nativeMerge.alignmentMetadata
            )
                .put("processStatus", "NATIVE_POSTPROCESS_FAILED_KEEPING_CACHE")
                .put("nativePostprocessRequired", true)
                .put("nativePostprocessUsed", false)
                .put("nativePostprocessStatus", postprocessStatus)
                .put("alignmentStatus", context.nativeMerge.alignmentStatus)
                .put("processedAt", System.currentTimeMillis())
            context.files.jobFile.writeText(failed.toString(2))
            context.onStatus("RAW fusion native 24MP postprocess failed. RAW cache kept.")
            return RawFusionProcessResult(
                false,
                context.files.mergedRawFile,
                null,
                null,
                null,
                "Native 24MP postprocess failed: $postprocessStatus"
            )
        }

        val finalFile = File(context.files.jobDir, "raw_fusion_final.png")
        val nativeMp24DebugPngRequested = context.saveNativeMp24DebugPng
        var nativeMp24DebugPngWritten = false
        if (nativeMp24DebugPngRequested) {
            var nativeBitmap: Bitmap? = null
            try {
                context.onStatus("Processing RAW fusion: writing optional 24MP debug PNG...")
                nativeBitmap = loadRawRgbaBitmap(nativeRgbaFile, outputWidth, outputHeight)
                saveRawFusionPng(nativeBitmap, finalFile)
                nativeMp24DebugPngWritten = true
            } finally {
                nativeBitmap?.takeUnless { it.isRecycled }?.recycle()
            }
        }
        val nativeMp24DebugPngSkipReason = if (nativeMp24DebugPngWritten) {
            null
        } else {
            "Disabled by default for high-resolution native MP24 output to avoid an extra 24MP Bitmap allocation."
        }
        val partialNote = captureCompletenessNote(context.frames)
        val updated = applyNativePostprocessMetadata(
            target = applyNativeMergeMetadata(
                target = JSONObject(context.job.toString()),
                alignment = context.nativeMerge.alignmentMetadata
            ),
            metadata = nativePostprocessMetadata
        )
            .put("processStatus", "RAW_FUSION_COMPLETE")
            .put("mergedRawFile", context.files.mergedRawFile.name)
            .put("mergedDngFile", JSONObject.NULL)
            .put("previewFile", JSONObject.NULL)
            .put("finalFile", if (nativeMp24DebugPngWritten) finalFile.name else JSONObject.NULL)
            .put("finalOutputSource", "native_rgba")
            .put("nativeMp24DebugPngRequested", nativeMp24DebugPngRequested)
            .put("nativeMp24DebugPngWritten", nativeMp24DebugPngWritten)
            .put(
                "nativeMp24DebugPngSkipReason",
                nativeMp24DebugPngSkipReason ?: JSONObject.NULL
            )
            .put("usedFrameCount", context.frames.inputs.size)
            .put("requestedFrames", context.frames.requestedFrames)
            .put("savedFrames", context.frames.savedFrames)
            .put("captureCompleteness", context.frames.captureCompleteness)
            .put(
                "partialCapture",
                context.frames.partialCapture ||
                    context.frames.inputs.size < context.frames.requestedFrames
            )
            .put("processingNotes", partialNote)
            .put("blackLevelUsed", context.sensor.blackLevel)
            .put("whiteLevelUsed", context.sensor.whiteLevel)
            .put("outputWidth", outputWidth)
            .put("outputHeight", outputHeight)
            .put("selected24MpStrategy", "50MP_DETAIL_TO_24MP_FUSION_V0")
            .put("actualInputResolutionMode", CaptureResolutionMode.MP50.name)
            .put("outputResolutionMode", CaptureResolutionMode.MP24_FUSION.name)
            .put("nativePostprocessRequired", true)
            .put("nativePostprocessUsed", true)
            .put("nativePostprocessStatus", postprocessStatus)
            .put("nativePostprocessMetadataFile", nativeMetadataFile.name)
            .put("nativePostprocessRgbaFile", nativeRgbaFile.name)
            .put("fullSizeKotlinDemosaicUsed", false)
            .put("native24RawUsed", false)
            .put("highResRawInputUsed", true)
            .put("referenceFrameIndex", context.referenceSelection.index)
            .put("referenceFrameReason", context.referenceSelection.reason)
            .put("alignmentStatus", context.nativeMerge.alignmentStatus)
            .put("nativeRawMerge", true)
            .put("alignmentFile", context.files.alignmentFile.name)
            .put("mergedRawFormat", "black_level_subtracted_aligned_compact_raw16")
            .put("sensorOrientation", context.job.opt("sensorOrientation") ?: JSONObject.NULL)
            .put("outputOrientation", "UNROTATED_RAW_SENSOR_GRID")
            .put("processedAt", System.currentTimeMillis())
        context.files.jobFile.writeText(updated.toString(2))
        return RawFusionProcessResult(
            success = true,
            mergedRawFile = context.files.mergedRawFile,
            mergedDngFile = null,
            previewPngFile = null,
            finalPngFile = finalFile.takeIf { nativeMp24DebugPngWritten },
            errorMessage = null,
            nativeRgbaFile = nativeRgbaFile,
            nativeRgbaWidth = outputWidth,
            nativeRgbaHeight = outputHeight
        )
    }

    private fun exportStandardBitmap(context: RawFusionExportContext): RawFusionProcessResult {
        val previewFile = File(context.files.jobDir, "raw_fusion_preview.png")
        val finalFile = File(context.files.jobDir, "raw_fusion_final.png")
        val targetSize = chooseRawDemosaicTarget(
            context.sensor.width,
            context.sensor.height,
            context.highResolutionRaw
        )
        val mergedForDemosaic = readRaw16(
            context.files.mergedRawFile,
            context.sensor.pixelCount
        )
        var preview: Bitmap? = null
        var toned: Bitmap? = null
        var sharpened: Bitmap? = null
        var finalBitmap: Bitmap? = null
        var finalOutputWidth = targetSize.first
        var finalOutputHeight = targetSize.second
        val outputFallbackReason = when {
            context.outputMode == CaptureResolutionMode.MP50 ->
                "MP50 Kotlin ARGB output unsupported until native demosaic/postprocess exists; wrote downscaled preview."
            context.outputMode == CaptureResolutionMode.MP24_FUSION &&
                context.highResolutionRaw ->
                "MP24_FUSION fell back to ~12MP because full 24MP Kotlin bitmap chain is unsafe for 50MP-class high-resolution RAW input."
            else -> null
        }
        try {
            preview = if (context.highResolutionRaw) {
                context.onStatus(
                    "Processing RAW fusion: memory-safe downscaled demosaic " +
                        "${targetSize.first}x${targetSize.second}..."
                )
                demosaicBilinearDownscaled(
                    mergedForDemosaic,
                    context.sensor.width,
                    context.sensor.height,
                    context.sensor.cfa,
                    context.sensor.whiteLevel - context.sensor.blackLevel,
                    targetSize.first,
                    targetSize.second
                )
            } else {
                demosaicBilinear(
                    mergedForDemosaic,
                    context.sensor.width,
                    context.sensor.height,
                    context.sensor.cfa,
                    context.sensor.whiteLevel - context.sensor.blackLevel
                )
            }
            saveRawFusionPng(preview, previewFile)
            context.onStatus("Processing RAW fusion: tone/sharpen...")
            toned = toneMapRawFusion(preview)
            preview.recycle()
            preview = null
            sharpened = sharpenRawFusion(toned)
            toned.recycle()
            toned = null
            finalBitmap = resizeFinalForResolutionMode(
                source = sharpened,
                mode = if (outputFallbackReason == null) {
                    context.outputMode
                } else {
                    CaptureResolutionMode.MP12
                },
                onStatus = context.onStatus
            )
            if (finalBitmap !== sharpened) {
                sharpened.recycle()
                sharpened = null
            }
            finalOutputWidth = finalBitmap.width
            finalOutputHeight = finalBitmap.height
            saveRawFusionPng(finalBitmap, finalFile)
        } finally {
            preview?.takeUnless { it.isRecycled }?.recycle()
            toned?.takeUnless { it.isRecycled }?.recycle()
            sharpened?.takeUnless { it.isRecycled }?.recycle()
            finalBitmap?.takeUnless { it.isRecycled }?.recycle()
        }

        val partialNote = captureCompletenessNote(context.frames)
        val notes = "RAW Fusion MVP: Bayer weighted merge, black-level correction, exposure/ISO normalization, simple bilinear demosaic, gray-world-ish tone. $partialNote Merged DNG skipped because CaptureResult metadata was not available after process restart. TODO gyro Bayer alignment, image micro-alignment, ghost suppression, RAW super-resolution/detail fusion, proper Camera2 color matrices."
        val updated = applyNativeMergeMetadata(
            target = JSONObject(context.job.toString()),
            alignment = context.nativeMerge.alignmentMetadata
        )
            .put("processStatus", "RAW_FUSION_COMPLETE")
            .put("mergedRawFile", context.files.mergedRawFile.name)
            .put("mergedDngFile", JSONObject.NULL)
            .put("previewFile", previewFile.name)
            .put("finalFile", finalFile.name)
            .put("finalOutputSource", "final_png")
            .put("outputFallbackReason", outputFallbackReason ?: JSONObject.NULL)
            .put("fullSizeKotlinDemosaicUsed", !context.highResolutionRaw)
            .put("nativePostprocessRequired", false)
            .put("nativePostprocessUsed", false)
            .put("highResRawInputThresholdPixels", HIGH_RES_RAW_MIN_PIXELS)
            .put("highResRawInputThresholdMp", 40.0)
            .put("usedFrameCount", context.frames.inputs.size)
            .put("requestedFrames", context.frames.requestedFrames)
            .put("savedFrames", context.frames.savedFrames)
            .put("captureCompleteness", context.frames.captureCompleteness)
            .put(
                "partialCapture",
                context.frames.partialCapture ||
                    context.frames.inputs.size < context.frames.requestedFrames
            )
            .put("processingNotes", partialNote)
            .put("rawFusionNotes", notes)
            .put("blackLevelUsed", context.sensor.blackLevel)
            .put("blackLevelSource", context.blackLevelEstimate.source)
            .put("blackLevelMode", context.blackLevelEstimate.mode)
            .put("whiteLevelUsed", context.sensor.whiteLevel)
            .put("whiteLevelSource", context.whiteLevelEstimate.source)
            .put("outputWidth", finalOutputWidth)
            .put("outputHeight", finalOutputHeight)
            .put("referenceFrameIndex", context.referenceSelection.index)
            .put("referenceFrameReason", context.referenceSelection.reason)
            .put("alignmentStatus", context.nativeMerge.alignmentStatus)
            .put("nativeRawMerge", context.nativeMerge.mergedOk)
            .put(
                "alignmentFile",
                if (context.nativeMerge.mergedOk) {
                    context.files.alignmentFile.name
                } else {
                    JSONObject.NULL
                }
            )
            .put(
                "alignmentError",
                if (context.nativeMerge.mergedOk) JSONObject.NULL else context.nativeMerge.status
            )
            .put(
                "mergedRawFormat",
                if (context.nativeMerge.mergedOk) {
                    "black_level_subtracted_aligned_compact_raw16"
                } else {
                    "black_level_subtracted_kotlin_compact_raw16"
                }
            )
            .put("sensorOrientation", context.job.opt("sensorOrientation") ?: JSONObject.NULL)
            .put("outputOrientation", "UNROTATED_RAW_SENSOR_GRID")
            .put("processedAt", System.currentTimeMillis())
        context.files.jobFile.writeText(updated.toString(2))
        return RawFusionProcessResult(
            true,
            context.files.mergedRawFile,
            null,
            previewFile,
            finalFile,
            null
        )
    }

    private fun captureCompletenessNote(frames: PreparedRawFusionFrames): String {
        return if (frames.partialCapture || frames.inputs.size < frames.requestedFrames) {
            "Partial RAW fusion used ${frames.inputs.size}/${frames.requestedFrames} requested frames."
        } else {
            "Full RAW fusion used ${frames.inputs.size}/${frames.requestedFrames} requested frames."
        }
    }
}

fun processRawFusionJob(
    context: Context,
    jobDir: File,
    saveNativeMp24DebugPng: Boolean = SAVE_NATIVE_MP24_DEBUG_PNG_DEFAULT,
    onStatus: (String) -> Unit
): RawFusionProcessResult {
    val jobFile = File(jobDir, JOB_JSON_FILE_NAME)
    return try {
        val job = JSONObject(jobFile.readText())
        val frames = job.getJSONArray("frames")
        val width = job.getInt("rawWidth")
        val height = job.getInt("rawHeight")
        val pixelCountLong = width.toLong() * height.toLong()
        require(pixelCountLong <= Int.MAX_VALUE) { "RAW dimensions exceed Kotlin array limits: ${width}x$height" }
        val pixelCount = pixelCountLong.toInt()
        applyRawFusionMemoryMetadata(job, estimateRawFusionMemory(pixelCountLong))
        jobFile.writeText(job.toString(2))
        val highResolutionRaw = pixelCountLong >= HIGH_RES_RAW_MIN_PIXELS
        val preparedFrames = prepareRawFusionFrames(jobDir, job, frames, pixelCount)
        val frameInputs = preparedFrames.inputs
        val frameMeta = preparedFrames.metadata
        val requestedFrames = preparedFrames.requestedFrames

        onStatus("Processing RAW fusion: loading frames...")
        val fallbackSample = if (needsRawBlackLevelFallback(job, frameMeta)) {
            readRaw16(frameInputs.first().file, min(pixelCount, 4096))
        } else {
            null
        }
        val blackLevelEstimate = estimateBlackLevel(job, frameMeta, fallbackSample)
        val blackLevel = blackLevelEstimate.value
        val cfa = job.optInt("cfaPattern", 0)
        val mergePreparation = prepareRawMergeInputs(jobDir, frameInputs)
        val exposureScales = mergePreparation.exposureScales
        val frameWeights = mergePreparation.frameWeights
        val referenceSelection = chooseRawReferenceFrame(
            frameInputs,
            mergePreparation.motionScores,
            mergePreparation.gyroSamples.isNotEmpty()
        )
        val observedMax = fallbackSample?.maxOfOrNull { it.toInt() and 0xFFFF } ?: 0
        val whiteLevelEstimate = estimateWhiteLevel(job, observedMax, blackLevel)
        val whiteLevel = whiteLevelEstimate.value
        val sensor = RawFusionSensorData(
            width = width,
            height = height,
            pixelCount = pixelCount,
            cfa = cfa,
            blackLevel = blackLevel,
            whiteLevel = whiteLevel
        )

        onStatus("Processing RAW fusion: using ${frameInputs.size}/$requestedFrames frames")
        val mergedRawFile = File(jobDir, "merged_raw.raw16")
        val alignmentFile = File(jobDir, ALIGNMENT_JSON_FILE_NAME)
        val files = RawFusionFiles(jobDir, jobFile, mergedRawFile, alignmentFile)
        if (isNativeRawEngineAvailable()) {
            onStatus("Processing RAW fusion: native alignment...")
        }
        val nativeStatus = runNativeRawMerge(
            NativeMergeRequest(
                frameInputs = frameInputs,
                exposureScales = exposureScales,
                frameWeights = frameWeights,
                width = width,
                height = height,
                cfa = cfa,
                blackLevel = blackLevel,
                whiteLevel = whiteLevel,
                referenceIndex = referenceSelection.index,
                mergedRawFile = mergedRawFile,
                alignmentFile = alignmentFile
            )
        )
        val nativeMergedOk = nativeStatus.startsWith("OK:") &&
            mergedRawFile.exists() &&
            mergedRawFile.length() >= pixelCount * 2L &&
            alignmentFile.exists()
        if (nativeMergedOk) {
            onStatus("Processing RAW fusion: native Bayer merge complete")
        } else {
            if (highResolutionRaw) {
                job.put("processStatus", "NATIVE_MERGE_FAILED_KEEPING_CACHE")
                    .put("nativeRawMerge", false)
                    .put("alignmentStatus", "NATIVE_TILE_MERGE_FAILED")
                    .put("alignmentError", nativeStatus)
                    .put("processedAt", System.currentTimeMillis())
                jobFile.writeText(job.toString(2))
                onStatus("RAW fusion native tile merge failed. RAW cache kept.")
                return RawFusionProcessResult(
                    success = false,
                    mergedRawFile = null,
                    mergedDngFile = null,
                    previewPngFile = null,
                    finalPngFile = null,
                    errorMessage = "Native RAW merge required for 50MP-class high-resolution input; Kotlin fallback disabled. $nativeStatus"
                )
            }
            onStatus("Processing RAW fusion: native failed, falling back to Kotlin merge")
            mergeRawFramesInKotlin(
                KotlinRawMergeRequest(
                    frameInputs = frameInputs,
                    mergePreparation = mergePreparation,
                    sensor = sensor,
                    blackLevelEstimate = blackLevelEstimate,
                    job = job,
                    mergedRawFile = mergedRawFile,
                    onStatus = onStatus
                )
            )
        }
        val nativeAlignmentMetadata = if (nativeMergedOk) {
            runCatching { JSONObject(alignmentFile.readText()) }.getOrNull()
        } else {
            null
        }
        val nativeAlignmentStatus =
            resolveNativeAlignmentStatus(nativeMergedOk, nativeAlignmentMetadata)
        if (nativeMergedOk) {
            applyNativeMergeMetadata(job, nativeAlignmentMetadata)
                .put("alignmentStatus", nativeAlignmentStatus)
                .put("nativeRawMerge", true)
                .put("alignmentFile", alignmentFile.name)
            jobFile.writeText(job.toString(2))
        }

        onStatus("Processing RAW fusion: demosaicing...")
        val outputMode = CaptureResolutionMode.entries.firstOrNull { it.name == job.optString("outputResolutionMode") }
            ?: CaptureResolutionMode.entries.firstOrNull { it.label == job.optString("resolutionMode") }
            ?: CaptureResolutionMode.MP12
        RawFusionExportCoordinator.export(
            RawFusionExportContext(
                files = files,
                job = job,
                sensor = sensor,
                frames = preparedFrames,
                blackLevelEstimate = blackLevelEstimate,
                whiteLevelEstimate = whiteLevelEstimate,
                referenceSelection = referenceSelection,
                nativeMerge = NativeMergeOutcome(
                    mergedOk = nativeMergedOk,
                    status = nativeStatus,
                    alignmentMetadata = nativeAlignmentMetadata,
                    alignmentStatus = nativeAlignmentStatus
                ),
                outputMode = outputMode,
                highResolutionRaw = highResolutionRaw,
                saveNativeMp24DebugPng = saveNativeMp24DebugPng,
                onStatus = onStatus
            )
        )
    } catch (oom: OutOfMemoryError) {
        runCatching {
            val job = if (jobFile.exists()) JSONObject(jobFile.readText()) else JSONObject()
            job.put("processStatus", "OOM_FAILED_KEEPING_CACHE")
                .put("processError", "OutOfMemoryError")
                .put("processedAt", System.currentTimeMillis())
            jobFile.writeText(job.toString(2))
        }
        onStatus("RAW fusion stopped: insufficient memory. RAW cache kept.")
        RawFusionProcessResult(false, null, null, null, null, "OutOfMemoryError: RAW cache kept")
    } catch (e: Exception) {
        RawFusionProcessResult(false, null, null, null, null, "${e.javaClass.simpleName}: ${e.message}")
    }
}

fun chooseRawFusionSizeV2(
    characteristics: CameraCharacteristics,
    resolutionMode: CaptureResolutionMode,
    resolutionPlan: ResolutionCapturePlan? = null
): RawSizeSelection {
    val normalMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?: error("Stream configuration map missing")
    val normalRaw = normalMap.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
    val maxRaw = if (Build.VERSION.SDK_INT >= 31) {
        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.toList()
            .orEmpty()
    } else {
        emptyList()
    }
    val highRaw = runCatching {
        normalMap.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
    }.getOrDefault(emptyList())
    if (normalRaw.isEmpty() && maxRaw.isEmpty() && highRaw.isEmpty()) {
        error("RAW_SENSOR not exposed in normal, maximum-resolution, or high-resolution maps")
    }
    val highDetailRawAvailable = (maxRaw + highRaw + normalRaw).any {
        it.width.toLong() * it.height.toLong() >= HIGH_RES_RAW_MIN_PIXELS
    }

    resolutionPlan?.inputSize?.takeIf {
        resolutionPlan.isAvailable &&
            !(resolutionMode == CaptureResolutionMode.MP24_FUSION &&
                highDetailRawAvailable &&
                it.width.toLong() * it.height.toLong() < HIGH_RES_RAW_MIN_PIXELS)
    }?.let { planSize ->
        val inMax = planSize in maxRaw
        val inHigh = planSize in highRaw
        val inNormal = planSize in normalRaw
        if (inMax || inHigh || inNormal) {
            return RawSizeSelection(
                size = planSize,
                source = when {
                    resolutionMode == CaptureResolutionMode.MP24_FUSION &&
                        resolutionPlan.selected24MpStrategy == "50MP_DETAIL_TO_24MP_FUSION_V0" ->
                        "50mp_detail_for_24mp_fusion"
                    resolutionMode == CaptureResolutionMode.MP24_FUSION &&
                        resolutionPlan.selected24MpStrategy == "native_24mp_raw_fallback" ->
                        "native_24mp_raw_fallback"
                    inMax -> "MAXIMUM_RESOLUTION_MAP"
                    inHigh -> "HIGH_RESOLUTION_OUTPUT"
                    else -> "NORMAL_MAP"
                },
                requiresMaximumResolutionPixelMode = resolutionPlan.usesMaximumResolution || inMax,
                isHighResolutionSlowPath = resolutionPlan.usesHighResolutionSlowPath || inHigh,
                fallbackReason = null
            )
        }
    }

    fun List<Size>.largestAtLeast(minMp: Double) = filter { megapixels(it) >= minMp }.maxByOrNull { it.width * it.height }
    fun List<Size>.native24() = filter { megapixels(it) in 20.0..30.0 }.minByOrNull { abs(megapixels(it) - 24.0) }
    fun mp12() = normalRaw.filter { megapixels(it) <= 14.5 }.maxByOrNull { it.width * it.height }
        ?: normalRaw.maxByOrNull { it.width * it.height }
        ?: error("12MP RAW unavailable in normal stream map")
    fun mp50(): RawSizeSelection? {
        maxRaw.largestAtLeast(40.0)?.let {
            return RawSizeSelection(it, "MAXIMUM_RESOLUTION_MAP", true, false, null)
        }
        highRaw.largestAtLeast(40.0)?.let {
            return RawSizeSelection(it, "HIGH_RESOLUTION_OUTPUT", false, true, null)
        }
        normalRaw.largestAtLeast(40.0)?.let {
            return RawSizeSelection(it, "NORMAL_MAP", false, false, null)
        }
        return null
    }

    return when (resolutionMode) {
        CaptureResolutionMode.MP12 -> RawSizeSelection(mp12(), "NORMAL_MAP", false, false, null)
        CaptureResolutionMode.MP50 -> mp50()
            ?: error("50M RAW unavailable; no >=40MP RAW stream exposed through public Camera2.")
        CaptureResolutionMode.MP24_FUSION -> {
            mp50()?.copy(source = "50mp_detail_for_24mp_fusion")
                ?: (maxRaw + highRaw + normalRaw).native24()?.let {
                    val fromMax = it in maxRaw
                    RawSizeSelection(
                        it,
                        "native_24mp_raw_fallback",
                        fromMax,
                        it in highRaw,
                        "No 50MP-class RAW input is exposed; using native 20-30MP public Camera2 RAW fallback."
                    )
                }
                ?: error("24M Fusion unavailable: no >=40MP or native 20-30MP RAW stream is exposed through public Camera2.")
        }
    }
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
    val width = image.width
    val height = image.height
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val limit = buffer.limit()
    val rowBytes = ByteArray(width * 2)
    BufferedOutputStream(FileOutputStream(file)).use { output ->
        for (y in 0 until height) {
            val row = y * rowStride
            var out = 0
            for (x in 0 until width) {
                val index = row + x * pixelStride
                if (index + 1 < limit) {
                    rowBytes[out++] = buffer.get(index)
                    rowBytes[out++] = buffer.get(index + 1)
                } else {
                    rowBytes[out++] = 0
                    rowBytes[out++] = 0
                }
            }
            output.write(rowBytes)
        }
    }
    val expectedSize = width * height * 2L
    if (!file.exists() || file.length() < expectedSize) {
        error("RAW16 output invalid: ${file.absolutePath}, size=${file.length()}, expected=$expectedSize")
    }
}

internal fun readRaw16(file: File, pixelCount: Int): ShortArray {
    val values = ShortArray(pixelCount)
    val bytes = ByteArray(1024 * 1024)
    var byteCarry: Int? = null
    var out = 0
    BufferedInputStream(FileInputStream(file)).use { input ->
        while (out < pixelCount) {
            val read = input.read(bytes)
            if (read <= 0) break
            var index = 0
            if (byteCarry != null && index < read) {
                values[out++] = ((bytes[index].toInt() and 0xFF) shl 8 or byteCarry!!).toShort()
                byteCarry = null
                index++
            }
            while (index + 1 < read && out < pixelCount) {
                val lo = bytes[index].toInt() and 0xFF
                val hi = bytes[index + 1].toInt() and 0xFF
                values[out++] = ((hi shl 8) or lo).toShort()
                index += 2
            }
            if (index < read && out < pixelCount) {
                byteCarry = bytes[index].toInt() and 0xFF
            }
        }
    }
    return values
}

internal fun writeRaw16(values: ShortArray, file: File) {
    val row = ByteArray(1024 * 1024)
    BufferedOutputStream(FileOutputStream(file)).use { output ->
        var offset = 0
        while (offset < values.size) {
            val count = min(values.size - offset, row.size / 2)
            var out = 0
            for (i in 0 until count) {
                val value = values[offset + i].toInt()
                row[out++] = (value and 0xFF).toByte()
                row[out++] = ((value ushr 8) and 0xFF).toByte()
            }
            output.write(row, 0, out)
            offset += count
        }
    }
}

private fun needsRawBlackLevelFallback(job: JSONObject, frameMeta: List<JSONObject>): Boolean {
    if (frameMeta.any { (it.optJSONArray("dynamicBlackLevel")?.length() ?: 0) > 0 }) return false
    val patternArray = job.optJSONArray("blackLevelPattern")
    if (patternArray != null && patternArray.length() > 0) return false
    return Regex("\\d+").find(job.optString("blackLevelPattern")) == null
}

private fun estimateBlackLevel(job: JSONObject, frameMeta: List<JSONObject>, fallbackSample: ShortArray?): BlackLevelEstimate {
    for (frame in frameMeta) {
        val dynamic = frame.optJSONArray("dynamicBlackLevel")
        if (dynamic != null && dynamic.length() >= 4) {
            val pattern = IntArray(4) { dynamic.optDouble(it).toInt() }
            return BlackLevelEstimate(pattern.average().toInt(), "frame.dynamicBlackLevel", pattern, "per_cfa")
        }
        if (dynamic != null && dynamic.length() > 0) {
            return BlackLevelEstimate(dynamic.optDouble(0).toInt(), "frame.dynamicBlackLevel")
        }
    }
    val patternArray = job.optJSONArray("blackLevelPattern")
    val patternValues = if (patternArray != null) {
        List(patternArray.length()) { patternArray.optInt(it) }
    } else {
        Regex("\\d+").findAll(job.optString("blackLevelPattern")).mapNotNull { it.value.toIntOrNull() }.toList()
    }
    if (patternValues.size >= 4) {
        val pattern = IntArray(4) { patternValues[it] }
        return BlackLevelEstimate(pattern.average().toInt(), "job.blackLevelPattern", pattern, "per_cfa")
    }
    if (patternValues.isNotEmpty()) return BlackLevelEstimate(patternValues.average().toInt(), "job.blackLevelPattern")
    val sample = fallbackSample?.asSequence()?.map { it.toInt() and 0xFFFF }?.sorted()?.toList().orEmpty()
    return BlackLevelEstimate(sample.getOrNull((sample.size * 0.01).toInt()) ?: 64, "percentile.fallback")
}

internal fun blackLevelForPixel(
    x: Int,
    y: Int,
    cfa: Int,
    estimate: BlackLevelEstimate
): Int {
    val pattern = estimate.pattern ?: return estimate.value
    return pattern[blackLevelPatternIndex(x, y, cfa)]
}

private fun blackLevelPatternIndex(x: Int, y: Int, cfa: Int): Int {
    val evenX = x % 2 == 0
    val evenY = y % 2 == 0
    // Pattern index order: R, green on the R row, green on the B row, B.
    return when (cfa) {
        1 -> if (evenY) if (evenX) 1 else 0 else if (evenX) 3 else 2 // GRBG
        2 -> if (evenY) if (evenX) 2 else 3 else if (evenX) 0 else 1 // GBRG
        3 -> if (evenY) if (evenX) 3 else 2 else if (evenX) 1 else 0 // BGGR
        else -> if (evenY) if (evenX) 0 else 1 else if (evenX) 2 else 3 // RGGB
    }
}

internal fun estimateWhiteLevel(
    job: JSONObject,
    maxValue: Int,
    blackLevel: Int
): WhiteLevelEstimate {
    val white = job.optInt("whiteLevel", 0)
    if (white > blackLevel) return WhiteLevelEstimate(white, "cameraCharacteristics.whiteLevel")
    val fallback = when {
        maxValue <= 1023 -> 1023
        maxValue <= 4095 -> 4095
        maxValue <= 16383 -> 16383
        else -> 65535
    }
    return WhiteLevelEstimate(max(fallback, maxValue), "observedMax.fallback")
}

private fun demosaicBilinear(raw: ShortArray, width: Int, height: Int, cfa: Int, whiteLevel: Int): Bitmap {
    val pixels = IntArray(width * height)
    fun rawAt(x: Int, y: Int): Int = raw[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)].toInt() and 0xFFFF
    fun avg2(a: Int, b: Int): Int = (a + b) / 2
    fun avg4(a: Int, b: Int, c: Int, d: Int): Int = (a + b + c + d) / 4
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
    fun greenOnRedRow(y: Int): Boolean = when (cfa) {
        2, 3 -> y % 2 != 0 // GBRG/BGGR red samples live on odd rows.
        else -> y % 2 == 0 // RGGB/GRBG red samples live on even rows.
    }
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
                    g = avg4(rawAt(x - 1, y), rawAt(x + 1, y), rawAt(x, y - 1), rawAt(x, y + 1))
                    b = avg4(rawAt(x - 1, y - 1), rawAt(x + 1, y - 1), rawAt(x - 1, y + 1), rawAt(x + 1, y + 1))
                }
                'B' -> {
                    b = value
                    g = avg4(rawAt(x - 1, y), rawAt(x + 1, y), rawAt(x, y - 1), rawAt(x, y + 1))
                    r = avg4(rawAt(x - 1, y - 1), rawAt(x + 1, y - 1), rawAt(x - 1, y + 1), rawAt(x + 1, y + 1))
                }
                else -> {
                    g = value
                    if (greenOnRedRow(y)) {
                        r = avg2(rawAt(x - 1, y), rawAt(x + 1, y))
                        b = avg2(rawAt(x, y - 1), rawAt(x, y + 1))
                    } else {
                        r = avg2(rawAt(x, y - 1), rawAt(x, y + 1))
                        b = avg2(rawAt(x - 1, y), rawAt(x + 1, y))
                    }
                }
            }
            pixels[y * width + x] = Color.rgb(curveRaw(r, whiteLevel), curveRaw(g, whiteLevel), curveRaw(b, whiteLevel))
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun chooseRawDemosaicTarget(width: Int, height: Int, forceDownscale: Boolean): Pair<Int, Int> {
    if (!forceDownscale) return width to height
    val scale = kotlin.math.sqrt(TARGET_12MP_PIXELS.toDouble() / (width.toLong() * height.toLong()))
        .coerceAtMost(1.0)
    val targetWidth = ((width * scale).toInt().coerceAtLeast(2) / 2) * 2
    val targetHeight = ((height * scale).toInt().coerceAtLeast(2) / 2) * 2
    return targetWidth to targetHeight
}

private fun demosaicBilinearDownscaled(
    raw: ShortArray,
    width: Int,
    height: Int,
    cfa: Int,
    whiteLevel: Int,
    targetWidth: Int,
    targetHeight: Int
): Bitmap {
    val pixels = IntArray(targetWidth * targetHeight)
    fun sourceX(x: Int): Int {
        val evenBase = ((x.toLong() * width) / targetWidth).toInt() / 2 * 2
        return (evenBase + x % 2).coerceIn(0, width - 1)
    }
    fun sourceY(y: Int): Int {
        val evenBase = ((y.toLong() * height) / targetHeight).toInt() / 2 * 2
        return (evenBase + y % 2).coerceIn(0, height - 1)
    }
    fun rawAt(x: Int, y: Int): Int =
        raw[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)].toInt() and 0xFFFF
    fun avg2(a: Int, b: Int) = (a + b) / 2
    fun avg4(a: Int, b: Int, c: Int, d: Int) = (a + b + c + d) / 4
    fun colorAt(x: Int, y: Int): Char = when (cfa) {
        1 -> if (y % 2 == 0) if (x % 2 == 0) 'G' else 'R' else if (x % 2 == 0) 'B' else 'G'
        2 -> if (y % 2 == 0) if (x % 2 == 0) 'G' else 'B' else if (x % 2 == 0) 'R' else 'G'
        3 -> if (y % 2 == 0) if (x % 2 == 0) 'B' else 'G' else if (x % 2 == 0) 'G' else 'R'
        else -> if (y % 2 == 0) if (x % 2 == 0) 'R' else 'G' else if (x % 2 == 0) 'G' else 'B'
    }
    fun greenOnRedRow(y: Int) = if (cfa == 2 || cfa == 3) y % 2 != 0 else y % 2 == 0
    for (targetY in 0 until targetHeight) {
        val y = sourceY(targetY)
        for (targetX in 0 until targetWidth) {
            val x = sourceX(targetX)
            val value = rawAt(x, y)
            val (r, g, b) = when (colorAt(x, y)) {
                'R' -> Triple(
                    value,
                    avg4(rawAt(x - 1, y), rawAt(x + 1, y), rawAt(x, y - 1), rawAt(x, y + 1)),
                    avg4(rawAt(x - 1, y - 1), rawAt(x + 1, y - 1), rawAt(x - 1, y + 1), rawAt(x + 1, y + 1))
                )
                'B' -> Triple(
                    avg4(rawAt(x - 1, y - 1), rawAt(x + 1, y - 1), rawAt(x - 1, y + 1), rawAt(x + 1, y + 1)),
                    avg4(rawAt(x - 1, y), rawAt(x + 1, y), rawAt(x, y - 1), rawAt(x, y + 1)),
                    value
                )
                else -> if (greenOnRedRow(y)) {
                    Triple(avg2(rawAt(x - 1, y), rawAt(x + 1, y)), value, avg2(rawAt(x, y - 1), rawAt(x, y + 1)))
                } else {
                    Triple(avg2(rawAt(x, y - 1), rawAt(x, y + 1)), value, avg2(rawAt(x - 1, y), rawAt(x + 1, y)))
                }
            }
            pixels[targetY * targetWidth + targetX] =
                Color.rgb(curveRaw(r, whiteLevel), curveRaw(g, whiteLevel), curveRaw(b, whiteLevel))
        }
    }
    return Bitmap.createBitmap(pixels, targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
}

private fun curveRaw(value: Int, whiteLevel: Int): Int {
    val x = (value.toDouble() / max(1, whiteLevel)).coerceIn(0.0, 1.0)
    return (x.pow(1.0 / 2.2) * 255.0).toInt().coerceIn(0, 255)
}

private fun toneMapRawFusion(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) {
        val color = pixels[i]
        val r = toneChannel(Color.red(color))
        val g = toneChannel(Color.green(color))
        val b = toneChannel(Color.blue(color))
        pixels[i] = Color.rgb(r, g, b)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun toneChannel(value: Int): Int {
    val x = value / 255.0
    val lifted = (x * 1.06 + 0.012).coerceIn(0.0, 1.0)
    val shouldered = lifted / (lifted + 0.10)
    return (shouldered / (1.0 / 1.10) * 255.0).toInt().coerceIn(0, 255)
}

private fun resizeFinalForResolutionMode(
    source: Bitmap,
    mode: CaptureResolutionMode,
    onStatus: (String) -> Unit
): Bitmap {
    if (mode != CaptureResolutionMode.MP24_FUSION) return source
    val targetWidth = 4896
    val targetHeight = 3672
    return try {
        onStatus("24M Fusion v0: resizing final output to ${targetWidth}x$targetHeight")
        Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    } catch (e: Exception) {
        onStatus("24M Fusion resize failed; keeping source output. ${e.javaClass.simpleName}")
        source
    }
}

private fun sharpenRawFusion(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val input = IntArray(width * height)
    val output = IntArray(width * height)
    source.getPixels(input, 0, width, 0, 0, width, height)
    fun pixel(x: Int, y: Int): Int = input[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
    for (y in 0 until height) {
        for (x in 0 until width) {
            val center = pixel(x, y)
            var blurR = 0
            var blurG = 0
            var blurB = 0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val c = pixel(x + dx, y + dy)
                    blurR += Color.red(c)
                    blurG += Color.green(c)
                    blurB += Color.blue(c)
                }
            }
            val r = (Color.red(center) * 1.35f - (blurR / 9f) * 0.35f).toInt().coerceIn(0, 255)
            val g = (Color.green(center) * 1.35f - (blurG / 9f) * 0.35f).toInt().coerceIn(0, 255)
            val b = (Color.blue(center) * 1.35f - (blurB / 9f) * 0.35f).toInt().coerceIn(0, 255)
            output[y * width + x] = Color.rgb(r, g, b)
        }
    }
    return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
}

private fun saveRawFusionPng(bitmap: Bitmap, file: File) {
    FileOutputStream(file).use {
        val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        if (!ok) error("Bitmap.compress PNG failed for ${file.name}")
    }
    if (!file.exists() || file.length() < 1024L) {
        error("PNG output invalid or too small: ${file.absolutePath}, size=${file.length()}")
    }
}

internal fun loadRawRgbaBitmap(file: File, width: Int, height: Int): Bitmap {
    val expectedBytes = width.toLong() * height.toLong() * 4L
    require(file.length() == expectedBytes) {
        "Native RGBA size mismatch: expected=$expectedBytes actual=${file.length()}"
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        val rowsPerChunk = 16
        val byteBuffer = ByteBuffer.allocate(width * rowsPerChunk * 4).order(ByteOrder.LITTLE_ENDIAN)
        val pixels = IntArray(width * rowsPerChunk)
        FileInputStream(file).use { input ->
            var y = 0
            while (y < height) {
                val rows = min(rowsPerChunk, height - y)
                val bytesNeeded = width * rows * 4
                byteBuffer.clear()
                var offset = 0
                while (offset < bytesNeeded) {
                    val read = input.read(byteBuffer.array(), offset, bytesNeeded - offset)
                    if (read < 0) error("Unexpected EOF in native RGBA output")
                    offset += read
                }
                var p = 0
                var b = 0
                while (p < width * rows) {
                    val r = byteBuffer.array()[b++].toInt() and 0xFF
                    val g = byteBuffer.array()[b++].toInt() and 0xFF
                    val blue = byteBuffer.array()[b++].toInt() and 0xFF
                    val a = byteBuffer.array()[b++].toInt() and 0xFF
                    pixels[p++] = Color.argb(a, r, g, blue)
                }
                bitmap.setPixels(pixels, 0, width, 0, y, width, rows)
                y += rows
            }
        }
        return bitmap
    } catch (t: Throwable) {
        bitmap.takeUnless { it.isRecycled }?.recycle()
        throw t
    }
}
