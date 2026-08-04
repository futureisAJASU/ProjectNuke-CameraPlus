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
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.StatFs
import android.util.Log
import android.util.Size
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

private const val ENABLE_YUV_MEMORY_BURST_BUFFER = true
private const val MAX_YUV_MEMORY_BUFFER_FRAMES = 6
private const val MAX_YUV_MEMORY_BUFFER_BYTES = 160L * 1024L * 1024L
private const val YUV_CAPTURE_LOG_TAG = "KeplerYuvCapture"
private const val MIN_YUV_CAPTURE_TIMEOUT_MS = 12_000L
private const val YUV_RGB_STORAGE_BYTES_PER_PIXEL_ESTIMATE = 4L

private enum class YuvRgbMatrix {
    BT601_FULL
}

private val DEFAULT_YUV_RGB_MATRIX = YuvRgbMatrix.BT601_FULL

private data class YuvCaptureFailureSnapshot(
    val jobFile: File?,
    val savedFrames: Int,
    val receivedImages: Int,
    val completedResults: Int,
    val failedCaptures: Int,
    val frames: List<YuvFrameManifestEntry>
)

private fun logYuvCaptureFailure(
    stage: String,
    throwable: Throwable? = null,
    detail: String? = null
) {
    val message = buildString {
        append("YUV_CAPTURE_FAILED: ")
        append(stage)
        if (!detail.isNullOrBlank()) {
            append(" - ")
            append(detail)
        }
    }
    if (throwable != null) {
        Log.e(YUV_CAPTURE_LOG_TAG, message, throwable)
    } else {
        Log.e(YUV_CAPTURE_LOG_TAG, message)
    }
}

private fun persistYuvCaptureFailure(
    snapshot: YuvCaptureFailureSnapshot,
    source: String,
    throwable: Throwable? = null,
    failureType: String? = null,
    failureMessage: String? = null
) {
    val jobFile = snapshot.jobFile ?: return
    runCatching {
        val job = if (jobFile.exists()) {
            JSONObject(NoFollowFileSystem.readTextVerified(jobFile))
        } else {
            JSONObject()
        }
        val framesArray = JSONArray()
        snapshot.frames.forEach { frame ->
            val frameObject = JSONObject()
                .put("index", frame.frameIndex)
                .put("frameIndex", frame.frameIndex)
                .put("file", frame.filename)
                .put("timestampNs", frame.timestampNs)
                .put("persisted", frame.persisted)
            if (frame.failure != null) frameObject.put("failure", frame.failure)
            framesArray.put(frameObject)
        }
        job.put("status", "CAPTURE_FAILED")
            .put("currentPipelineStage", "CAPTURE_FAILED")
            .put("processStatus", "CAPTURE_FAILED")
            .put("captureFailed", true)
            .put("captureFailureSource", source)
            .put("captureFailureType", failureType ?: throwable?.javaClass?.name ?: "Unknown")
            .put("captureFailureMessage", failureMessage ?: throwable?.message ?: "")
            .put("captureFailureStackTrace", throwable?.stackTraceToString() ?: "")
            .put("savedFrames", snapshot.savedFrames)
            .put("receivedImages", snapshot.receivedImages)
            .put("completedResults", snapshot.completedResults)
            .put("failedCaptures", snapshot.failedCaptures)
            .put("yuvRgbMatrix", DEFAULT_YUV_RGB_MATRIX.name)
            .put("frames", framesArray)
            .put("updatedAt", System.currentTimeMillis())
        KeplerJobMetadata.write(jobFile.parentFile ?: error("Job directory missing"), job)
    }.onFailure { persistError ->
        Log.w(YUV_CAPTURE_LOG_TAG, "Failed to persist YUV capture failure metadata", persistError)
    }
}

internal data class BufferedYuvFrame(
    val index: Int,
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val yRowStride: Int,
    val yPixelStride: Int,
    val uRowStride: Int,
    val uPixelStride: Int,
    val vRowStride: Int,
    val vPixelStride: Int
)

// YUV_420_888 plane buffers may include row padding and interleaved chroma storage.
// Use a conservative 3 bytes/pixel estimate rather than the tightly packed 1.5 bytes/pixel ideal.
private fun estimateYuvBufferBytes(width: Int, height: Int): Long =
    width.toLong() * height.toLong() * 3L

internal fun actualYuvPlaneBytes(image: Image): Long = image.planes.fold(0L) { total, plane ->
    // Match copyYuvFrameToMemory exactly: it duplicates each plane from position zero.
    val duplicate = plane.buffer.duplicate().apply { position(0) }
    val remaining = duplicate.remaining().toLong().coerceAtLeast(0L)
    if (Long.MAX_VALUE - total < remaining) Long.MAX_VALUE else total + remaining
}

private fun canUseYuvMemoryBuffer(width: Int, height: Int, frameCount: Int): Boolean {
    if (!ENABLE_YUV_MEMORY_BURST_BUFFER) return false
    if (frameCount > MAX_YUV_MEMORY_BUFFER_FRAMES) return false
    val estimated = estimateYuvBufferBytes(width, height) * frameCount
    return estimated <= MAX_YUV_MEMORY_BUFFER_BYTES
}

internal fun copyYuvFrameToMemory(image: Image, index: Int): BufferedYuvFrame {
    fun copyPlane(plane: Image.Plane): ByteArray {
        val buffer = plane.buffer.duplicate()
        buffer.position(0)
        return ByteArray(buffer.remaining()).also(buffer::get)
    }

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    return BufferedYuvFrame(
        index = index,
        timestampNs = image.timestamp,
        width = image.width,
        height = image.height,
        y = copyPlane(yPlane),
        u = copyPlane(uPlane),
        v = copyPlane(vPlane),
        yRowStride = yPlane.rowStride,
        yPixelStride = yPlane.pixelStride,
        uRowStride = uPlane.rowStride,
        uPixelStride = uPlane.pixelStride,
        vRowStride = vPlane.rowStride,
        vPixelStride = vPlane.pixelStride
    )
}

@SuppressLint("MissingPermission")
fun captureYuvBurstColorWithMotion(
    context: Context,
    cameraManager: CameraManager,
    cameraId: String,
    characteristics: CameraCharacteristics,
    outputDir: File,
    zoomRatio: Float,
    focusAeState: FocusAeState,
    resolutionMode: CaptureResolutionMode,
    captureMode: CaptureMode,
    frameCountMode: FrameCountMode,
    autoMinFrames: Int,
    autoMaxFrames: Int,
    manualFrames: Int,
    processingParams: ClassicYuvFusionParams,
    onStatus: (String) -> Unit,
    onComplete: (File, List<YuvFrameManifestEntry>) -> Unit,
    onError: (String, String?) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    val postStatusMsg: (String) -> Unit = { message ->
        if (!mainHandler.post { onStatus(message) }) runCatching { onStatus(message) }
    }

    fun postError(message: String, detail: String? = null) {
        logYuvCaptureFailure(message, detail = detail)
        if (!mainHandler.post { onError(message, detail) }) runCatching { onError(message, detail) }
    }

    val workerThread = HandlerThread("KeplerYuvBurstThread").apply { start() }
    val workerHandler = Handler(workerThread.looper)

    // --- Variables that need to be captured by lambdas ---
    var cameraDevice: CameraDevice? = null
    var burstReader: ImageReader? = null
    var jobFile: File? = null
    var manifest = mutableListOf<YuvFrameManifestEntry>()
    var yuvMemoryBufferUsed = false
    var yuvMemoryBufferEstimatedBytes = 0L
    var yuvCaptureRequestTemplate: String? = null
    var yuvCaptureRequestTemplateFallbackUsed = false
    var yuvCaptureRequestTemplateFailures = mutableListOf<String>()
    var plannedFrames = 0
    var framePlanReason = ""

    // --- Session reference (owner-based architecture) ---
    val sessionRef = java.util.concurrent.atomic.AtomicReference<YuvCaptureSession?>(null)

    // --- Pre-session failure helper ---
    fun failInit(stage: String, throwable: Throwable? = null, detail: String? = null) {
        postError(stage, detail)
    }

    // --- Post-session failure helper ---
    fun failDuringCapture(stage: String, throwable: Throwable? = null, detail: String? = null) {
        sessionRef.get()?.owner?.onCaptureFailed(throwable ?: IllegalStateException(stage), detail ?: stage)
        postError(stage, detail)
    }

    // --- Cleanup ---
    fun cleanup() {
        try {
            sessionRef.get()?.close()
        } catch (e: Exception) {
            Log.w(YUV_CAPTURE_LOG_TAG, "Session close failed", e)
        }
        try {
            burstReader?.close()
            burstReader = null
        } catch (e: Exception) {
            Log.w(YUV_CAPTURE_LOG_TAG, "ImageReader close failed", e)
        }
        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.w(YUV_CAPTURE_LOG_TAG, "CameraDevice close failed", e)
        }
        workerThread.quitSafely()
    }

    // --- Late camera callback logging ---
    fun logLateCameraCallback(source: String, detail: String? = null) {
        val session = sessionRef.get()
        val finished = session?.finished?.get() == true
        val terminal = session?.owner?.terminalState()?.status()
        val received = session?.owner?.completedResultsCount() ?: 0
        val frames = session?.accounting?.snapshot()?.receivedFrames ?: 0
        val workers = session?.boundedWorker?.activeCount() ?: 0
        val workQueue = session?.boundedWorker?.queuedCount() ?: 0
        val memo = when (terminal) {
            CaptureTerminalStatus.SUCCESS -> "terminal=SUCCESS"
            CaptureTerminalStatus.FAILED -> "terminal=FAILED"
            CaptureTerminalStatus.CANCELLED -> "terminal=CANCELLED"
            CaptureTerminalStatus.TIMED_OUT -> "terminal=TIMEOUT"
            null -> "terminal=null"
            else -> "terminal=$terminal"
        }
        val msg = "LATE_CAMERA_CALLBACK: source=$source finished=$finished $memo received=$received frames=$frames workers=$workers workQueue=$workQueue${if (!detail.isNullOrBlank()) " $detail" else ""}"
        Log.w(YUV_CAPTURE_LOG_TAG, msg)
    }

    // --- Cancellation handle ---
    var captureCancellationHandle: ScheduledExecutorService? = null
    fun registerCancellation(check: () -> Boolean) {
        captureCancellationHandle = Executors.newSingleThreadScheduledExecutor()
        captureCancellationHandle!!.scheduleAtFixedRate({ check() }, 0, 100, TimeUnit.MILLISECONDS)
    }

    // --- Capture failure snapshot ---
    fun captureFailureSnapshot(): YuvCaptureFailureSnapshot {
        val session = sessionRef.get()
        return YuvCaptureFailureSnapshot(
            jobFile = jobFile,
            savedFrames = manifest.count { it.persisted },
            receivedImages = session?.accounting?.snapshot()?.receivedFrames ?: 0,
            completedResults = session?.owner?.completedResultsCount() ?: 0,
            failedCaptures = session?.accounting?.snapshot()?.failedFrames ?: 0,
            frames = manifest
        )
    }

    workerHandler.post {
        try {
            // ---- Pre-session initialization (same as before) ----
            val yuvSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?: emptyArray()
            val yuvSize = chooseColorFusionSize(yuvSizes, resolutionMode)
            val width = yuvSize.width
            val height = yuvSize.height

            if (!canUseYuvMemoryBuffer(width, height, 8)) {
                yuvMemoryBufferUsed = false
                yuvMemoryBufferEstimatedBytes = 0
            }

            val requestedFrames = when (frameCountMode) {
                FrameCountMode.AUTO -> {
                    plannedFrames = (autoMinFrames + autoMaxFrames) / 2
                    framePlanReason = "Auto: middle of [$autoMinFrames, $autoMaxFrames]"
                    plannedFrames
                }
                FrameCountMode.MANUAL -> {
                    plannedFrames = manualFrames
                    framePlanReason = "Manual: $manualFrames"
                    plannedFrames
                }
            }

            // Create job directory
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val jobDir = File(outputDir, "YUV_NIGHT_FUSION_$timestamp")
            if (!jobDir.mkdirs()) {
                failInit("jobDir.mkdirs_failed")
                cleanup()
                return@post
            }
            jobFile = File(jobDir, JOB_JSON_FILE_NAME)
            val jobId = UUID.randomUUID().toString()

            // Initial job metadata
            val initialJob = JSONObject().apply {
                put("jobId", jobId)
                put("status", "CAPTURE_INIT")
                put("currentPipelineStage", "CAPTURE_INIT")
                put("processStatus", "CAPTURE_INIT")
                put("cameraId", cameraId)
                put("yuvWidth", width)
                put("yuvHeight", height)
                put("requestedFrames", requestedFrames)
                put("yuvMemoryBufferUsed", yuvMemoryBufferUsed)
                put("yuvMemoryBufferEstimatedBytes", yuvMemoryBufferEstimatedBytes)
                put("createdAt", System.currentTimeMillis())
                put("updatedAt", System.currentTimeMillis())
            }
            KeplerJobMetadata.write(jobDir, initialJob)

            postStatusMsg("YUV 버스트 캡처 준비 중... (${width}x$height, ${plannedFrames}프레임)")

            // Create ImageReader
            val readerFormat = if (yuvMemoryBufferUsed) ImageFormat.YUV_420_888 else ImageFormat.YUV_420_888
            val maxImages = if (yuvMemoryBufferUsed) MAX_YUV_MEMORY_BUFFER_FRAMES else plannedFrames + 2
            burstReader = ImageReader.newInstance(width, height, readerFormat, maxImages)

            val rotationDegrees = calculateResultRotationDegrees(characteristics)
            val maxRetainedBytes = if (yuvMemoryBufferUsed) MAX_YUV_MEMORY_BUFFER_BYTES else 0L

            // ---- SESSION CONSTRUCTION (owner-based) ----
            val session = YuvCaptureSession.create(
                dispatch = { event -> workerHandler.post { event.execute() }; true },
                outputDir = jobDir,
                frameCount = plannedFrames,
                rotationDegrees = rotationDegrees,
                workerCapacity = plannedFrames,
                maxRetainedBytes = maxRetainedBytes,
                workProcessor = YuvPngWorkProcessor(
                    encoder = object : YuvPngEncoder {
                        override fun encodeDirect(image: Image, candidate: File, rotationDegrees: Int) {
                            val bitmap = yuv420ToBitmap(image)
                            val rotated = rotateBitmapIfNeeded(bitmap, rotationDegrees)
                            try {
                                FileOutputStream(candidate).use { output ->
                                    if (!rotated.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                        throw IllegalStateException("Bitmap PNG compression returned false")
                                    }
                                    output.fd.sync()
                                }
                            } finally {
                                if (rotated !== bitmap) rotated.recycle()
                                bitmap.recycle()
                            }
                        }

                        override fun encodeBuffered(frame: BufferedYuvFrame, candidate: File, rotationDegrees: Int) {
                            val bitmap = yuv420BufferToBitmap(frame)
                            val rotated = rotateBitmapIfNeeded(bitmap, rotationDegrees)
                            try {
                                FileOutputStream(candidate).use { output ->
                                    if (!rotated.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                        throw IllegalStateException("Bitmap PNG compression returned false")
                                    }
                                    output.fd.sync()
                                }
                            } finally {
                                if (rotated !== bitmap) rotated.recycle()
                                bitmap.recycle()
                            }
                        }
                    },
                    committer = { candidate, finalFile ->
                        KeplerJobMetadata.atomicReplace(candidate, finalFile)
                    }
                ),
postStatus = postStatusMsg,
                postMainOrRun = { runnable -> if (!mainHandler.post(runnable)) runnable.run() },
                writeJobJson = { status, savedFrames, manifest ->
                    KeplerJobMetadata.update(jobDir) { current ->
                        current.put("status", status)
                            .put("currentPipelineStage", status)
                            .put("processStatus", status)
                            .put("savedFrames", savedFrames)
                            .put("updatedAt", System.currentTimeMillis())
                    }
                },
                saveMotionOnce = { dir ->
                    // Motion files are handled elsewhere; return null for now
                    null to null
                },
                onCaptureComplete = { finalJobDir ->
                    val finalFrames = sessionRef.get()?.accounting?.snapshot()?.manifest ?: emptyList()
                    mainHandler.post {
                        onComplete(finalJobDir, finalFrames)
                    }
                },
                onCaptureError = { message, cause ->
                    val msg = "YUV_CAPTURE_FAILED: $message"
                    logYuvCaptureFailure("capture_error", cause, message)
                    if (!mainHandler.post { onError(msg, message) }) runCatching { onError(msg, message) }
                }
            )
            sessionRef.set(session)

            // ImageReader listener routes through owner
            burstReader!!.setOnImageAvailableListener({ reader ->
                val session = sessionRef.get() ?: return@setOnImageAvailableListener
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (session.finished.get()) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                val frameIndex = session.accounting.snapshot().receivedFrames
                val isBuffered = yuvMemoryBufferUsed && frameIndex < MAX_YUV_MEMORY_BUFFER_FRAMES
                if (isBuffered) {
                    session.owner.acceptBuffered(Camera2YuvImageAccess(image))
                } else {
                    session.owner.acceptDirect(Camera2DirectYuvImageAccess(image))
                }
            }, workerHandler)

            // Open camera
            val stateCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    if (sessionRef.get()?.finished?.get() == true) {
                        logLateCameraCallback("onOpened", "session already finished")
                        camera.close()
                        return
                    }
                    postStatusMsg("카메라 열림 - YUV 버스트 시작")
                    try {
                        val (requestBuilder, template) = createYuvBurstCaptureRequestBuilder(
                            camera = camera,
                            readerSurface = burstReader!!.surface,
                            characteristics = characteristics,
                            zoomRatio = zoomRatio,
                            focusAeState = focusAeState,
cameraId = cameraId,
                            postStatusMsg = postStatusMsg,
                            failureMessages = yuvCaptureRequestTemplateFailures
                        )
                        yuvCaptureRequestTemplate = yuvTemplateLabel(template)
                        yuvCaptureRequestTemplateFallbackUsed = (template != CameraDevice.TEMPLATE_STILL_CAPTURE)
                        updateYuvCaptureRequestTemplateMetadata(
                            jobFile!!, yuvCaptureRequestTemplate!!, yuvCaptureRequestTemplateFallbackUsed, yuvCaptureRequestTemplateFailures
                        )

                        val captureRequest = requestBuilder.build()
                        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                val s = sessionRef.get() ?: return
                                if (s.finished.get()) {
                                    logLateCameraCallback("onCaptureCompleted", "session finished")
                                    return
                                }
                                s.owner.onCaptureCompletedResult()
                            }

                            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                val s = sessionRef.get() ?: return
                                if (s.finished.get()) {
                                    logLateCameraCallback("onCaptureFailed", "session finished")
                                    return
                                }
                                val cause = IllegalStateException("Capture failed: reason=${failure.reason}")
                                s.owner.onCaptureFailed(cause, "captureRequest.failed")
                            }

                            override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                                // no-op
                            }

                            override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                                val s = sessionRef.get() ?: return
                                if (s.finished.get()) {
                                    logLateCameraCallback("onCaptureSequenceAborted", "session finished")
                                    return
                                }
                                s.owner.onCaptureFailed(IllegalStateException("Capture sequence aborted"), "captureSequenceAborted")
                            }
                        }

                        camera.createCaptureSession(listOf(burstReader!!.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                if (sessionRef.get()?.finished?.get() == true) {
                                    logLateCameraCallback("onConfigured", "session finished")
                                    session.close()
                                    return
                                }
                                try {
                                    session.setRepeatingBurst(listOf(captureRequest), captureCallback, workerHandler)
                                } catch (e: Exception) {
                                    val s = sessionRef.get() ?: return
                                    s.owner.onCaptureFailed(e, "repeatingBurst.startFailed")
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                val s = sessionRef.get() ?: return
                                s.owner.onCaptureFailed(IllegalStateException("Capture session configure failed"), "captureSession.configureFailed")
                            }
                        }, workerHandler)
                    } catch (e: Exception) {
                        val s = sessionRef.get() ?: return
                        s.owner.onCaptureFailed(e, "captureRequest.buildFailed")
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    val s = sessionRef.get() ?: return
                    if (!s.finished.get()) {
                        s.owner.onCaptureFailed(IllegalStateException("Camera disconnected"), "cameraDisconnected")
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    val s = sessionRef.get() ?: return
                    if (!s.finished.get()) {
                        s.owner.onCaptureFailed(IllegalStateException("Camera error: $error"), "cameraError:$error")
                    }
                }
            }

            cameraManager.openCamera(cameraId, stateCallback, workerHandler)

            // Timeout
            val timeoutMs = computeYuvCaptureTimeoutMs(plannedFrames, resolutionMode)
            val timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
            timeoutExecutor.schedule({
                val s = sessionRef.get() ?: return@schedule
                if (!s.finished.get()) {
                    s.owner.onDeadlineReached()
                }
            }, timeoutMs, TimeUnit.MILLISECONDS)

            // Cancellation check
            registerCancellation {
                val s = sessionRef.get() ?: return@registerCancellation true
                if (!s.finished.get()) {
                    s.owner.onCancellationRequested()
                }
                true
            }

        } catch (e: Exception) {
            failInit("captureYuvBurstColorWithMotion.setupFailed", e)
            cleanup()
        }
    }
}

private fun computeYuvCaptureTimeoutMs(
    frameCount: Int,
    resolutionMode: CaptureResolutionMode
): Long {
    val extraFrames = (frameCount - 6).coerceAtLeast(0)
    val extraFrameMs = extraFrames * 1_000L
    val resolutionExtraMs = when (resolutionMode) {
        CaptureResolutionMode.MP12 -> 0L
        CaptureResolutionMode.MP24_FUSION -> 4_000L
        CaptureResolutionMode.MP50 -> 8_000L
    }
    return (MIN_YUV_CAPTURE_TIMEOUT_MS + extraFrameMs + resolutionExtraMs)
        .coerceAtLeast(MIN_YUV_CAPTURE_TIMEOUT_MS)
}

private fun ensureSufficientSpaceForYuvBurstPngs(
    burstDir: File,
    frameCount: Int,
    outputWidth: Int,
    outputHeight: Int
): Boolean {
    return runCatching {
        val statFs = StatFs(burstDir.absolutePath)
        val availableBytes = statFs.availableBytes
        val estimatedBytes =
            outputWidth.toLong() * outputHeight.toLong() *
                YUV_RGB_STORAGE_BYTES_PER_PIXEL_ESTIMATE * frameCount
        availableBytes >= estimatedBytes
    }.getOrDefault(false)
}

private fun writeBitmapToTempPng(bitmap: Bitmap, finalFile: File) {
    val tempFile = File(finalFile.parentFile, ".${finalFile.name}.${System.nanoTime()}.tmp")
    try {
        FileOutputStream(tempFile).use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IllegalStateException("Bitmap PNG compression returned false")
            }
            output.fd.sync()
        }
        KeplerJobMetadata.atomicReplace(tempFile, finalFile)
    } catch (t: Throwable) {
        runCatching {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
        throw t
    }
}

fun averageLatestYuvBurstColor(
    context: Context,
    onStatus: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    fun postStatusMsg(message: String) {
        if (!mainHandler.post { onStatus(message) }) runCatching { onStatus(message) }
    }

    val workerThread = HandlerThread("KeplerAverageColorThread").apply { start() }
    val workerHandler = Handler(workerThread.looper)

    workerHandler.post {
        try {
            val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)

            if (picturesDir == null) {
                postStatusMsg("Pictures 폴더를 찾지 못함")
                workerThread.quitSafely()
                return@post
            }

            val colorRoot = File(picturesDir, "KeplerYuvFusion")

            if (NoFollowFileSystem.inspect(colorRoot.toPath()) is NoFollowInspection.Absent) {
                postStatusMsg("KeplerColorBurst 폴더가 없음. 먼저 Color Fusion 캡처를 해야 함.")
                workerThread.quitSafely()
                return@post
            }

            val latestJobDir = NoFollowFileSystem.requireDirectChildren(colorRoot)
                .filter { it.isDirectory && NoFollowFileSystem.optionalDirectChildFile(it, JOB_JSON_FILE_NAME) != null }
                ?.maxByOrNull { it.lastModified() }

            if (latestJobDir == null) {
                postStatusMsg("Color Fusion job을 찾지 못함")
                workerThread.quitSafely()
                return@post
            }

            val jobFile = NoFollowFileSystem.requireDirectChildFile(latestJobDir, JOB_JSON_FILE_NAME)
            val job = JSONObject(NoFollowFileSystem.readTextVerified(jobFile))
            val framesArray = job.getJSONArray("frames")

            if (framesArray.length() == 0) {
                postStatusMsg("job.json에 컬러 프레임이 없음")
                workerThread.quitSafely()
                return@post
            }

            val firstFileName = framesArray.getJSONObject(0).getString("file")
            val firstFile = NoFollowFileSystem.requireDirectChildFile(latestJobDir, firstFileName)
            val firstBitmap = BitmapFactory.decodeFile(firstFile.absolutePath)

            if (firstBitmap == null) {
                postStatusMsg("첫 컬러 프레임을 읽지 못함")
                workerThread.quitSafely()
                return@post
            }

            val width = firstBitmap.width
            val height = firstBitmap.height
            firstBitmap.recycle()
            val pixelCount = width * height

            val accR = IntArray(pixelCount)
            val accG = IntArray(pixelCount)
            val accB = IntArray(pixelCount)

            var usedFrames = 0

            postStatusMsg(
                "컬러 평균 합성 준비\n" +
                    "폴더: ${latestJobDir.name}\n" +
                    "해상도: ${width}x${height}\n" +
                    "프레임: ${framesArray.length()}장"
            )

            for (i in 0 until framesArray.length()) {
                val frameObj = framesArray.getJSONObject(i)
                val fileName = frameObj.getString("file")
                val frameFile = NoFollowFileSystem.requireDirectChildFile(latestJobDir, fileName)

                if (!frameFile.exists()) continue

                val bitmap = BitmapFactory.decodeFile(frameFile.absolutePath) ?: continue

                if (bitmap.width != width || bitmap.height != height) {
                    bitmap.recycle()
                    continue
                }

                val pixels = IntArray(pixelCount)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                for (p in 0 until pixelCount) {
                    val c = pixels[p]
                    accR[p] += Color.red(c)
                    accG[p] += Color.green(c)
                    accB[p] += Color.blue(c)
                }

                bitmap.recycle()
                usedFrames++

                postStatusMsg(
                    "컬러 평균 합성 중...\n" +
                        "사용 프레임: $usedFrames / ${framesArray.length()}"
                )
            }

            if (usedFrames == 0) {
                postStatusMsg("사용 가능한 컬러 프레임이 없음")
                workerThread.quitSafely()
                return@post
            }

            val outPixels = IntArray(pixelCount)

            for (p in 0 until pixelCount) {
                val r = accR[p] / usedFrames
                val g = accG[p] / usedFrames
                val b = accB[p] / usedFrames
                outPixels[p] = Color.rgb(r, g, b)
            }

            val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            outBitmap.setPixels(outPixels, 0, width, 0, 0, width, height)

            val outFile = File(latestJobDir, "average_color_rotated.png")

            FileOutputStream(outFile).use { output ->
                outBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }

            outBitmap.recycle()

            val updatedJob = JSONObject(job.toString())
                .put("processStatus", "AVERAGE_COLOR_COMPLETE")
                .put("averageColorFile", outFile.name)
                .put("averageUsedFrames", usedFrames)
                .put("processedAt", System.currentTimeMillis())

            KeplerJobMetadata.update(jobFile.parentFile ?: error("Job directory missing")) { current ->
                current.put("processStatus", updatedJob.get("processStatus"))
                    .put("averageColorFile", updatedJob.get("averageColorFile"))
                    .put("averageUsedFrames", updatedJob.get("averageUsedFrames"))
                    .put("processedAt", updatedJob.get("processedAt"))
            }

            postStatusMsg(
                "컬러 평균 합성 완료\n" +
                    "사용 프레임: $usedFrames 장\n" +
                    "결과:\n${outFile.absolutePath}\n" +
                    "크기: ${outFile.length() / 1024 / 1024} MB"
            )
        } catch (e: Exception) {
            postStatusMsg("컬러 평균 합성 실패\n${e.stackTraceToString()}")
        } finally {
            workerThread.quitSafely()
        }
    }
}

fun saveRotatedColorPngFromYuv(
    image: Image,
    outFile: File,
    rotationDegrees: Int
) {
    val bitmap = yuv420ToBitmap(image)
    val rotated = rotateBitmapIfNeeded(bitmap, rotationDegrees)
    writeBitmapToTempPng(rotated, outFile)

    if (rotated !== bitmap) {
        rotated.recycle()
    }

    bitmap.recycle()
}

private fun saveRotatedColorPngFromBufferedYuv(
    frame: BufferedYuvFrame,
    outFile: File,
    rotationDegrees: Int
) {
    val bitmap = yuv420BufferToBitmap(frame)
    val rotated = rotateBitmapIfNeeded(bitmap, rotationDegrees)
    writeBitmapToTempPng(rotated, outFile)
    if (rotated !== bitmap) rotated.recycle()
    bitmap.recycle()
}

private fun convertYuvToRgbBt601Full(
    yValue: Int,
    uValue: Int,
    vValue: Int
): Int {
    val r = clampToByte((yValue + 1.402f * vValue).toInt())
    val g = clampToByte((yValue - 0.344136f * uValue - 0.714136f * vValue).toInt())
    val b = clampToByte((yValue + 1.772f * uValue).toInt())
    return Color.rgb(r, g, b)
}

private fun yuv420BufferToBitmap(frame: BufferedYuvFrame): Bitmap {
    val pixels = IntArray(frame.width * frame.height)
    for (y in 0 until frame.height) {
        val yRow = y * frame.yRowStride
        val uvRow = y / 2
        for (x in 0 until frame.width) {
            val yValue = frame.y.safeGet(yRow + x * frame.yPixelStride).toInt() and 0xFF
            val uValue = (
                frame.u.safeGet(uvRow * frame.uRowStride + (x / 2) * frame.uPixelStride)
                    .toInt() and 0xFF
                ) - 128
            val vValue = (
                frame.v.safeGet(uvRow * frame.vRowStride + (x / 2) * frame.vPixelStride)
                    .toInt() and 0xFF
                ) - 128
            pixels[y * frame.width + x] = convertYuvToRgbBt601Full(yValue, uValue, vValue)
        }
    }
    return Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
    }
}

private fun ByteArray.safeGet(index: Int): Byte =
    if (index in indices) this[index] else 0

fun yuv420ToBitmap(image: Image): Bitmap {
    val width = image.width
    val height = image.height

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer

    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride

    val uRowStride = uPlane.rowStride
    val uPixelStride = uPlane.pixelStride

    val vRowStride = vPlane.rowStride
    val vPixelStride = vPlane.pixelStride

    val pixels = IntArray(width * height)

    for (y in 0 until height) {
        val yRow = y * yRowStride
        val uvRow = (y / 2)

        for (x in 0 until width) {
            val yIndex = yRow + x * yPixelStride
            val uIndex = uvRow * uRowStride + (x / 2) * uPixelStride
            val vIndex = uvRow * vRowStride + (x / 2) * vPixelStride

            val yValue = yBuffer.safeGet(yIndex).toInt() and 0xFF
            val uValue = (uBuffer.safeGet(uIndex).toInt() and 0xFF) - 128
            val vValue = (vBuffer.safeGet(vIndex).toInt() and 0xFF) - 128

            pixels[y * width + x] = convertYuvToRgbBt601Full(yValue, uValue, vValue)
        }
    }

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

    return bitmap
}

fun ByteBuffer.safeGet(index: Int): Byte {
    return if (index in 0 until limit()) {
        get(index)
    } else {
        0
    }
}

fun clampToByte(value: Int): Int {
    return when {
        value < 0 -> 0
        value > 255 -> 255
        else -> value
    }
}

fun rotateBitmapIfNeeded(
    bitmap: Bitmap,
    rotationDegrees: Int
): Bitmap {
    val normalized = ((rotationDegrees % 360) + 360) % 360

    if (normalized == 0) return bitmap

    val matrix = android.graphics.Matrix().apply {
        postRotate(normalized.toFloat())
    }

    return Bitmap.createBitmap(
        bitmap,
        0,
        0,
        bitmap.width,
        bitmap.height,
        matrix,
        true
    )
}

fun calculateResultRotationDegrees(
    characteristics: CameraCharacteristics,
    displayRotation: Int = Surface.ROTATION_0
): Int {
    return (resolveExportOrientation(
        ExportOrientationInput(
            sensorOrientationDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION),
            displayRotation = displayRotation,
            lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING),
            sourceWasDisplayUpright = false,
            rotationAlreadyApplied = false
        )
    ) as? ExportOrientationResolution.Resolved)?.degrees ?: 0
}

fun chooseColorFusionSize(
    yuvSizes: Array<Size>,
    resolutionMode: CaptureResolutionMode
): Size {
    fun megapixels(size: Size): Double {
        return size.width.toDouble() * size.height.toDouble() / 1_000_000.0
    }

    fun nearestTo(targetMp: Double): Size? {
        return yuvSizes.minByOrNull { size ->
            kotlin.math.abs(megapixels(size) - targetMp)
        }
    }

    return when (resolutionMode) {
        CaptureResolutionMode.MP12 -> {
            yuvSizes
                .filter { megapixels(it) <= 14.0 }
                .maxByOrNull { it.width * it.height }
                ?: nearestTo(12.0)
                ?: yuvSizes.maxByOrNull { it.width * it.height }
                ?: Size(1920, 1080)
        }

        CaptureResolutionMode.MP50 -> {
            // TODO: 50M depends on whether Camera2 exposes a >=40MP YUV stream for the selected camera.
            yuvSizes
                .filter { megapixels(it) >= 40.0 }
                .maxByOrNull { it.width * it.height }
                ?: yuvSizes.maxByOrNull { it.width * it.height }
                ?: Size(1920, 1080)
        }

        CaptureResolutionMode.MP24_FUSION -> {
            // 24M fusion intentionally captures a high-quality 12MP burst before tiled super-resolution.
            yuvSizes
                .filter { megapixels(it) <= 14.0 }
                .maxByOrNull { it.width * it.height }
                ?: nearestTo(12.0)
                ?: yuvSizes.maxByOrNull { it.width * it.height }
                ?: Size(1920, 1080)
        }
    }
}

private fun createYuvBurstCaptureRequestBuilder(
    camera: CameraDevice,
    readerSurface: Surface,
    characteristics: CameraCharacteristics,
    zoomRatio: Float,
    focusAeState: FocusAeState,
    cameraId: String,
    postStatusMsg: (String) -> Unit,
    failureMessages: MutableList<String>? = null
): Pair<CaptureRequest.Builder, Int> {
    val templates = listOf(
        CameraDevice.TEMPLATE_STILL_CAPTURE,
        CameraDevice.TEMPLATE_PREVIEW,
        CameraDevice.TEMPLATE_RECORD
    )
    val failures = mutableListOf<String>()
    for (template in templates) {
        val builder = try {
            camera.createCaptureRequest(template)
        } catch (e: Exception) {
            val message =
                "${yuvTemplateLabel(template)}: ${e.javaClass.simpleName}: ${e.message}"
            failures += message
            failureMessages?.add(message.take(240))
            Log.w("KeplerCaptureStatus", "YUV capture request template failed: $message")
            val next = templates.dropWhile { it != template }.drop(1).firstOrNull()
            if (next != null) {
                postStatusMsg(
                    "YUV capture request template ${yuvTemplateShortName(template)} failed; " +
                        "trying ${yuvTemplateShortName(next)}..."
                )
            }
            continue
        }
        builder.addTarget(readerSurface)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.toSet()
            .orEmpty()
        if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes) {
            runCatching {
                builder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }
        }
        runCatching {
            builder.set(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_FAST
            )
        }
        runCatching {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        }
        builder.applyZoomAndFocusAe(
            characteristics = characteristics,
            zoomRatio = zoomRatio,
            focusAeState = focusAeState,
            cameraId = cameraId
        )
        return builder to template
    }
    throw IllegalStateException(
        "YUV capture request template creation failed for all templates: " +
            failures.joinToString(" | ")
    )
}

private fun yuvTemplateLabel(template: Int): String = when (template) {
    CameraDevice.TEMPLATE_STILL_CAPTURE -> "STILL_CAPTURE"
    CameraDevice.TEMPLATE_PREVIEW -> "PREVIEW_FALLBACK"
    CameraDevice.TEMPLATE_RECORD -> "RECORD_FALLBACK"
    else -> "UNKNOWN_$template"
}

private fun yuvTemplateShortName(template: Int): String = when (template) {
    CameraDevice.TEMPLATE_STILL_CAPTURE -> "STILL"
    CameraDevice.TEMPLATE_PREVIEW -> "PREVIEW"
    CameraDevice.TEMPLATE_RECORD -> "RECORD"
    else -> "UNKNOWN_$template"
}

private fun updateYuvCaptureRequestTemplateMetadata(
    jobFile: File,
    template: String,
    fallbackUsed: Boolean,
    failures: List<String>
) {
    runCatching {
        KeplerJobMetadata.update(jobFile.parentFile ?: error("Job directory missing")) { current ->
            current.put("yuvCaptureRequestTemplate", template)
                .put("yuvCaptureRequestTemplateFallbackUsed", fallbackUsed)
                .put("yuvCaptureRequestTemplateFailures", JSONArray(failures.take(6)))
                .put("updatedAt", System.currentTimeMillis())
        }
    }
}

fun writeColorJobJson(
    jobFile: File,
    status: String,
    cameraId: String,
    width: Int,
    height: Int,
    outputWidth: Int,
    outputHeight: Int,
    rotationDegrees: Int,
    requestedFrames: Int,
    savedFrames: Int,
    frameManifest: List<YuvFrameManifestEntry>,
    gyroFile: String?,
    rotationVectorFile: String?,
    gyroSampleCount: Int,
    rotationVectorSampleCount: Int,
    motionInfo: String,
    resolutionMode: CaptureResolutionMode = CaptureResolutionMode.MP12,
    zoomRatio: Float = 1.0f,
    cropApplied: Boolean = false,
    physicalCameraId: String? = null,
    zoomRoute: ThreeXSourceMode = ThreeXSourceMode.OPTICAL,
    previewRoute: String? = null,
    routeFallbackReason: String? = null,
    frameCountMode: FrameCountMode = FrameCountMode.AUTO,
    plannedFrames: Int = requestedFrames,
    autoMinFrames: Int = 4,
    autoMaxFrames: Int = 8,
    manualFrames: Int = 4,
    framePlanReason: String = "Default",
    captureMode: CaptureMode = CaptureMode.MULTI_FRAME,
    processingParams: ClassicYuvFusionParams = ClassicYuvFusionPreset.NATURAL.params,
    yuvMemoryBufferUsed: Boolean = false,
    yuvMemoryBufferEstimatedBytes: Long = 0L,
    yuvCaptureRequestTemplate: String? = null,
    yuvCaptureRequestTemplateFallbackUsed: Boolean? = null,
    yuvCaptureRequestTemplateFailures: List<String>? = null,
    selectedRoute: ThreeXSourceMode = zoomRoute,
    actualRoute: String? = null,
    requestedPhysicalCameraId: String? = physicalCameraId,
    finalRequestZoom: Float = zoomRatio,
    requestedZoomRatio: Float = zoomRatio
) {
    val actualPhysicalCameraId =
        if (actualRoute == PhysicalCaptureRoute.PHYSICAL.name) physicalCameraId else null
    val previousJob = if (jobFile.exists()) {
        runCatching { JSONObject(jobFile.readText()) }.getOrNull()
    } else {
        null
    }
    val metadataRoute = inferMetadataZoomRoute(
        requestedUiZoomRatio = finalRequestZoom,
        captureZoomRatio = finalRequestZoom,
        physicalCameraId = actualPhysicalCameraId,
        cropApplied = cropApplied,
        previewRoute = previewRoute
    )
    val captureRouteValue =
        previousJob?.optString("captureRoute")?.takeUnless { it.isNullOrBlank() } ?: metadataRoute
    val framesArray = JSONArray()

    frameManifest.forEach { frame ->
        val frameObject = JSONObject()
            .put("index", frame.frameIndex)
            .put("frameIndex", frame.frameIndex)
            .put("file", frame.filename)
            .put("timestampNs", frame.timestampNs)
            .put("persisted", frame.persisted)
        if (frame.failure != null) frameObject.put("failure", frame.failure)

        framesArray.put(frameObject)
    }

    val motionObject = JSONObject()
        .put("gyroFile", gyroFile ?: JSONObject.NULL)
        .put("rotationVectorFile", rotationVectorFile ?: JSONObject.NULL)
        .put("gyroSampleCount", gyroSampleCount)
        .put("rotationVectorSampleCount", rotationVectorSampleCount)
        .put("info", motionInfo)

    val now = System.currentTimeMillis()

    val json = JSONObject()
        .put("app", "Kepler Night Lab")
        .put("jobType", if (captureMode == CaptureMode.SINGLE_FRAME) "YUV_SINGLE_FRAME" else "YUV_NIGHT_FUSION")
        .put("captureMode", captureMode.name)
        .put("processingPresetName", processingParams.clamped().presetName)
        .put("processingParams", processingParams.clamped().toJson())
        .put("fusionPresetName", processingParams.clamped().presetName)
        .put("fusionParams", processingParams.clamped().toJson())
        .put("status", status)
        .put("currentPipelineStage", status)
        .put("userCanMoveDevice", status == "CAPTURE_COMPLETE" || status == "PIPELINE_COMPLETE")
        .put(
            "captureStageCompleteAt",
            if (status == "CAPTURE_COMPLETE" || status == "PIPELINE_COMPLETE") {
                previousJob?.opt("captureStageCompleteAt")?.takeUnless { it == JSONObject.NULL } ?: now
            } else {
                JSONObject.NULL
            }
        )
        .put("processingStartedAt", previousJob?.opt("processingStartedAt") ?: JSONObject.NULL)
        .put(
            "processStatus",
            if (status == "PIPELINE_COMPLETE") {
                status
            } else {
                previousJob?.optString("processStatus", status) ?: status
            }
        )
        .put("frameCount", requestedFrames)
        .put("yuvWidth", width)
        .put("yuvHeight", height)
        .put("finalOutputSource", previousJob?.optString("finalOutputSource", "pending") ?: "pending")
        .put("finalFile", previousJob?.optString("finalFile", "") ?: "")
        .put("yuvFusionVersion", "YUV_NIGHT_FUSION_V0")
        .put("yuvAlignVersion", "YUV_GLOBAL_SHIFT_V0")
        .put("yuvMergeVersion", "YUV_TEMPORAL_GHOST_V0")
        .put("yuvDenoiseVersion", "YUV_LUMA_CHROMA_EDGE_AWARE_V0")
        .put("yuvDetailVersion", "YUV_LUMA_DETAIL_V0")
        .put("yuvSharpenVersion", "YUV_ADAPTIVE_LUMA_SHARPEN_V0")
        .put("yuvLookVersion", "YUV_NATURAL_NIGHT_LOOK_V0")
        .put("yuvRgbMatrix", DEFAULT_YUV_RGB_MATRIX.name)
        .put(
            "timing",
            previousJob?.optJSONObject("timing") ?: JSONObject()
                .put(
                    "yuvCaptureMs",
                    if (status == "CAPTURE_COMPLETE" || status == "PIPELINE_COMPLETE") {
                        now - (previousJob?.optLong("createdAt", now) ?: now)
                    } else {
                        0L
                    }
                )
                .put("yuvSaveMs", 0L)
                .put("yuvAlignMs", 0L)
                .put("yuvMergeMs", 0L)
                .put("yuvDenoiseMs", 0L)
                .put("yuvLookMs", 0L)
                .put("yuvExportMs", 0L)
                .put("totalPipelineMs", 0L)
        )
        .put("cameraId", cameraId)
        .put("selectedCameraId", cameraId)
        .put("physicalCameraId", actualPhysicalCameraId ?: JSONObject.NULL)
        .put("requestedPhysicalCameraId", requestedPhysicalCameraId ?: JSONObject.NULL)
        .put("selectedRoute", selectedRoute.name)
        .put("actualRoute", actualRoute ?: JSONObject.NULL)
        .put("requestedZoomRatio", requestedZoomRatio.toDouble())
        .put("requestedZoomRoute", zoomRoute.name)
        .put("finalZoomRoute", metadataRoute)
        .put("finalRequestZoom", finalRequestZoom.toDouble())
        .put("previewRoute", previewRoute ?: JSONObject.NULL)
        .put("captureRoute", captureRouteValue)
        .put("routeFallbackReason", routeFallbackReason ?: JSONObject.NULL)
        .put("resolutionMode", resolutionMode.label)
        .put("zoomRatio", finalRequestZoom.toDouble())
        .put("cropApplied", cropApplied)
        .put("frameCountMode", frameCountMode.label)
        .put("plannedFrames", plannedFrames)
        .put("autoMinFrames", autoMinFrames)
        .put("autoMaxFrames", autoMaxFrames)
        .put("manualFrames", manualFrames)
        .put("framePlanReason", framePlanReason)
        .put("width", width)
        .put("height", height)
        .put("outputWidth", outputWidth)
        .put("outputHeight", outputHeight)
        .put("rotationDegrees", rotationDegrees)
        .put("requestedFrames", requestedFrames)
        .put("savedFrames", savedFrames)
        .put("yuvMemoryBufferUsed", yuvMemoryBufferUsed)
        .put("yuvMemoryBufferEstimatedBytes", yuvMemoryBufferEstimatedBytes)
        .put("yuvMemoryBufferFrameLimit", MAX_YUV_MEMORY_BUFFER_FRAMES)
        .put("yuvMemoryBufferByteLimit", MAX_YUV_MEMORY_BUFFER_BYTES)
        .put(
            "yuvCaptureRequestTemplate",
            yuvCaptureRequestTemplate
                ?: previousJob?.optString("yuvCaptureRequestTemplate", "UNSELECTED")
                ?: "UNSELECTED"
        )
        .put(
            "yuvCaptureRequestTemplateFallbackUsed",
            yuvCaptureRequestTemplateFallbackUsed
                ?: previousJob?.optBoolean("yuvCaptureRequestTemplateFallbackUsed", false)
                ?: false
        )
        .put(
            "yuvCaptureRequestTemplateFailures",
            JSONArray(
                yuvCaptureRequestTemplateFailures
                    ?: previousJob?.optJSONArray("yuvCaptureRequestTemplateFailures")
                        ?.let { array -> List(array.length()) { array.optString(it) } }
                    ?: emptyList<String>()
            )
        )
        .put("frames", framesArray)
        .put("motion", motionObject)
        .put("updatedAt", now)

    if (previousJob == null) {
        json.put("createdAt", now)
    } else {
        val oldCreatedAt = previousJob.optLong("createdAt", now)

        json.put("createdAt", oldCreatedAt)
    }

    KeplerJobMetadata.write(jobFile.parentFile ?: error("Job directory missing"), json)
}