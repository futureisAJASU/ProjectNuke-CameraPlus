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
private const val RAW_RENDER_VERSION = "native_raw_isp_v0.3"
private const val RAW_RENDER_SHARPEN_AMOUNT = 0.12f
private const val RAW_RENDER_DENOISE_STRENGTH = 0.35f
private const val RAW_RENDER_CHROMA_DENOISE_STRENGTH = 0.55f
private const val RAW_RENDER_DEBUG_MAX_DIMENSION = 1280
private const val ENABLE_KOTLIN_RAW_RENDER_FALLBACK = false
private const val RAW_PIPELINE_LOG_TAG = "KeplerRawPipeline"

private data class RawWhiteBalanceGains(
    val red: Float,
    val green: Float,
    val blue: Float,
    val redMean: Float,
    val greenMean: Float,
    val blueMean: Float,
    val warning: String?
)

private data class RawRenderToneResult(
    val bitmap: Bitmap,
    val exposureGain: Float,
    val shadowLift: Float,
    val highlightRollOff: Float
)

private data class RawRenderStats(
    val redMean: Float,
    val greenMean: Float,
    val blueMean: Float,
    val saturationEstimate: Float,
    val overBrightRatio: Float,
    val underBrightRatio: Float,
    val noiseEstimate: Float
)

private data class RawRenderMetadata(
    val cameraWbGains: FloatArray?,
    val colorTransform: FloatArray?,
    val warnings: Set<String>
)

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
    rawSpeedMode: RawSpeedMode = RawSpeedMode.BALANCED,
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
    val rawCaptureStartedAt = System.currentTimeMillis()
    var rawFirstImageDelayMs: Long? = null
    val rawFrameSaveTimesMs = mutableListOf<Long>()
    fun postCaptureProgress() {
        post("RAW 캡처 중입니다. 기기를 움직이지 마세요. saved $savedFrames/$requestedFrames, images $receivedImages/$requestedFrames, results $completedResults/$requestedFrames, failed $failedCaptures")
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
                .put(
                    "currentPipelineStage",
                    if (status.contains("FAILED") || status.contains("ABORTED")) "FAILED" else "CAPTURE"
                )
                .put("userCanMoveDevice", false)
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
        val adbDebugHint = "adb shell ls -la '${jobDir.absolutePath}'"
        Log.i(RAW_PIPELINE_LOG_TAG, "jobDirAbsolutePath=${jobDir.absolutePath}")

        val baseJob = JSONObject()
            .put("app", "Kepler Night Lab")
            .put("jobType", "RAW_NIGHT_FUSION")
            .put("status", "CAPTURING")
            .put("processStatus", "NOT_PROCESSED")
            .put("currentPipelineStage", "CAPTURE")
            .put("captureStageCompleteAt", JSONObject.NULL)
            .put("processingStartedAt", JSONObject.NULL)
            .put("userCanMoveDevice", false)
            .put("rawCaptureMs", JSONObject.NULL)
            .put("rawSaveTotalMs", JSONObject.NULL)
            .put("nativeAlignMs", JSONObject.NULL)
            .put("nativeMergeMs", JSONObject.NULL)
            .put("nativeIspRenderMs", JSONObject.NULL)
            .put("nativePreviewPrepareMs", JSONObject.NULL)
            .put("totalPipelineMs", JSONObject.NULL)
            .put("jobDirAbsolutePath", jobDir.absolutePath)
            .put("adbDebugHint", adbDebugHint)
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
            .put("rawSpeedMode", rawSpeedMode.name)
            .put("rawCaptureStartedAt", rawCaptureStartedAt)
            .put("rawDebugPreviewSkipped", rawSpeedMode == RawSpeedMode.BALANCED)
            .put(
                "rawDebugPreviewSkipReason",
                if (rawSpeedMode == RawSpeedMode.BALANCED) {
                    "RAW speed mode Balanced skips optional debug preview PNGs during processing."
                } else {
                    JSONObject.NULL
                }
            )
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
                .put("rawSpeedMode", rawSpeedMode.name)
                .put("rawCaptureStartedAt", rawCaptureStartedAt)
                .put("rawCaptureMs", System.currentTimeMillis() - rawCaptureStartedAt)
                .put("rawSaveTotalMs", rawFrameSaveTimesMs.sum())
                .put("captureStageCompleteAt", System.currentTimeMillis())
                .put("processingStartedAt", JSONObject.NULL)
                .put("userCanMoveDevice", true)
                .put("currentPipelineStage", "PROCESSING")
                .put("jobDirAbsolutePath", jobDir.absolutePath)
                .put("adbDebugHint", adbDebugHint)
                .put("rawFirstImageDelayMs", rawFirstImageDelayMs ?: JSONObject.NULL)
                .put(
                    "rawAverageFrameSaveMs",
                    rawFrameSaveTimesMs.takeIf { it.isNotEmpty() }?.average() ?: JSONObject.NULL
                )
                .put("rawTotalCaptureMs", System.currentTimeMillis() - rawCaptureStartedAt)
                .put("rawDebugPreviewSkipped", rawSpeedMode == RawSpeedMode.BALANCED)
                .put(
                    "rawDebugPreviewSkipReason",
                    if (rawSpeedMode == RawSpeedMode.BALANCED) {
                        "RAW speed mode Balanced skips optional debug preview PNGs during processing."
                    } else {
                        JSONObject.NULL
                    }
                )
                .put("captureCompleteness", completeness)
                .put("partialCapture", partial)
                .put("frames", frameObjects)
                .put("gyroFile", motionFiles?.first ?: JSONObject.NULL)
                .put("rotationVectorFile", motionFiles?.second ?: JSONObject.NULL)
                .put("capturedAt", System.currentTimeMillis())
            if (partial) completeJob.put("partialReason", partialReason)
            jobFile.writeText(completeJob.toString(2))
            Log.i(RAW_PIPELINE_LOG_TAG, "CAPTURE_COMPLETE jobDirAbsolutePath=${jobDir.absolutePath} savedFrames=$savedFrames/$requestedFrames partial=$partial")
            if (partial) {
                post("CAPTURE_COMPLETE_PARTIAL: 캡처가 완료되었습니다. 이제 기기를 움직여도 됩니다. saved $savedFrames/$requestedFrames frames")
            } else {
                post("CAPTURE_COMPLETE: 캡처가 완료되었습니다. 이제 기기를 움직여도 됩니다. saved $savedFrames/$requestedFrames frames")
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
                    val saveStartedAt = System.currentTimeMillis()
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
                    val saveMs = System.currentTimeMillis() - saveStartedAt
                    rawFrameSaveTimesMs += saveMs
                    post("RAW saved frame $savedFrames/$requestedFrames (${saveMs}ms)")
                    if (savedFrames == 1 || savedFrames == requestedFrames) {
                        writeJobStatus(jobFile, baseJob, "CAPTURING")
                    }
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
            if (rawFirstImageDelayMs == null) {
                rawFirstImageDelayMs = System.currentTimeMillis() - rawCaptureStartedAt
                post("RAW first image delay: ${rawFirstImageDelayMs}ms")
            }
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
        val nativeIspStartedAt = System.currentTimeMillis()
        Log.i(RAW_PIPELINE_LOG_TAG, "NATIVE_ISP_STARTED jobDirAbsolutePath=${context.files.jobDir.absolutePath}")
        context.onStatus("Native RAW ISP 렌더링 중입니다.")
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
        val nativeIspRenderMs = System.currentTimeMillis() - nativeIspStartedAt
        Log.i(RAW_PIPELINE_LOG_TAG, "NATIVE_ISP_COMPLETE jobDirAbsolutePath=${context.files.jobDir.absolutePath} nativeIspRenderMs=$nativeIspRenderMs")
        Log.i(
            RAW_PIPELINE_LOG_TAG,
            "nativeRawIspUsed=$nativePostprocessUsed native status=$postprocessStatus " +
                "nativePostprocessRgbaFile=${nativeRgbaFile.absolutePath}"
        )
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
                .put("currentPipelineStage", "FAILED")
                .put("nativeIspRenderMs", nativeIspRenderMs)
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
                context.onStatus("결과를 저장하는 중입니다.")
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
            .put("currentPipelineStage", "PROCESSING")
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
            .put("nativeIspRenderMs", nativeIspRenderMs)
            .put("nativePostprocessMetadataFile", nativeMetadataFile.name)
            .put("nativePostprocessRgbaFile", nativeRgbaFile.name)
            .put("fullSizeKotlinDemosaicUsed", false)
            .put("native24RawUsed", false)
            .put("highResRawInputUsed", true)
            .put("referenceFrameIndex", context.referenceSelection.index)
            .put("referenceFrameReason", context.referenceSelection.reason)
            .put("alignmentStatus", context.nativeMerge.alignmentStatus)
            .put("nativeRawMerge", context.nativeMerge.mergedOk)
            .put("alignmentFile", context.files.alignmentFile.name)
            .put(
                "mergedRawFormat",
                if (context.job.optString("rawFusionEngine") == "classic_raw_v1") {
                    "black_level_subtracted_classic_raw_v1_compact_raw16"
                } else {
                    "black_level_subtracted_aligned_compact_raw16"
                }
            )
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

    private fun exportStandardNativeRawIsp(context: RawFusionExportContext): RawFusionProcessResult {
        val targetSize = chooseRawDemosaicTarget(
            context.sensor.width,
            context.sensor.height,
            context.highResolutionRaw
        )
        val outputWidth = targetSize.first
        val outputHeight = targetSize.second
        val nativeRgbaFile = File(context.files.jobDir, "raw_fusion_final.rgba")
        val renderInputMetadataFile = File(context.files.jobDir, "raw_render_input_metadata.json")
        val renderDebugFile = File(context.files.jobDir, "raw_render_debug.json")
        val referenceDebugRgbaFile = File(context.files.jobDir, "raw_reference_render_debug.rgba")
        val mergedLinearDebugRgbaFile = File(context.files.jobDir, "raw_merged_linear_debug.rgba")
        val renderMetadata = averageFrameMetadataForRender(context.frames.metadata)
        val warnings = renderMetadata.warnings.toMutableSet()
        writeRawRenderInputMetadata(
            file = renderInputMetadataFile,
            context = context,
            renderMetadata = renderMetadata,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            warnings = warnings
        )
        val referencePath = context.frames.inputs.getOrNull(context.referenceSelection.index)
            ?.file
            ?.absolutePath
            ?: context.frames.inputs.firstOrNull()?.file?.absolutePath
        val nativeIspStartedAt = System.currentTimeMillis()
        Log.i(RAW_PIPELINE_LOG_TAG, "NATIVE_ISP_STARTED jobDirAbsolutePath=${context.files.jobDir.absolutePath}")
        context.onStatus("Native RAW ISP 렌더링 중입니다.")
        val status = runCatching {
            NativeRawEngine.processRaw16ToRgbOutputV2(
                mergedRawPath = context.files.mergedRawFile.absolutePath,
                referenceRawPath = referencePath,
                width = context.sensor.width,
                height = context.sensor.height,
                cfaPattern = context.sensor.cfa,
                blackLevel = 0,
                whiteLevel = context.sensor.whiteLevel - context.sensor.blackLevel,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                metadataJsonPath = renderInputMetadataFile.absolutePath,
                outputRgbaPath = nativeRgbaFile.absolutePath,
                outputDebugJsonPath = renderDebugFile.absolutePath,
                outputReferenceDebugRgbaPath = referenceDebugRgbaFile.absolutePath,
                outputMergedLinearDebugRgbaPath = mergedLinearDebugRgbaFile.absolutePath
            )
        }.getOrElse { "ERROR: ${it.javaClass.simpleName}: ${it.message}" }
        val expectedBytes = outputWidth.toLong() * outputHeight.toLong() * 4L
        val nativeOk = status.startsWith("OK:") &&
            nativeRgbaFile.exists() &&
            nativeRgbaFile.length() == expectedBytes &&
            renderDebugFile.exists()
        val nativeIspRenderMs = System.currentTimeMillis() - nativeIspStartedAt
        Log.i(RAW_PIPELINE_LOG_TAG, "NATIVE_ISP_COMPLETE jobDirAbsolutePath=${context.files.jobDir.absolutePath} nativeIspRenderMs=$nativeIspRenderMs")
        Log.i(
            RAW_PIPELINE_LOG_TAG,
            "nativeRawIspUsed=$nativeOk native status=$status " +
                "nativePostprocessRgbaFile=${nativeRgbaFile.absolutePath} rawRenderDebugFile=${renderDebugFile.absolutePath}"
        )
        if (!nativeOk) {
            val failed = applyNativeMergeMetadata(
                target = JSONObject(context.job.toString()),
                alignment = context.nativeMerge.alignmentMetadata
            )
                .put("processStatus", "NATIVE_RAW_ISP_FAILED_KEEPING_CACHE")
                .put("nativeRawIspUsed", false)
                .put("nativePostprocessUsed", false)
                .put("nativePostprocessStatus", status)
                .put("rawRenderVersion", RAW_RENDER_VERSION)
                .put("rawRenderInputMetadataFile", renderInputMetadataFile.name)
                .put("rawRenderDebugFile", renderDebugFile.name)
                .put("currentPipelineStage", "FAILED")
                .put("nativeIspRenderMs", nativeIspRenderMs)
                .put("processedAt", System.currentTimeMillis())
            context.files.jobFile.writeText(failed.toString(2))
            context.onStatus("PIPELINE_FAILED: Native RAW ISP failed; RAW cache kept. $status")
            return RawFusionProcessResult(false, context.files.mergedRawFile, null, null, null, "Native RAW ISP failed: $status")
        }
        val debug = runCatching { JSONObject(renderDebugFile.readText()) }.getOrNull()
        val nativeWarnings = debug?.optJSONArray("renderWarnings") ?: JSONArray()
        Log.i(
            RAW_PIPELINE_LOG_TAG,
            "metadataParsingSucceeded=${renderMetadata.cameraWbGains != null || renderMetadata.colorTransform != null} " +
                "rawRenderCameraWbGains=${renderMetadata.cameraWbGains != null} " +
                "rawRenderColorTransform=${renderMetadata.colorTransform != null}"
        )
        val hasWarnings = nativeWarnings.length() > 0 || warnings.isNotEmpty()
        val updated = applyNativeMergeMetadata(
            target = JSONObject(context.job.toString()),
            alignment = context.nativeMerge.alignmentMetadata
        )
            .put("processStatus", if (hasWarnings) "RAW_FUSION_COMPLETE_WITH_RENDER_WARNINGS" else "RAW_FUSION_COMPLETE")
            .put("currentPipelineStage", "PROCESSING")
            .put("rawRenderVersion", RAW_RENDER_VERSION)
            .put("nativeRawIspUsed", true)
            .put("nativePostprocessUsed", true)
            .put("nativePostprocessStatus", status)
            .put("nativeIspRenderMs", debug?.optLong("nativeIspRenderMs", nativeIspRenderMs) ?: nativeIspRenderMs)
            .put("nativePostprocessRgbaFile", nativeRgbaFile.name)
            .put("nativePostprocessMetadataFile", renderInputMetadataFile.name)
            .put("rawRenderInputMetadataFile", renderInputMetadataFile.name)
            .put("rawRenderDebugFile", renderDebugFile.name)
            .put("rawReferenceDebugFile", if (referenceDebugRgbaFile.exists()) referenceDebugRgbaFile.name else JSONObject.NULL)
            .put("rawMergedLinearDebugFile", if (mergedLinearDebugRgbaFile.exists()) mergedLinearDebugRgbaFile.name else JSONObject.NULL)
            .put("rawFinalRenderDebugFile", nativeRgbaFile.name)
            .put("nativeRawIspFullBufferFallbackUsed", debug?.optBoolean("nativeRawIspFullBufferFallbackUsed", true) ?: true)
            .put("rawRenderWarnings", nativeWarnings)
            .put("rawRenderColorTransform", renderMetadata.colorTransform?.let { floatArrayToJson(it) } ?: JSONObject.NULL)
            .put("rawRenderCameraWbGains", renderMetadata.cameraWbGains?.let { floatArrayToJson(it) } ?: JSONObject.NULL)
            .put("rawRenderDenoiseStrength", RAW_RENDER_DENOISE_STRENGTH)
            .put("rawRenderChromaDenoiseStrength", RAW_RENDER_CHROMA_DENOISE_STRENGTH)
            .put("rawRenderSharpenAmount", RAW_RENDER_SHARPEN_AMOUNT)
            .put("demosaicMethod", debug?.optString("demosaicMethod", "MHC_5X5_V0") ?: "MHC_5X5_V0")
            .put("demosaicFallbackPixelCount", debug?.optLong("demosaicFallbackPixelCount", 0L) ?: 0L)
            .put("mhcBoundaryFallbackUsed", debug?.optBoolean("mhcBoundaryFallbackUsed", false) ?: false)
            .put("weightAwareDenoiseUsed", debug?.optBoolean("weightAwareDenoiseUsed", false) ?: false)
            .put("mergeWeightMapAvailable", debug?.optBoolean("mergeWeightMapAvailable", false) ?: false)
            .put("mergeWeightMapFile", debug?.opt("mergeWeightMapFile") ?: JSONObject.NULL)
            .put("mergeRejectMapAvailable", debug?.optBoolean("mergeRejectMapAvailable", false) ?: false)
            .put("mergeRejectMapFile", debug?.opt("mergeRejectMapFile") ?: JSONObject.NULL)
            .put("chromaArtifactSuppressionUsed", debug?.optBoolean("chromaArtifactSuppressionUsed", false) ?: false)
            .put("adaptiveSharpenUsed", debug?.optBoolean("adaptiveSharpenUsed", false) ?: false)
            .put("sharpenSuppressionLowConfidenceUsed", debug?.optBoolean("sharpenSuppressionLowConfidenceUsed", false) ?: false)
            .put("finalOutputSource", "native_rgba")
            .put("fullSizeKotlinDemosaicUsed", false)
            .put("mergedRawFile", context.files.mergedRawFile.name)
            .put("finalFile", JSONObject.NULL)
            .put("usedFrameCount", context.frames.inputs.size)
            .put("requestedFrames", context.frames.requestedFrames)
            .put("savedFrames", context.frames.savedFrames)
            .put("captureCompleteness", context.frames.captureCompleteness)
            .put("partialCapture", context.frames.partialCapture || context.frames.inputs.size < context.frames.requestedFrames)
            .put("processingNotes", captureCompletenessNote(context.frames))
            .put("blackLevelUsed", context.sensor.blackLevel)
            .put("whiteLevelUsed", context.sensor.whiteLevel)
            .put("outputWidth", outputWidth)
            .put("outputHeight", outputHeight)
            .put("referenceFrameIndex", context.referenceSelection.index)
            .put("referenceFrameReason", context.referenceSelection.reason)
            .put("alignmentStatus", context.nativeMerge.alignmentStatus)
            .put("nativeRawMerge", context.nativeMerge.mergedOk)
            .put("alignmentFile", context.files.alignmentFile.name)
            .put("sensorOrientation", context.job.opt("sensorOrientation") ?: JSONObject.NULL)
            .put("outputOrientation", "UNROTATED_RAW_SENSOR_GRID")
            .put("processedAt", System.currentTimeMillis())
        context.files.jobFile.writeText(updated.toString(2))
        return RawFusionProcessResult(
            success = true,
            mergedRawFile = context.files.mergedRawFile,
            mergedDngFile = null,
            previewPngFile = null,
            finalPngFile = null,
            errorMessage = null,
            nativeRgbaFile = nativeRgbaFile,
            nativeRgbaWidth = outputWidth,
            nativeRgbaHeight = outputHeight
        )
    }

    private fun exportStandardBitmap(context: RawFusionExportContext): RawFusionProcessResult {
        val nativeResult = exportStandardNativeRawIsp(context)
        if (nativeResult.success) return nativeResult
        if (!ENABLE_KOTLIN_RAW_RENDER_FALLBACK) return nativeResult

        val previewFile = File(context.files.jobDir, "raw_fusion_preview.png")
        val finalFile = File(context.files.jobDir, "raw_fusion_final.png")
        val referenceDebugFile = File(context.files.jobDir, "raw_reference_render_debug.png")
        val mergedLinearDebugFile = File(context.files.jobDir, "raw_merged_linear_debug.png")
        val finalRenderDebugFile = File(context.files.jobDir, "raw_final_render_debug.png")
        val renderDebugFile = File(context.files.jobDir, "raw_render_debug.json")
        val skipDebugPreview = context.job.optString("rawSpeedMode") == RawSpeedMode.BALANCED.name
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
        var whiteBalanced: Bitmap? = null
        var toned: Bitmap? = null
        var denoised: Bitmap? = null
        var sharpened: Bitmap? = null
        var finalBitmap: Bitmap? = null
        var finalOutputWidth = targetSize.first
        var finalOutputHeight = targetSize.second
        var wbGains = RawWhiteBalanceGains(1f, 1f, 1f, 0f, 0f, 0f, null)
        var toneExposureGain = 1.0f
        var toneShadowLift = 0.0f
        var toneHighlightRollOff = 0.0f
        val warnings = mutableSetOf<String>()
        var mergedStats = RawRenderStats(0f, 0f, 0f, 0f, 0f, 0f, 0f)
        var finalStats = RawRenderStats(0f, 0f, 0f, 0f, 0f, 0f, 0f)
        var cfaScores = JSONObject()
        val renderMetadata = averageFrameMetadataForRender(context.frames.metadata)
        warnings += renderMetadata.warnings
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
            if (!skipDebugPreview) {
                saveRawFusionPng(preview, previewFile)
            }
            mergedStats = estimateRawRenderStats(preview)
            wbGains = computeRawWhiteBalanceGains(preview, renderMetadata.cameraWbGains)
            wbGains.warning?.let { warnings += it }
            cfaScores = computeCfaSanityScores(
                raw = mergedForDemosaic,
                width = context.sensor.width,
                height = context.sensor.height,
                whiteLevel = context.sensor.whiteLevel - context.sensor.blackLevel,
                currentCfa = context.sensor.cfa
            )
            if (cfaScores.optBoolean("possibleMismatch", false)) warnings += "POSSIBLE_CFA_MISMATCH"
            whiteBalanced = applyRawWhiteBalance(preview, wbGains)
            val colorCorrected = applyRawColorTransform(whiteBalanced, renderMetadata.colorTransform)
            whiteBalanced.recycle()
            whiteBalanced = colorCorrected
            saveRawFusionPng(whiteBalanced, mergedLinearDebugFile)
            context.onStatus("Processing RAW fusion: tone/sharpen...")
            toned = toneMapRawFusion(whiteBalanced).also {
                toneExposureGain = it.exposureGain
                toneShadowLift = it.shadowLift
                toneHighlightRollOff = it.highlightRollOff
            }.bitmap
            preview.recycle()
            preview = null
            whiteBalanced.recycle()
            whiteBalanced = null
            denoised = denoiseRawFusion(
                toned,
                denoiseStrength = RAW_RENDER_DENOISE_STRENGTH,
                chromaStrength = RAW_RENDER_CHROMA_DENOISE_STRENGTH
            )
            toned.recycle()
            toned = null
            sharpened = sharpenRawFusion(denoised, RAW_RENDER_SHARPEN_AMOUNT)
            denoised.recycle()
            denoised = null
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
            saveRawFusionPng(finalBitmap, finalRenderDebugFile)
            finalStats = estimateRawRenderStats(finalBitmap)
            if (finalStats.saturationEstimate < 0.035f) warnings += "LOW_SATURATION"
            if (finalStats.overBrightRatio > 0.12f) warnings += "OVER_BRIGHT"
            if (mergedStats.noiseEstimate > 0f && finalStats.noiseEstimate > mergedStats.noiseEstimate * 1.35f) {
                warnings += "NOISE_AMPLIFIED"
            }
            saveRawReferenceDebugRender(
                context = context,
                gains = wbGains,
                referenceDebugFile = referenceDebugFile
            )
        } finally {
            preview?.takeUnless { it.isRecycled }?.recycle()
            whiteBalanced?.takeUnless { it.isRecycled }?.recycle()
            toned?.takeUnless { it.isRecycled }?.recycle()
            denoised?.takeUnless { it.isRecycled }?.recycle()
            sharpened?.takeUnless { it.isRecycled }?.recycle()
            finalBitmap?.takeUnless { it.isRecycled }?.recycle()
        }
        writeRawRenderDebugJson(
            file = renderDebugFile,
            context = context,
            gains = wbGains,
            mergedStats = mergedStats,
            finalStats = finalStats,
            cfaScores = cfaScores,
            renderMetadata = renderMetadata,
            warnings = warnings,
            exposureGain = toneExposureGain,
            shadowLift = toneShadowLift,
            highlightRollOff = toneHighlightRollOff
        )

        val partialNote = captureCompletenessNote(context.frames)
        val notes = "RAW Fusion MVP: Bayer weighted merge, black-level correction, exposure/ISO normalization, simple bilinear demosaic, gray-world-ish tone. $partialNote Merged DNG skipped because CaptureResult metadata was not available after process restart. TODO gyro Bayer alignment, image micro-alignment, ghost suppression, RAW super-resolution/detail fusion, proper Camera2 color matrices."
        val updated = applyNativeMergeMetadata(
            target = JSONObject(context.job.toString()),
            alignment = context.nativeMerge.alignmentMetadata
        )
            .put("processStatus", if (warnings.isEmpty()) "RAW_FUSION_COMPLETE" else "RAW_FUSION_COMPLETE_WITH_RENDER_WARNINGS")
            .put("rawRenderVersion", RAW_RENDER_VERSION)
            .put(
                "rawRenderWhiteBalance",
                JSONObject()
                    .put("rGain", wbGains.red)
                    .put("gGain", wbGains.green)
                    .put("bGain", wbGains.blue)
                    .put("redMean", wbGains.redMean)
                    .put("greenMean", wbGains.greenMean)
                    .put("blueMean", wbGains.blueMean)
            )
            .put("rawRenderDenoiseStrength", RAW_RENDER_DENOISE_STRENGTH)
            .put("rawRenderChromaDenoiseStrength", RAW_RENDER_CHROMA_DENOISE_STRENGTH)
            .put("rawRenderSharpenAmount", RAW_RENDER_SHARPEN_AMOUNT)
            .put("rawRenderExposureGain", toneExposureGain)
            .put("rawRenderShadowLift", toneShadowLift)
            .put("rawRenderHighlightRollOff", toneHighlightRollOff)
            .put("rawRenderWarnings", JSONArray(warnings.toList()))
            .put("rawRenderColorTransform", renderMetadata.colorTransform?.let { floatArrayToJson(it) } ?: JSONObject.NULL)
            .put("rawRenderCameraWbGains", renderMetadata.cameraWbGains?.let { floatArrayToJson(it) } ?: JSONObject.NULL)
            .put("rawReferenceDebugFile", if (referenceDebugFile.exists()) referenceDebugFile.name else JSONObject.NULL)
            .put("rawMergedLinearDebugFile", mergedLinearDebugFile.name)
            .put("rawFinalRenderDebugFile", finalRenderDebugFile.name)
            .put("rawRenderDebugFile", renderDebugFile.name)
            .put("mergedRawFile", context.files.mergedRawFile.name)
            .put("mergedDngFile", JSONObject.NULL)
            .put("previewFile", if (skipDebugPreview) JSONObject.NULL else previewFile.name)
            .put("finalFile", finalFile.name)
            .put("rawDebugPreviewSkipped", skipDebugPreview)
            .put(
                "rawDebugPreviewSkipReason",
                if (skipDebugPreview) {
                    "RAW speed mode Balanced skips optional debug preview PNGs during processing."
                } else {
                    JSONObject.NULL
                }
            )
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
            if (skipDebugPreview) null else previewFile,
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
        val processingStartedAt = System.currentTimeMillis()
        job.put("processingStartedAt", processingStartedAt)
            .put("currentPipelineStage", "PROCESSING")
            .put("userCanMoveDevice", true)
        Log.i(
            RAW_PIPELINE_LOG_TAG,
            "PROCESSING_STARTED jobDirAbsolutePath=${jobDir.absolutePath} processStatus=${job.optString("processStatus")}"
        )
        onStatus("RAW 프레임을 정렬하는 중입니다.")
        applyRawFusionMemoryMetadata(job, estimateRawFusionMemory(pixelCountLong))
        jobFile.writeText(job.toString(2))
        val highResolutionRaw = pixelCountLong >= HIGH_RES_RAW_MIN_PIXELS
        val preparedFrames = prepareRawFusionFrames(jobDir, job, frames, pixelCount)
        val frameInputs = preparedFrames.inputs
        val frameMeta = preparedFrames.metadata
        val requestedFrames = preparedFrames.requestedFrames

        onStatus("RAW 프레임을 정렬하는 중입니다.")
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

        onStatus("RAW 프레임을 병합하는 중입니다. using ${frameInputs.size}/$requestedFrames frames")
        val mergedRawFile = File(jobDir, "merged_raw_classic_v1.raw16")
        val alignmentFile = File(jobDir, "raw_fusion_debug.json")
        val files = RawFusionFiles(jobDir, jobFile, mergedRawFile, alignmentFile)
        val classicMerge = runClassicRawFusionMerge(
            jobDir = jobDir,
            job = job,
            preparedFrames = preparedFrames,
            sensor = sensor,
            blackLevelEstimate = blackLevelEstimate,
            mergedRawFile = mergedRawFile,
            alignmentFile = alignmentFile,
            onStatus = onStatus
        )
        val classicMergedOk = classicMerge.success &&
            mergedRawFile.exists() &&
            mergedRawFile.length() >= pixelCount * 2L &&
            alignmentFile.exists()
        if (!classicMergedOk) {
            jobFile.writeText(job.toString(2))
            onStatus("Classic RAW fusion failed. RAW cache kept.")
            return RawFusionProcessResult(
                success = false,
                mergedRawFile = null,
                mergedDngFile = null,
                previewPngFile = null,
                finalPngFile = null,
                errorMessage = classicMerge.errorMessage ?: "Classic RAW fusion failed"
            )
        }
        jobFile.writeText(job.toString(2))

        onStatus("Native RAW ISP 렌더링 중입니다.")
        onStatus("결과를 저장하는 중입니다.")
        val outputMode = CaptureResolutionMode.entries.firstOrNull { it.name == job.optString("outputResolutionMode") }
            ?: CaptureResolutionMode.entries.firstOrNull { it.label == job.optString("resolutionMode") }
            ?: CaptureResolutionMode.MP12
        val exportResult = RawFusionExportCoordinator.export(
            RawFusionExportContext(
                files = files,
                job = job,
                sensor = sensor,
                frames = preparedFrames,
                blackLevelEstimate = blackLevelEstimate,
                whiteLevelEstimate = whiteLevelEstimate,
                referenceSelection = RawReferenceSelection(
                    classicMerge.referenceIndex,
                    classicMerge.referenceReason
                ),
                nativeMerge = NativeMergeOutcome(
                    mergedOk = false,
                    status = "OK: classic_raw_v1",
                    alignmentMetadata = classicMerge.debugMetadata,
                    alignmentStatus = classicMerge.alignmentStatus
                ),
                outputMode = outputMode,
                highResolutionRaw = highResolutionRaw,
                saveNativeMp24DebugPng = saveNativeMp24DebugPng,
                onStatus = onStatus
            )
        )
        runCatching {
            val updated = JSONObject(jobFile.readText())
            val pipelineStartedAt = updated.optLong("rawCaptureStartedAt", 0L)
                .takeIf { it > 0L }
                ?: updated.optLong("createdAt", System.currentTimeMillis())
            updated.put("totalPipelineMs", System.currentTimeMillis() - pipelineStartedAt)
            jobFile.writeText(updated.toString(2))
        }
        exportResult
    } catch (oom: OutOfMemoryError) {
        runCatching {
            val job = if (jobFile.exists()) JSONObject(jobFile.readText()) else JSONObject()
            job.put("processStatus", "OOM_FAILED_KEEPING_CACHE")
                .put("currentPipelineStage", "FAILED")
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

private fun computeRawWhiteBalanceGains(source: Bitmap, cameraGains: FloatArray?): RawWhiteBalanceGains {
    if (cameraGains != null && cameraGains.size >= 4) {
        val green = ((cameraGains[1] + cameraGains[2]) * 0.5f).coerceAtLeast(0.001f)
        return RawWhiteBalanceGains(
            red = (cameraGains[0] / green).coerceIn(0.6f, 2.5f),
            green = 1.0f,
            blue = (cameraGains[3] / green).coerceIn(0.6f, 2.5f),
            redMean = 0f,
            greenMean = 0f,
            blueMean = 0f,
            warning = null
        )
    }

    /*
        val targetSize = chooseRawDemosaicTarget(
            context.sensor.width,
            context.sensor.height,
            context.highResolutionRaw
        )
        val outputFallbackReason = when {
            context.outputMode == CaptureResolutionMode.MP50 ->
                "MP50 native RGBA output is downscaled for memory-safe export."
            context.outputMode == CaptureResolutionMode.MP24_FUSION && context.highResolutionRaw ->
                "MP24_FUSION native RGBA output is downscaled for memory-safe export."
            else -> null
        }
        val outputWidth = targetSize.first
        val outputHeight = targetSize.second
        val nativeRgbaFile = File(context.files.jobDir, "raw_fusion_final.rgba")
        val renderInputMetadataFile = File(context.files.jobDir, "raw_render_input_metadata.json")
        val renderDebugFile = File(context.files.jobDir, "raw_render_debug.json")
        val referenceDebugRgbaFile = File(context.files.jobDir, "raw_reference_render_debug.rgba")
        val mergedLinearDebugRgbaFile = File(context.files.jobDir, "raw_merged_linear_debug.rgba")
        val renderMetadata = averageFrameMetadataForRender(context.frames.metadata)
        val warnings = renderMetadata.warnings.toMutableSet()
        writeRawRenderInputMetadata(
            file = renderInputMetadataFile,
            context = context,
            renderMetadata = renderMetadata,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            warnings = warnings
        )
        val referencePath = context.frames.inputs.getOrNull(context.referenceSelection.index)
            ?.file
            ?.absolutePath
            ?: context.frames.inputs.firstOrNull()?.file?.absolutePath
        context.onStatus("Processing RAW fusion: native RAW ISP v0.2...")
        val status = runCatching {
            NativeRawEngine.processRaw16ToRgbOutputV2(
                mergedRawPath = context.files.mergedRawFile.absolutePath,
                referenceRawPath = referencePath,
                width = context.sensor.width,
                height = context.sensor.height,
                cfaPattern = context.sensor.cfa,
                blackLevel = 0,
                whiteLevel = context.sensor.whiteLevel - context.sensor.blackLevel,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                metadataJsonPath = renderInputMetadataFile.absolutePath,
                outputRgbaPath = nativeRgbaFile.absolutePath,
                outputDebugJsonPath = renderDebugFile.absolutePath,
                outputReferenceDebugRgbaPath = referenceDebugRgbaFile.absolutePath,
                outputMergedLinearDebugRgbaPath = mergedLinearDebugRgbaFile.absolutePath
            )
        }.getOrElse { "ERROR: ${it.javaClass.simpleName}: ${it.message}" }
        val expectedBytes = outputWidth.toLong() * outputHeight.toLong() * 4L
        val nativeOk = status.startsWith("OK:") &&
            nativeRgbaFile.exists() &&
            nativeRgbaFile.length() == expectedBytes &&
            renderDebugFile.exists()
        if (!nativeOk) {
            val failed = applyNativeMergeMetadata(
                target = JSONObject(context.job.toString()),
                alignment = context.nativeMerge.alignmentMetadata
            )
                .put("processStatus", "NATIVE_RAW_ISP_FAILED_KEEPING_CACHE")
                .put("nativeRawIspUsed", false)
                .put("nativePostprocessUsed", false)
                .put("nativePostprocessStatus", status)
                .put("rawRenderVersion", RAW_RENDER_VERSION)
                .put("rawRenderInputMetadataFile", renderInputMetadataFile.name)
                .put("rawRenderDebugFile", renderDebugFile.name)
                .put("processedAt", System.currentTimeMillis())
            context.files.jobFile.writeText(failed.toString(2))
            context.onStatus("PIPELINE_FAILED: Native RAW ISP failed; RAW cache kept. $status")
            return RawFusionProcessResult(
                success = false,
                mergedRawFile = context.files.mergedRawFile,
                mergedDngFile = null,
                previewPngFile = null,
                finalPngFile = null,
                errorMessage = "Native RAW ISP failed: $status"
            )
        }
        val debug = runCatching { JSONObject(renderDebugFile.readText()) }.getOrNull()
        val nativeWarnings = debug?.optJSONArray("renderWarnings") ?: JSONArray()
        val hasWarnings = nativeWarnings.length() > 0 || warnings.isNotEmpty()
        val partialNote = captureCompletenessNote(context.frames)
        val updated = applyNativeMergeMetadata(
            target = JSONObject(context.job.toString()),
            alignment = context.nativeMerge.alignmentMetadata
        )
            .put("processStatus", if (hasWarnings) "RAW_FUSION_COMPLETE_WITH_RENDER_WARNINGS" else "RAW_FUSION_COMPLETE")
            .put("rawRenderVersion", RAW_RENDER_VERSION)
            .put("nativeRawIspUsed", true)
            .put("nativePostprocessUsed", true)
            .put("nativePostprocessStatus", status)
            .put("nativePostprocessRgbaFile", nativeRgbaFile.name)
            .put("nativePostprocessMetadataFile", renderInputMetadataFile.name)
            .put("rawRenderInputMetadataFile", renderInputMetadataFile.name)
            .put("rawRenderDebugFile", renderDebugFile.name)
            .put("rawReferenceDebugFile", if (referenceDebugRgbaFile.exists()) referenceDebugRgbaFile.name else JSONObject.NULL)
            .put("rawMergedLinearDebugFile", if (mergedLinearDebugRgbaFile.exists()) mergedLinearDebugRgbaFile.name else JSONObject.NULL)
            .put("rawFinalRenderDebugFile", nativeRgbaFile.name)
            .put("rawRenderWarnings", nativeWarnings)
            .put("rawRenderColorTransform", renderMetadata.colorTransform?.let { floatArrayToJson(it) } ?: JSONObject.NULL)
            .put("rawRenderCameraWbGains", renderMetadata.cameraWbGains?.let { floatArrayToJson(it) } ?: JSONObject.NULL)
            .put("rawRenderDenoiseStrength", RAW_RENDER_DENOISE_STRENGTH)
            .put("rawRenderChromaDenoiseStrength", RAW_RENDER_CHROMA_DENOISE_STRENGTH)
            .put("rawRenderSharpenAmount", RAW_RENDER_SHARPEN_AMOUNT)
            .put("finalOutputSource", "native_rgba")
            .put("outputFallbackReason", outputFallbackReason ?: JSONObject.NULL)
            .put("fullSizeKotlinDemosaicUsed", false)
            .put("mergedRawFile", context.files.mergedRawFile.name)
            .put("finalFile", JSONObject.NULL)
            .put("usedFrameCount", context.frames.inputs.size)
            .put("requestedFrames", context.frames.requestedFrames)
            .put("savedFrames", context.frames.savedFrames)
            .put("captureCompleteness", context.frames.captureCompleteness)
            .put("partialCapture", context.frames.partialCapture || context.frames.inputs.size < context.frames.requestedFrames)
            .put("processingNotes", partialNote)
            .put("blackLevelUsed", context.sensor.blackLevel)
            .put("whiteLevelUsed", context.sensor.whiteLevel)
            .put("outputWidth", outputWidth)
            .put("outputHeight", outputHeight)
            .put("referenceFrameIndex", context.referenceSelection.index)
            .put("referenceFrameReason", context.referenceSelection.reason)
            .put("alignmentStatus", context.nativeMerge.alignmentStatus)
            .put("nativeRawMerge", context.nativeMerge.mergedOk)
            .put("alignmentFile", context.files.alignmentFile.name)
            .put("sensorOrientation", context.job.opt("sensorOrientation") ?: JSONObject.NULL)
            .put("outputOrientation", "UNROTATED_RAW_SENSOR_GRID")
            .put("processedAt", System.currentTimeMillis())
        context.files.jobFile.writeText(updated.toString(2))
        return RawFusionProcessResult(
            success = true,
            mergedRawFile = context.files.mergedRawFile,
            mergedDngFile = null,
            previewPngFile = null,
            finalPngFile = null,
            errorMessage = null,
            nativeRgbaFile = nativeRgbaFile,
            nativeRgbaWidth = outputWidth,
            nativeRgbaHeight = outputHeight
        )
    }
    */
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    var rSum = 0.0
    var gSum = 0.0
    var bSum = 0.0
    var count = 0
    val step = max(1, pixels.size / 250_000)
    var i = 0
    while (i < pixels.size) {
        val c = pixels[i]
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        val luma = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
        if (luma in 24..230 && r < 245 && g < 245 && b < 245) {
            rSum += r
            gSum += g
            bSum += b
            count++
        }
        i += step
    }
    if (count < 64) return RawWhiteBalanceGains(1f, 1f, 1f, 0f, 0f, 0f, "POSSIBLE_CFA_MISMATCH")
    val rMean = (rSum / count).toFloat().coerceAtLeast(1f)
    val gMean = (gSum / count).toFloat().coerceAtLeast(1f)
    val bMean = (bSum / count).toFloat().coerceAtLeast(1f)
    val warning = if (rMean < 2f || bMean < 2f) "POSSIBLE_CFA_MISMATCH" else null
    return RawWhiteBalanceGains(
        red = (gMean / rMean).coerceIn(0.6f, 2.5f),
        green = 1.0f,
        blue = (gMean / bMean).coerceIn(0.6f, 2.5f),
        redMean = rMean,
        greenMean = gMean,
        blueMean = bMean,
        warning = warning
    )
}

private fun applyRawColorTransform(source: Bitmap, matrix: FloatArray?): Bitmap {
    if (matrix == null || matrix.size < 9) return source.copy(Bitmap.Config.ARGB_8888, false)
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) {
        val c = pixels[i]
        val r = Color.red(c) / 255f
        val g = Color.green(c) / 255f
        val b = Color.blue(c) / 255f
        val rr = (matrix[0] * r + matrix[1] * g + matrix[2] * b).coerceIn(0f, 1f)
        val gg = (matrix[3] * r + matrix[4] * g + matrix[5] * b).coerceIn(0f, 1f)
        val bb = (matrix[6] * r + matrix[7] * g + matrix[8] * b).coerceIn(0f, 1f)
        pixels[i] = Color.rgb((rr * 255f).toInt(), (gg * 255f).toInt(), (bb * 255f).toInt())
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun applyRawWhiteBalance(source: Bitmap, gains: RawWhiteBalanceGains): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) {
        val c = pixels[i]
        pixels[i] = Color.rgb(
            (Color.red(c) * gains.red).toInt().coerceIn(0, 255),
            (Color.green(c) * gains.green).toInt().coerceIn(0, 255),
            (Color.blue(c) * gains.blue).toInt().coerceIn(0, 255)
        )
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun toneMapRawFusion(source: Bitmap): RawRenderToneResult {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val meanLuma = pixels
        .asSequence()
        .step(max(1, pixels.size / 100_000))
        .map {
            (Color.red(it) * 0.299f + Color.green(it) * 0.587f + Color.blue(it) * 0.114f) / 255f
        }
        .average()
        .takeIf { !it.isNaN() }
        ?.toFloat()
        ?: 0.35f
    val exposureGain = (0.35f / meanLuma.coerceAtLeast(0.08f)).coerceIn(0.75f, 1.6f)
    val shadowLift = if (meanLuma < 0.22f) 0.04f else 0.015f
    val highlightRollOff = 0.16f
    for (i in pixels.indices) {
        val color = pixels[i]
        val r = toneChannel(Color.red(color), exposureGain, shadowLift, highlightRollOff)
        val g = toneChannel(Color.green(color), exposureGain, shadowLift, highlightRollOff)
        val b = toneChannel(Color.blue(color), exposureGain, shadowLift, highlightRollOff)
        pixels[i] = Color.rgb(r, g, b)
    }
    return RawRenderToneResult(
        bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888),
        exposureGain = exposureGain,
        shadowLift = shadowLift,
        highlightRollOff = highlightRollOff
    )
}

private fun <T> Sequence<T>.step(step: Int): Sequence<T> = sequence {
    var index = 0
    for (item in this@step) {
        if (index % step == 0) yield(item)
        index++
    }
}

private fun toneChannel(value: Int, exposureGain: Float, shadowLift: Float, highlightRollOff: Float): Int {
    val x = (value / 255.0 * exposureGain).coerceIn(0.0, 1.0)
    val lifted = if (x < 0.28) (x + shadowLift * (1.0 - x / 0.28)).coerceAtMost(0.34) else x
    val rolled = lifted / (1.0 + highlightRollOff * lifted)
    val normalized = (rolled / (1.0 / (1.0 + highlightRollOff))).coerceIn(0.0, 1.0)
    return (normalized.pow(1.0 / 1.08) * 255.0).toInt().coerceIn(0, 255)
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

private fun denoiseRawFusion(source: Bitmap, denoiseStrength: Float, chromaStrength: Float): Bitmap {
    val width = source.width
    val height = source.height
    val input = IntArray(width * height)
    val output = IntArray(width * height)
    source.getPixels(input, 0, width, 0, 0, width, height)
    fun pixel(x: Int, y: Int): Int = input[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
    for (y in 0 until height) {
        for (x in 0 until width) {
            val center = pixel(x, y)
            val cr = Color.red(center)
            val cg = Color.green(center)
            val cb = Color.blue(center)
            val cl = cr * 0.299f + cg * 0.587f + cb * 0.114f
            var rSum = 0f
            var gSum = 0f
            var bSum = 0f
            var weightSum = 0f
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val c = pixel(x + dx, y + dy)
                    val l = Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f
                    if (abs(l - cl) <= 22f || (dx == 0 && dy == 0)) {
                        val w = if (dx == 0 && dy == 0) 2f else 1f
                        rSum += Color.red(c) * w
                        gSum += Color.green(c) * w
                        bSum += Color.blue(c) * w
                        weightSum += w
                    }
                }
            }
            val ar = rSum / weightSum
            val ag = gSum / weightSum
            val ab = bSum / weightSum
            val avgL = ar * 0.299f + ag * 0.587f + ab * 0.114f
            val lumaMix = denoiseStrength.coerceIn(0f, 1f)
            val chromaMix = chromaStrength.coerceIn(0f, 1f)
            val nr = (cr * (1f - lumaMix) + (avgL + (ar - avgL) * chromaMix) * lumaMix).toInt().coerceIn(0, 255)
            val ng = (cg * (1f - lumaMix) + (avgL + (ag - avgL) * chromaMix) * lumaMix).toInt().coerceIn(0, 255)
            val nb = (cb * (1f - lumaMix) + (avgL + (ab - avgL) * chromaMix) * lumaMix).toInt().coerceIn(0, 255)
            output[y * width + x] = Color.rgb(nr, ng, nb)
        }
    }
    return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
}

private fun sharpenRawFusion(source: Bitmap, amount: Float = RAW_RENDER_SHARPEN_AMOUNT): Bitmap {
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
            val r = (Color.red(center) * (1f + amount) - (blurR / 9f) * amount).toInt().coerceIn(0, 255)
            val g = (Color.green(center) * (1f + amount) - (blurG / 9f) * amount).toInt().coerceIn(0, 255)
            val b = (Color.blue(center) * (1f + amount) - (blurB / 9f) * amount).toInt().coerceIn(0, 255)
            output[y * width + x] = Color.rgb(r, g, b)
        }
    }
    return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
}

private fun estimateRawRenderStats(bitmap: Bitmap): RawRenderStats {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val step = max(1, pixels.size / 200_000)
    var rSum = 0.0
    var gSum = 0.0
    var bSum = 0.0
    var satSum = 0.0
    var over = 0
    var under = 0
    var noise = 0.0
    var count = 0
    var i = 0
    while (i < pixels.size) {
        val c = pixels[i]
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val luma = r * 0.299 + g * 0.587 + b * 0.114
        rSum += r
        gSum += g
        bSum += b
        satSum += if (maxC > 0) (maxC - minC).toDouble() / maxC.toDouble() else 0.0
        if (luma > 242.0) over++
        if (luma < 8.0) under++
        val x = i % width
        val y = i / width
        if (x + 1 < width && y + 1 < height) {
            val right = pixels[i + 1]
            val down = pixels[i + width]
            val lr = Color.red(right) * 0.299 + Color.green(right) * 0.587 + Color.blue(right) * 0.114
            val ld = Color.red(down) * 0.299 + Color.green(down) * 0.587 + Color.blue(down) * 0.114
            noise += abs(luma - lr) + abs(luma - ld)
        }
        count++
        i += step
    }
    val denom = max(1, count).toFloat()
    return RawRenderStats(
        redMean = (rSum / denom).toFloat(),
        greenMean = (gSum / denom).toFloat(),
        blueMean = (bSum / denom).toFloat(),
        saturationEstimate = (satSum / denom).toFloat(),
        overBrightRatio = over / denom,
        underBrightRatio = under / denom,
        noiseEstimate = (noise / denom / 255.0).toFloat()
    )
}

private fun computeCfaSanityScores(
    raw: ShortArray,
    width: Int,
    height: Int,
    whiteLevel: Int,
    currentCfa: Int
): JSONObject {
    val target = chooseDebugTarget(width, height, 640)
    val scores = JSONObject()
    var bestCfa = currentCfa
    var bestScore = 0f
    var currentScore = 0f
    for (cfa in 0..3) {
        val bitmap = demosaicBilinearDownscaled(raw, width, height, cfa, whiteLevel, target.first, target.second)
        val score = estimateRawRenderStats(bitmap).saturationEstimate
        bitmap.recycle()
        scores.put(cfaName(cfa), score)
        if (cfa == currentCfa) currentScore = score
        if (score > bestScore) {
            bestScore = score
            bestCfa = cfa
        }
    }
    return JSONObject()
        .put("scores", scores)
        .put("currentCfa", currentCfa)
        .put("currentCfaName", cfaName(currentCfa))
        .put("bestCfa", bestCfa)
        .put("bestCfaName", cfaName(bestCfa))
        .put("currentScore", currentScore)
        .put("bestScore", bestScore)
        .put("possibleMismatch", currentScore < 0.035f && bestScore > currentScore * 1.6f && bestCfa != currentCfa)
}

private fun cfaName(cfa: Int): String = when (cfa) {
    1 -> "GRBG"
    2 -> "GBRG"
    3 -> "BGGR"
    else -> "RGGB"
}

private fun chooseDebugTarget(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
    val scale = min(1.0, maxDimension.toDouble() / max(width, height).toDouble())
    val targetWidth = ((width * scale).toInt().coerceAtLeast(2) / 2) * 2
    val targetHeight = ((height * scale).toInt().coerceAtLeast(2) / 2) * 2
    return targetWidth to targetHeight
}

private fun saveRawReferenceDebugRender(
    context: RawFusionExportContext,
    gains: RawWhiteBalanceGains,
    referenceDebugFile: File
) {
    runCatching {
        val input = context.frames.inputs.getOrNull(context.referenceSelection.index)
            ?: context.frames.inputs.firstOrNull()
            ?: return
        val target = chooseDebugTarget(context.sensor.width, context.sensor.height, RAW_RENDER_DEBUG_MAX_DIMENSION)
        val raw = readRaw16(input.file, context.sensor.pixelCount)
        var demosaic: Bitmap? = null
        var balanced: Bitmap? = null
        var toned: Bitmap? = null
        try {
            demosaic = demosaicBilinearDownscaled(
                raw,
                context.sensor.width,
                context.sensor.height,
                context.sensor.cfa,
                context.sensor.whiteLevel - context.sensor.blackLevel,
                target.first,
                target.second
            )
            balanced = applyRawWhiteBalance(demosaic, gains)
            toned = toneMapRawFusion(balanced).bitmap
            saveRawFusionPng(toned, referenceDebugFile)
        } finally {
            demosaic?.takeUnless { it.isRecycled }?.recycle()
            balanced?.takeUnless { it.isRecycled }?.recycle()
            toned?.takeUnless { it.isRecycled }?.recycle()
        }
    }
}

private fun writeRawRenderDebugJson(
    file: File,
    context: RawFusionExportContext,
    gains: RawWhiteBalanceGains,
    mergedStats: RawRenderStats,
    finalStats: RawRenderStats,
    cfaScores: JSONObject,
    renderMetadata: RawRenderMetadata,
    warnings: Set<String>,
    exposureGain: Float,
    shadowLift: Float,
    highlightRollOff: Float
) {
    val json = JSONObject()
        .put("rawRenderVersion", RAW_RENDER_VERSION)
        .put("cfaPattern", context.sensor.cfa)
        .put("cfaPatternName", cfaName(context.sensor.cfa))
        .put("blackLevelUsed", context.sensor.blackLevel)
        .put("whiteLevelUsed", context.sensor.whiteLevel)
        .put("mergedRawFormat", context.job.optString("mergedRawFormat", "black_level_subtracted_classic_raw_v1_compact_raw16"))
        .put("redMean", mergedStats.redMean)
        .put("greenMean", mergedStats.greenMean)
        .put("blueMean", mergedStats.blueMean)
        .put("redGain", gains.red)
        .put("greenGain", gains.green)
        .put("blueGain", gains.blue)
        .put("cameraWbGains", renderMetadata.cameraWbGains?.let { floatArrayToJson(it) } ?: JSONObject.NULL)
        .put("colorCorrectionTransformUsed", renderMetadata.colorTransform?.let { floatArrayToJson(it) } ?: JSONObject.NULL)
        .put("saturationEstimate", finalStats.saturationEstimate)
        .put("renderToneVersion", "conservative_auto_exposure_v0_2")
        .put("renderExposureGain", exposureGain)
        .put("renderShadowLift", shadowLift)
        .put("renderHighlightRollOff", highlightRollOff)
        .put("denoiseVersion", "mild_bilateral_chroma_v0_1")
        .put("denoiseStrength", RAW_RENDER_DENOISE_STRENGTH)
        .put("chromaDenoiseStrength", RAW_RENDER_CHROMA_DENOISE_STRENGTH)
        .put("sharpenAmount", RAW_RENDER_SHARPEN_AMOUNT)
        .put("cfaSanityScores", cfaScores)
        .put("finalStats", JSONObject()
            .put("saturationEstimate", finalStats.saturationEstimate)
            .put("overBrightRatio", finalStats.overBrightRatio)
            .put("underBrightRatio", finalStats.underBrightRatio)
            .put("noiseEstimate", finalStats.noiseEstimate)
        )
        .put("warnings", JSONArray(warnings.toList()))
    file.writeText(json.toString(2))
}

private fun writeRawRenderInputMetadata(
    file: File,
    context: RawFusionExportContext,
    renderMetadata: RawRenderMetadata,
    outputWidth: Int,
    outputHeight: Int,
    warnings: Set<String>
) {
    val json = JSONObject()
        .put("rawRenderVersion", RAW_RENDER_VERSION)
        .put("cfaPattern", context.sensor.cfa)
        .put("blackLevelUsed", 0)
        .put("whiteLevelUsed", context.sensor.whiteLevel - context.sensor.blackLevel)
        .put("mergedRawFormat", "black_level_subtracted_compact_raw16")
        .put("wbGains", renderMetadata.cameraWbGains?.let { floatArrayToJson(it) } ?: JSONObject.NULL)
        .put("colorTransform3x3", renderMetadata.colorTransform?.let { floatArrayToJson(it) } ?: JSONObject.NULL)
        .put("outputWidth", outputWidth)
        .put("outputHeight", outputHeight)
        .put("demosaicMethod", "MHC_5X5_V0")
        .put("denoiseStrength", RAW_RENDER_DENOISE_STRENGTH)
        .put("chromaDenoiseStrength", RAW_RENDER_CHROMA_DENOISE_STRENGTH)
        .put("sharpenAmount", RAW_RENDER_SHARPEN_AMOUNT)
        .put("toneTargetMidGray", 0.42)
        .put("toneMaxShadowLift", 0.06)
        .put("highlightRolloff", 0.16)
        .put("warnings", JSONArray(warnings.toList()))
    file.writeText(json.toString(2))
}

private fun averageFrameMetadataForRender(frameMeta: List<JSONObject>): RawRenderMetadata {
    val warnings = mutableSetOf<String>()
    val gains = frameMeta.mapNotNull { parseRggbGainsOrNull(it.optString("colorCorrectionGains")) }
    val matrix = frameMeta.mapNotNull { parseColorTransform3x3OrNull(it.optString("colorCorrectionTransform")) }
    if (gains.isEmpty()) warnings += "WB_GAINS_MISSING"
    if (matrix.isEmpty()) warnings += "COLOR_TRANSFORM_MISSING"
    return RawRenderMetadata(
        cameraWbGains = gains.takeIf { it.isNotEmpty() }?.let { averageFloatArrays(it, 4) },
        colorTransform = matrix.takeIf { it.isNotEmpty() }?.let { averageFloatArrays(it, 9) },
        warnings = warnings
    )
}

private fun parseRggbGainsOrNull(text: String?): FloatArray? {
    val values = parseFloatTokens(text)
    if (values.size < 4) return null
    return floatArrayOf(values[0], values[1], values[2], values[3])
}

private fun parseColorTransform3x3OrNull(text: String?): FloatArray? {
    val values = parseFloatTokens(text)
    if (values.size < 9) return null
    return FloatArray(9) { values[it] }
}

private fun parseFloatTokens(text: String?): List<Float> {
    if (text.isNullOrBlank() || text == "null") return emptyList()
    return Regex("-?\\d+(?:\\.\\d+)?(?:/\\d+(?:\\.\\d+)?)?")
        .findAll(text)
        .mapNotNull { token ->
            val raw = token.value
            if (raw.contains('/')) {
                val parts = raw.split('/')
                val numerator = parts.getOrNull(0)?.toFloatOrNull()
                val denominator = parts.getOrNull(1)?.toFloatOrNull()
                if (numerator != null && denominator != null && denominator != 0f) numerator / denominator else null
            } else {
                raw.toFloatOrNull()
            }
        }
        .toList()
}

private fun averageFloatArrays(values: List<FloatArray>, count: Int): FloatArray {
    val out = FloatArray(count)
    values.forEach { value ->
        for (i in 0 until count) out[i] += value[i]
    }
    for (i in 0 until count) out[i] /= values.size.toFloat()
    return out
}

private fun floatArrayToJson(values: FloatArray): JSONArray {
    val array = JSONArray()
    values.forEach { array.put(it.toDouble()) }
    return array
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
