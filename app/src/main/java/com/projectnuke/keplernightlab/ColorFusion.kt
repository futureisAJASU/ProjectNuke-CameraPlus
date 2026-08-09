package com.projectnuke.keplernightlab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
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

private class JobFileHolder(var file: File)

private class ProductionMetadataWriter(
    private val jobFileHolder: JobFileHolder,
    private val cameraId: String,
    private val yuvWidth: Int,
    private val yuvHeight: Int,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val rotationDegrees: Int,
    private val requestedFrames: Int,
    private val resolutionMode: CaptureResolutionMode,
    private val requestedZoomRatio: Float,
    private val zoomRoute: ThreeXSourceMode,
    private val previewRoute: String?,
    private val routeFallbackReason: String?,
    private val frameCountMode: FrameCountMode,
    private val autoMinFrames: Int,
    private val autoMaxFrames: Int,
    private val manualFrames: Int,
    private val framePlanReason: String,
    private val captureMode: CaptureMode,
    private val processingParams: ClassicYuvFusionParams,
    private val yuvMemoryBufferUsed: Boolean,
    private val yuvMemoryBufferEstimatedBytes: Long,
    private val requestedPhysicalCameraId: String?
) {
    var finalZoomRoute: ThreeXSourceMode = zoomRoute
    var finalCropApplied: Boolean = false
    var finalRequestZoom: Float = requestedZoomRatio
    var actualRoute: String? = null
    var finalRequestZoomSet: Float = requestedZoomRatio
    var motionInfo: String = "not_started"
    var motionGyroFile: String? = null
    var motionRotationVectorFile: String? = null
    var motionGyroSampleCount: Int = 0
    var motionRotationVectorSampleCount: Int = 0
    var templateName: String? = null
    var templateFallbackUsed: Boolean? = null
    var templateFailures: List<String>? = null

    fun write(status: String, savedFrames: Int, manifest: List<YuvFrameManifestEntry>) {
        val actualPhysicalId =
            if (actualRoute == PhysicalCaptureRoute.PHYSICAL.name) requestedPhysicalCameraId else null
        writeColorJobJson(
            jobFile = jobFileHolder.file,
            status = status,
            cameraId = cameraId,
            width = yuvWidth,
            height = yuvHeight,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            rotationDegrees = rotationDegrees,
            requestedFrames = requestedFrames,
            savedFrames = savedFrames,
            frameManifest = manifest,
            gyroFile = motionGyroFile,
            rotationVectorFile = motionRotationVectorFile,
            gyroSampleCount = motionGyroSampleCount,
            rotationVectorSampleCount = motionRotationVectorSampleCount,
            motionInfo = motionInfo,
            resolutionMode = resolutionMode,
            zoomRatio = finalRequestZoom,
            cropApplied = finalCropApplied,
            physicalCameraId = actualPhysicalId,
            zoomRoute = finalZoomRoute,
            previewRoute = previewRoute,
            routeFallbackReason = routeFallbackReason,
            frameCountMode = frameCountMode,
            plannedFrames = requestedFrames,
            autoMinFrames = autoMinFrames,
            autoMaxFrames = autoMaxFrames,
            manualFrames = manualFrames,
            framePlanReason = framePlanReason,
            captureMode = captureMode,
            processingParams = processingParams,
            yuvMemoryBufferUsed = yuvMemoryBufferUsed,
            yuvMemoryBufferEstimatedBytes = yuvMemoryBufferEstimatedBytes,
            yuvCaptureRequestTemplate = templateName,
            yuvCaptureRequestTemplateFallbackUsed = templateFallbackUsed,
            yuvCaptureRequestTemplateFailures = templateFailures,
            selectedRoute = finalZoomRoute,
            actualRoute = actualRoute,
            requestedPhysicalCameraId = requestedPhysicalCameraId,
            finalRequestZoom = finalRequestZoomSet,
            requestedZoomRatio = requestedZoomRatio
        )
    }
}

@SuppressLint("MissingPermission")
fun captureYuvBurstColorWithMotion(
    context: Context,
    cameraId: String,
    frameCount: Int = 6,
    resolutionMode: CaptureResolutionMode = CaptureResolutionMode.MP12,
    zoomRatio: Float = 1.0f,
    requestedUiZoomRatio: Float,
    physicalCameraId: String? = null,
    zoomRoute: ThreeXSourceMode = ThreeXSourceMode.OPTICAL,
    previewRoute: String? = null,
    routeFallbackReason: String? = null,
    focusAeState: FocusAeState = FocusAeState(),
    frameCountMode: FrameCountMode = FrameCountMode.AUTO,
    autoMinFrames: Int = 4,
    autoMaxFrames: Int = 8,
    manualFrames: Int = 4,
    framePlanReason: String = "Default",
    captureMode: CaptureMode = CaptureMode.MULTI_FRAME,
    processingParams: ClassicYuvFusionParams = ClassicYuvFusionPreset.NATURAL.params,
    captureCancellationHandle: KeplerCaptureCancellationHandle = NoOpKeplerCaptureCancellationHandle,
    onComplete: (File) -> Unit = {},
    onError: (String) -> Unit = {},
    onStatus: (String) -> Unit
) {
        val mainHandler = Handler(Looper.getMainLooper())

    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val backgroundThread = HandlerThread("KeplerColorBurstThread").apply { start() }
    val backgroundHandler = Handler(backgroundThread.looper)
    val timeoutScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "KeplerColorBurstTimeout").apply { isDaemon = true }
    }

    // The REAL production seam: Main-thread dispatchers, pre-session terminal,
    // production resource coordinator, and exactly-once production cleanup are
    // constructed here (unit-tested as a seam, see ColorFusionProductionSeamTest).
    val productionSeam = YuvColorFusionProductionSeam(
        mainHandler = mainHandler,
        timeoutScheduler = timeoutScheduler,
        backgroundHandler = backgroundHandler,
        backgroundThread = backgroundThread,
        onStatus = onStatus,
        onError = onError
    )
    fun postStatus(message: String) {
        productionSeam.postStatus(message)
    }

    var motionLogger: MotionLogger? = null
    var cameraDevice: CameraDevice? = null
    var captureSession: CameraCaptureSession? = null
    var imageReader: ImageReader? = null

    val finished = AtomicBoolean(false)
    var motionSaved = false
    var motionFiles: Pair<String?, String?> = Pair(null, null)
    var motionInfo = "motion_not_started"
    var jobFile: File? = null
    var burstDir: File? = null
    var metadataWriter: ProductionMetadataWriter? = null
    var yuvSession: YuvCaptureSession? = null
    var jobFileHolder: JobFileHolder? = null

    fun logLateCameraCallback(callback: String) {
        val isFinished = yuvSession?.let { !it.terminalState.status().equals(CaptureTerminalStatus.ACTIVE) } ?: finished.get()
        Log.d(
            "KeplerCaptureCancel",
            "pipeline=YUV callback=$callback late=true finished=$isFinished " +
                "coordinatorPhase=${productionSeam.productionResourceCoordinator.lifecyclePhase()}"
        )
    }

    /**
     * Pre-session terminal path: handles failures BEFORE YuvCaptureSession is created.
     * Marks the outer pipeline terminal exactly once, publishes/logs the error,
     * dispatches onError through the safe callback dispatcher, closes already-created
     * production resources, and stops timeout/background infrastructure.
     *
     * After YuvCaptureSession becomes authoritative, this path is no longer used.
     */
    fun finishPreSessionError(
        message: String,
        source: String = "captureYuvBurstColorWithMotion.init",
        throwable: Throwable? = null,
        failureType: String? = null,
        failureMessage: String? = null
    ) {
        logYuvCaptureFailure(stage = source, throwable = throwable, detail = message)
        finished.set(true)
        productionSeam.preSessionTerminal.finish(message)
    }

    /**
     * Unified error path: routes to the pre-session path if yuvSession is null,
     * or to the owner if the session is authoritative.
     */
    fun finishError(
        message: String,
        source: String = "captureYuvBurstColorWithMotion.legacy",
        throwable: Throwable? = null,
        failureType: String? = null,
        failureMessage: String? = null
    ) {
        val session = yuvSession
        if (session != null) {
            session.owner.onCaptureFailed(throwable ?: RuntimeException(message), message)
        } else {
            finishPreSessionError(message, source, throwable, failureType, failureMessage)
        }
    }

    /**
     * Cancellation handling: covers both pre-session and post-session.
     * Before yuvSession exists, cancellation triggers pre-session terminal + cleanup.
     * After yuvSession exists, cancellation routes through the owner.
     */
    captureCancellationHandle.registerCleanupAction {
        val session = yuvSession
        if (session != null) {
            session.owner.onCancellationRequested()
        } else {
            // Pre-session cancellation: mark terminal and cleanup.
            finished.set(true)
            productionSeam.preSessionTerminal.finish("캡처가 취소되었습니다.")
        }
    }

    fun saveMotionOnce(dir: File): Pair<String?, String?> {
        if (motionSaved) return motionFiles

        return try {
            val logger = motionLogger
            if (logger == null) {
                motionSaved = true
                metadataWriter?.motionGyroSampleCount = 0
                metadataWriter?.motionRotationVectorSampleCount = 0
                motionFiles = Pair(null, null)
                motionFiles
            } else {
                logger.stop()
                motionFiles = logger.saveToDirectory(dir)
                motionSaved = true
                metadataWriter?.motionGyroFile = motionFiles.first
                metadataWriter?.motionRotationVectorFile = motionFiles.second
                metadataWriter?.motionGyroSampleCount = logger.gyroCount()
                metadataWriter?.motionRotationVectorSampleCount = logger.rotationVectorCount()
                motionFiles
            }
        } catch (e: Exception) {
            motionSaved = true
            motionFiles = Pair(null, null)
            postStatus("Motion 저장 실패, 컬러 프레임은 유지\n${e.stackTraceToString()}")
            motionFiles
        }
    }

    try {
        postStatus("Color Fusion 초기화 1/7: 카메라 특성 확인 중...")

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        if (map == null) {
            finishError(
                message = "Color Fusion 초기화 실패: StreamConfigurationMap이 null임",
                source = "captureYuvBurstColorWithMotion.init",
                failureType = "ConfigurationError",
                failureMessage = "StreamConfigurationMap is null"
            )
            return
        }

        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)

        if (yuvSizes.isNullOrEmpty()) {
            finishError(
                message = "Color Fusion 초기화 실패: YUV_420_888 출력 크기를 찾지 못함",
                source = "captureYuvBurstColorWithMotion.init",
                failureType = "ConfigurationError",
                failureMessage = "No YUV_420_888 output sizes"
            )
            return
        }

        val yuvSize = chooseColorFusionSize(yuvSizes, resolutionMode)
        val yuvMegapixels = yuvSize.width.toDouble() * yuvSize.height.toDouble() / 1_000_000.0
        val resolutionFallbackNote = if (
            resolutionMode == CaptureResolutionMode.MP50 &&
            yuvMegapixels < 40.0
        ) {
            "50M requested, but selected camera only exposed ${yuvSize.width}x${yuvSize.height}. Using max available."
        } else {
            null
        }
        val cropApplied = zoomRatio > 1f && buildCenterCropRegion(characteristics, zoomRatio) != null
        var finalRequestZoom = zoomRatio
        var finalCropApplied = cropApplied
        var actualCaptureRoute: PhysicalCaptureRoute? = null
        fun actualPhysicalCameraId(): String? =
            if (actualCaptureRoute == PhysicalCaptureRoute.PHYSICAL) physicalCameraId else null
        val rotationDegrees = calculateResultRotationDegrees(
            characteristics,
            context.display?.rotation ?: Surface.ROTATION_0
        )

        postStatus("Color Fusion 초기화 2/7: 저장 폴더 준비 중...")

        val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)

        if (picturesDir == null) {
            finishError(
                message = "Color Fusion 초기화 실패: Pictures 폴더가 null임",
                source = "captureYuvBurstColorWithMotion.init",
                failureType = "StorageError",
                failureMessage = "Pictures directory is null"
            )
            return
        }

        val keplerDir = File(picturesDir, "KeplerYuvFusion").apply {
            if (!exists()) {
                val ok = mkdirs()
                if (!ok && !exists()) {
                    finishError(
                        message = "Color Fusion 초기화 실패: KeplerColorBurst 폴더 생성 실패\n$absolutePath",
                        source = "captureYuvBurstColorWithMotion.init",
                        failureType = "StorageError",
                        failureMessage = "Failed to create KeplerYuvFusion directory"
                    )
                    return
                }
            }
        }

        val burstTimestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss_SSS",
            Locale.US
        ).format(Date())

        val currentBurstDir = File(keplerDir, "KPL_YUV_FUSION_${burstTimestamp}_${UUID.randomUUID().toString().take(8)}").apply {
            if (!exists()) {
                val ok = mkdirs()
                if (!ok && !exists()) {
                    finishError(
                        message = "Color Fusion 초기화 실패: Burst 폴더 생성 실패\n$absolutePath",
                        source = "captureYuvBurstColorWithMotion.init.storage.burstDir",
                        failureType = "StorageError",
                        failureMessage = "Failed to create KPL_YUV_FUSION burst directory"
                    )
                    return
                }
            }
        }

        burstDir = currentBurstDir
        val outputWidth = if (rotationDegrees == 90 || rotationDegrees == 270) yuvSize.height else yuvSize.width
        val outputHeight = if (rotationDegrees == 90 || rotationDegrees == 270) yuvSize.width else yuvSize.height
    var useMemoryBuffer = canUseYuvMemoryBuffer(
        yuvSize.width,
        yuvSize.height,
        frameCount
    )
        val estimatedBufferBytes =
            estimateYuvBufferBytes(yuvSize.width, yuvSize.height) * frameCount
        val captureTimeoutMs = computeYuvCaptureTimeoutMs(frameCount, resolutionMode)

        val currentJobFile = File(currentBurstDir, "job.json")
        jobFile = currentJobFile
        jobFileHolder = JobFileHolder(currentJobFile)
        var yuvCaptureRequestTemplate = "UNSELECTED"
        var yuvCaptureRequestTemplateFallbackUsed = false
        val yuvCaptureRequestTemplateFailures = mutableListOf<String>()

        postStatus("Color Fusion 초기화 3/7: job.json 생성 중...")

        writeColorJobJson(
            jobFile = currentJobFile,
            status = "CAPTURING",
            cameraId = cameraId,
            width = yuvSize.width,
            height = yuvSize.height,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            rotationDegrees = rotationDegrees,
            requestedFrames = frameCount,
            savedFrames = 0,
            frameManifest = emptyList(),
            gyroFile = null,
            rotationVectorFile = null,
            gyroSampleCount = 0,
            rotationVectorSampleCount = 0,
            motionInfo = "not_started",
            resolutionMode = resolutionMode,
            zoomRatio = zoomRatio,
            cropApplied = cropApplied,
            physicalCameraId = null,
            zoomRoute = zoomRoute,
            previewRoute = previewRoute,
            routeFallbackReason = routeFallbackReason,
            frameCountMode = frameCountMode,
            plannedFrames = frameCount,
            autoMinFrames = autoMinFrames,
            autoMaxFrames = autoMaxFrames,
            manualFrames = manualFrames,
            framePlanReason = framePlanReason,
            captureMode = captureMode,
            processingParams = processingParams,
            yuvMemoryBufferUsed = useMemoryBuffer,
            yuvMemoryBufferEstimatedBytes = estimatedBufferBytes,
            selectedRoute = zoomRoute,
            actualRoute = actualCaptureRoute?.name,
            requestedPhysicalCameraId = physicalCameraId,
            finalRequestZoom = finalRequestZoom,
            requestedZoomRatio = zoomRatio
        )

        metadataWriter = ProductionMetadataWriter(
            jobFileHolder = jobFileHolder!!,
            cameraId = cameraId,
            yuvWidth = yuvSize.width,
            yuvHeight = yuvSize.height,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            rotationDegrees = rotationDegrees,
            requestedFrames = frameCount,
            resolutionMode = resolutionMode,
            requestedZoomRatio = zoomRatio,
            zoomRoute = zoomRoute,
            previewRoute = previewRoute,
            routeFallbackReason = routeFallbackReason,
            frameCountMode = frameCountMode,
            autoMinFrames = autoMinFrames,
            autoMaxFrames = autoMaxFrames,
            manualFrames = manualFrames,
            framePlanReason = framePlanReason,
            captureMode = captureMode,
            processingParams = processingParams,
            yuvMemoryBufferUsed = useMemoryBuffer,
            yuvMemoryBufferEstimatedBytes = estimatedBufferBytes,
            requestedPhysicalCameraId = physicalCameraId
        )
        metadataWriter?.motionInfo = motionInfo

        val yuvWorkProcessor = YuvPngWorkProcessor(
            encoder = object : YuvPngEncoder {
                override fun encodeDirect(image: Image, candidate: File, rotationDegrees: Int) {
                    saveRotatedColorPngFromYuv(image, candidate, rotationDegrees)
                }

                override fun encodeBuffered(frame: BufferedYuvFrame, candidate: File, rotationDegrees: Int) {
                    saveRotatedColorPngFromBufferedYuv(frame, candidate, rotationDegrees)
                }
            },
            committer = YuvCandidateCommitter { candidate, finalFile ->
                KeplerJobMetadata.atomicReplace(candidate, finalFile)
            }
        )

        yuvSession = YuvCaptureSession.create(
            dispatch = { event -> backgroundHandler.post { event.execute() } },
            outputDir = currentBurstDir,
            frameCount = frameCount,
            rotationDegrees = rotationDegrees,
            workerCapacity = maxOf(2, minOf(frameCount, MAX_YUV_MEMORY_BUFFER_FRAMES)),
            maxRetainedBytes = MAX_YUV_MEMORY_BUFFER_BYTES,
            workProcessor = yuvWorkProcessor,
            postStatus = { msg -> productionSeam.statusDispatcher.dispatch(msg) },
                        dispatchCallback = productionSeam.callbackDispatcher,
            terminalMetadataWriter = YuvTerminalMetadataWriter { request ->
                metadataWriter?.write(request.jobStatus, request.savedFrames, request.manifest)
            },
            verifiedFileReader = YuvVerifiedFileReader { file ->
                NoFollowFileSystem.readBytesVerified(file)
            },
            terminalFinalVerifier = YuvTerminalFinalVerifier { file, frameIndex ->
                RealYuvFinalFileVerifier.verify(file, frameIndex)
            },
            onSessionTerminal = { request ->
                // Session terminal observer: consumes the SOLE published request and
                // dispatches exactly one user callback.  Runs on Main (the owner's
                // dispatchCallback already posted it); never infers the terminal
                // result from counters or elapsed time.
                when (request.completionKind) {
                    TerminalCompletionKind.SUCCESS -> {
                        onComplete(currentBurstDir)
                    }
                    TerminalCompletionKind.ERROR -> {
                        logYuvCaptureFailure(
                            stage = "terminal",
                            throwable = request.cause,
                            detail = request.reason ?: "YUV capture failed"
                        )
                        onError(request.reason ?: "YUV capture failed")
                    }
                }
            },
            saveMotionOnce = { dir -> saveMotionOnce(dir) },
            productionResourceCoordinator = productionSeam.productionResourceCoordinator,
            finished = finished,
            // ColorFusion starts the real terminal consumer only after the finite
            // captureBurst operation is accepted below.
            // Terminal consumption starts with session authority, before any
            // fallible ImageReader/camera/session/request setup.
            startTerminalObserverOnCreate = true
        )
        postStatus("Color Fusion 초기화 4/7: ImageReader 생성 중...")

        val reader = ImageReader.newInstance(
            yuvSize.width,
            yuvSize.height,
            ImageFormat.YUV_420_888,
            min(4, maxOf(2, frameCount))
        )

        when (productionSeam.productionResourceCoordinator.attachImageReader(reader)) {
            ProductionAttachmentDisposition.ACCEPTED -> imageReader = reader
            ProductionAttachmentDisposition.ALREADY_OWNED -> imageReader = reader
            ProductionAttachmentDisposition.SETTLED_LATE -> {
                logLateCameraCallback("ImageReader.attach")
                return
            }
            ProductionAttachmentDisposition.NO_RESOURCE -> error("new ImageReader was unexpectedly null")
        }
        if (useMemoryBuffer) {
            postStatus(
                "YUV memory buffer enabled: frames=$frameCount " +
                    "estimated=${estimatedBufferBytes / 1024L / 1024L}MB"
            )
            Log.i(
                "KeplerCaptureStatus",
                "YUV memory buffer enabled: frames=$frameCount " +
                    "estimated=${estimatedBufferBytes / 1024L / 1024L}MB"
            )
        } else {
            postStatus("YUV memory buffer disabled; using direct PNG save")
            Log.i(
                "KeplerCaptureStatus",
                "YUV memory buffer disabled; using direct PNG save"
            )
        }
        postStatus("YUV capture: saved 0/$frameCount")
        if (!ensureSufficientSpaceForYuvBurstPngs(currentBurstDir, frameCount, outputWidth, outputHeight)) {
            finishError(
                message = "YUV capture failed: insufficient free space for burst frame PNGs",
                source = "captureYuvBurstColorWithMotion.storage.freeSpace",
                failureType = "StorageError",
                failureMessage = "Insufficient free space before saving YUV burst PNG frames"
            )
            return
        }
        postStatus("YUV 캡처 중입니다. 기기를 움직이지 마세요.")

        postStatus("Color Fusion 초기화 5/7: 모션 센서 시작 중...")

        motionLogger = try {
            MotionLogger(context).also { logger ->
                motionInfo = logger.start()
                metadataWriter?.motionInfo = motionInfo
            }
        } catch (e: Exception) {
            motionInfo = "motion_failed_but_continue: ${e.javaClass.simpleName}: ${e.message}"
            metadataWriter?.motionInfo = motionInfo
            null
        }
        if (productionSeam.productionResourceCoordinator.attachMotionLogger(motionLogger) ==
            ProductionAttachmentDisposition.SETTLED_LATE
        ) {
            logLateCameraCallback("MotionLogger.attach")
            return
        }

        postStatus(
            "Color Fusion 준비 완료\n" +
                "Camera $cameraId\n" +
                "Resolution: ${resolutionMode.label}\n" +
                "Input: ${yuvSize.width}x${yuvSize.height}\n" +
                (resolutionFallbackNote?.let { "$it\n" } ?: "") +
                "Zoom: ${zoomRatio}x, cropApplied=$cropApplied\n" +
                "Output: ${outputWidth}x${outputHeight}\n" +
                "Rotation: ${rotationDegrees}도\n" +
                "Frames: $frameCount\n" +
                "Motion: $motionInfo\n" +
                "Folder:\n${currentBurstDir.absolutePath}"
        )

        fun isTerminalOrFinished(): Boolean =
            yuvSession?.let { it.terminalState.status() != CaptureTerminalStatus.ACTIVE } ?: finished.get()

        val imageBridge = YuvProductionImageBridge(
            ownerProvider = { yuvSession?.owner },
            isTerminal = ::isTerminalOrFinished,
            onAcquireFailure = { failure ->
                yuvSession?.owner?.onCaptureFailed(failure, "YUV acquire failed")
            },
            onReleaseFailure = { failure ->
                logYuvCaptureFailure(
                    stage = "image.release",
                    throwable = failure,
                    detail = "late YUV Image.close failed"
                )
            }
        )
        reader.setOnImageAvailableListener(
            { availableReader -> imageBridge.onImageAvailable(availableReader, useMemoryBuffer) },
            backgroundHandler
        )

        postStatus("Color Fusion 초기화 6/7: 카메라 여는 중...")

        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    when (productionSeam.productionResourceCoordinator.attachCameraDevice(camera)) {
                        ProductionAttachmentDisposition.ACCEPTED -> cameraDevice = camera
                        ProductionAttachmentDisposition.ALREADY_OWNED -> cameraDevice = camera
                        ProductionAttachmentDisposition.SETTLED_LATE -> {
                            logLateCameraCallback("CameraDevice.onOpened.lateAttach")
                            return
                        }
                        ProductionAttachmentDisposition.NO_RESOURCE -> return
                    }
                    if (isTerminalOrFinished()) {
                        logLateCameraCallback("CameraDevice.onOpened.afterAssign")
                        productionSeam.productionResourceCoordinator.perform()
                        return
                    }
                    postStatus("카메라 열림. Color Burst 세션 생성 중...")

                    try {
                        createRoutedStillCaptureSession(
                            camera = camera,
                            surface = reader.surface,
                            cameraId = cameraId,
                            physicalCameraId = physicalCameraId,
                            requestedUiZoomRatio = requestedUiZoomRatio,
                            requestedCaptureZoomRatio = zoomRatio,
                            selectedRoute = zoomRoute,
                            handler = backgroundHandler,
                            pipelineName = "YUV",
                            isFinished = { isTerminalOrFinished() },
                            onConfigured = { session, captureRoute ->
                                    when (productionSeam.productionResourceCoordinator.attachCaptureSession(session)) {
                                        ProductionAttachmentDisposition.ACCEPTED -> captureSession = session
                                        ProductionAttachmentDisposition.ALREADY_OWNED -> captureSession = session
                                        ProductionAttachmentDisposition.SETTLED_LATE -> {
                                            logLateCameraCallback("CameraCaptureSession.onConfigured.lateAttach")
                                            return@createRoutedStillCaptureSession
                                        }
                                        ProductionAttachmentDisposition.NO_RESOURCE -> return@createRoutedStillCaptureSession
                                    }
                                    if (isTerminalOrFinished()) {
                                        logLateCameraCallback("CameraCaptureSession.onConfigured.afterAssign")
                                        productionSeam.productionResourceCoordinator.perform()
                                        return@createRoutedStillCaptureSession
                                    }
                                    postStatus("Color Fusion 초기화 7/7: 세션 준비 완료. $frameCount 장 촬영 중...")

                                    try {
                                        actualCaptureRoute = captureRoute
                                        finalRequestZoom = captureRoute.finalRequestZoomRatio(zoomRatio)
                                        finalCropApplied = finalRequestZoom > 1f &&
                                            buildCenterCropRegion(characteristics, finalRequestZoom) != null
                                        metadataWriter?.finalZoomRoute = zoomRoute
                                        metadataWriter?.finalCropApplied = finalCropApplied
                                        metadataWriter?.finalRequestZoom = finalRequestZoom
                                        metadataWriter?.actualRoute = actualCaptureRoute?.name
                                        val requestZoomRatio = finalRequestZoom
                                        metadataWriter?.write(
                                            "CAPTURING",
                                            yuvSession?.accounting?.snapshot()?.persistedFrames ?: 0,
                                            yuvSession?.accounting?.snapshot()?.manifest ?: emptyList()
                                        )
                                        val requests = List(frameCount) {
                                            val (builder, selectedTemplate) =
                                                createYuvBurstCaptureRequestBuilder(
                                                    camera = camera,
                                                    readerSurface = reader.surface,
                                                    characteristics = characteristics,
                                                    zoomRatio = requestZoomRatio,
                                                    focusAeState = focusAeState,
                                                    cameraId = cameraId,
                                                    postStatus = ::postStatus,
                                                    failureMessages = yuvCaptureRequestTemplateFailures
                                                )
                                            yuvCaptureRequestTemplate =
                                                yuvTemplateLabel(selectedTemplate)
                                            yuvCaptureRequestTemplateFallbackUsed =
                                                selectedTemplate != CameraDevice.TEMPLATE_STILL_CAPTURE
                                            builder.build()
                                        }
                                        metadataWriter?.templateName = yuvCaptureRequestTemplate
                                        metadataWriter?.templateFallbackUsed = yuvCaptureRequestTemplateFallbackUsed
                                        metadataWriter?.templateFailures = yuvCaptureRequestTemplateFailures.toList()
                                        updateYuvCaptureRequestTemplateMetadata(
                                            jobFile = currentJobFile,
                                            template = yuvCaptureRequestTemplate,
                                            fallbackUsed = yuvCaptureRequestTemplateFallbackUsed,
                                            failures = yuvCaptureRequestTemplateFailures
                                        )
                                        Log.i(
                                            "KeplerCaptureStatus",
                                            "YUV capture request template selected: " +
                                                yuvCaptureRequestTemplate
                                        )
                                        postStatus(
                                            "YUV capture request template selected: " +
                                                yuvCaptureRequestTemplate
                                        )

                                        val callbackBridge = YuvProductionCameraCallbackBridge(
                                            ownerProvider = { yuvSession?.owner },
                                            isTerminal = ::isTerminalOrFinished,
                                            onCompleted = { result ->
                                                metadataWriter?.actualRoute = captureRoute?.name
                                                metadataWriter?.finalRequestZoomSet = requestZoomRatio
                                                Log.i(
                                                    "KeplerPhysicalRoute",
                                                    "capture completed selectedRoute=$zoomRoute actualRoute=$captureRoute " +
                                                        "requestedUiZoomRatio=$requestedUiZoomRatio " +
                                                        "requestedPhysicalCameraId=$physicalCameraId " +
                                                        "activePhysicalId=${result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)} " +
                                                        "finalRequestZoom=$requestZoomRatio"
                                                )
                                            },
                                            failureDetail = { failure ->
                                                "CaptureFailure reason=${failure.reason} sequenceId=${failure.sequenceId} " +
                                                    "frameNumber=${failure.frameNumber} wasImageCaptured=${failure.wasImageCaptured()}"
                                            }
                                        )
                                        YuvFiniteBurstSubmission().submit(
                                            session = session,
                                            requests = requests,
                                            callback = callbackBridge.callback(),
                                            handler = backgroundHandler,
                                            frameCount = frameCount
                                        )
                                        timeoutScheduler.schedule({
                                            yuvSession?.owner?.onDeadlineReached()
                                        }, captureTimeoutMs, TimeUnit.MILLISECONDS)
                                        // Terminal publication gate: parks (on a DEDICATED
                                        // daemon thread — never the owner dispatcher) until
                                        // the sole YuvTerminalRequest is published, the
                                        // session closes, or the defensive bound expires.
                                        // The terminal handoff observer is the only consumer
                                        // that chooses the final user callback. It receives a
                                        // typed Published / Closed / SettlementFailed / watchdog
                                        // result and never infers a terminal result from time.
                                        yuvSession?.startTerminalObservation(
                                            captureTimeoutMs + YUV_TERMINAL_SETTLE_MARGIN_MS
                                        )
                                    } catch (e: Exception) {
                                        val templateFailure =
                                            e.message?.contains(
                                                "YUV capture request template creation failed"
                                            ) == true
                                        if (templateFailure) {
                                            finishError(
                                                message = "PIPELINE_FAILED: ${e.message}",
                                                source = "captureYuvBurstColorWithMotion.captureRequest.template",
                                                throwable = e,
                                                failureType = "CaptureRequestError",
                                                failureMessage = yuvCaptureRequestTemplateFailures.joinToString(" | ").ifBlank {
                                                    e.message ?: "YUV capture request template creation failed"
                                                }
                                            )
                                        } else {
                                            finishError(
                                                message = "Color Burst 캡처 요청 실패",
                                                source = "captureYuvBurstColorWithMotion.captureRequest.submit",
                                                throwable = e,
                                                failureType = "CaptureRequestError",
                                                failureMessage = e.message ?: "Capture request submission failed"
                                            )
                                        }
                                    }
                            },

                            onFailed = { reason ->
                                    if (isTerminalOrFinished()) {
                                        logLateCameraCallback("CameraCaptureSession.onConfigureFailed")
                                        return@createRoutedStillCaptureSession
                                    }
                                    finishError(
                                        message = "Color Burst 세션 구성 실패: $reason",
                                        source = "captureYuvBurstColorWithMotion.session.configure",
                                        failureType = "SessionConfigurationFailed",
                                        failureMessage = reason
                                    )
                            }
                        )
                    } catch (e: Exception) {
                        finishError(
                            message = "Color Burst 세션 생성 실패",
                            source = "captureYuvBurstColorWithMotion.session.create",
                            throwable = e,
                            failureType = "SessionConfigurationFailed",
                            failureMessage = e.message ?: "Failed to create capture session"
                        )
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    if (isTerminalOrFinished()) {
                        logLateCameraCallback("CameraDevice.onDisconnected")
                        return
                    }
                    finishError(
                        message = "카메라 연결 해제됨",
                        source = "captureYuvBurstColorWithMotion.camera.disconnected",
                        failureType = "CameraDisconnected",
                        failureMessage = "CameraDevice disconnected during YUV capture"
                    )
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    if (isTerminalOrFinished()) {
                        logLateCameraCallback("CameraDevice.onError")
                        return
                    }
                    finishError(
                        message = "카메라 오류: $error",
                        source = "captureYuvBurstColorWithMotion.camera.error",
                        failureType = "CameraDeviceError",
                        failureMessage = "CameraDevice onError($error)"
                    )
                }
            },
            backgroundHandler
        )
    } catch (e: Exception) {
        finishError(
            message = "Color Fusion 초기화 실패",
            source = "captureYuvBurstColorWithMotion.init.catch",
            throwable = e,
            failureType = "CaptureRequestError",
            failureMessage = e.message ?: "Unexpected initialization failure"
        )
    }
}

/**
 * Terminal publication gate (production-only).  Parks on the session's SOLE
 * terminal-request publication:
 *
 *  - published request -> the session terminal observer already dispatched the
 *    single user callback; nothing more to do.
 *  - session closed before publication -> cancellation/closure; no user callback
 *    is synthesized.
 *  - defensive bound expired with no publication and no close -> invariant
 *    failure: record/log only.  This watchdog NEVER chooses the user-visible
 *    terminal result.
 *
 * Must run on a thread that is NOT the owner's serialized dispatcher (parking
 * the dispatcher would deadlock terminal settlement).
 */
/** Defensive bound for the terminal gate: capture timeout plus a generous settlement margin. */
private const val YUV_TERMINAL_SETTLE_MARGIN_MS = 15_000L

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
    commitProcessingArtifact(
        finalFile = finalFile,
        writeTemp = { temp ->
            FileOutputStream(temp).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Bitmap PNG compression returned false"
                }
                output.fd.sync()
            }
        },
        verifyFinal = { committed ->
            val signature = committed.inputStream().use { it.readNBytes(8) }
            check(signature.contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))) {
                "Bitmap PNG verification failed"
            }
        }
    )
}

fun averageLatestYuvBurstColor(
    context: Context,
    onStatus: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    val callbackDispatcher = ProcessingCallbackDispatcher(mainHandler, "KeplerColorFusion")
    fun postStatus(message: String) {
        val result = callbackDispatcher.dispatch { onStatus(message) }
        if (result != ProcessingCallbackDispatchResult.ACCEPTED) {
            Log.w("KeplerColorFusion", "status dispatch $result")
        }
    }

    val workerThread = HandlerThread("KeplerAverageColorThread").apply { start() }
    val workerHandler = Handler(workerThread.looper)

    workerHandler.post {
        try {
            val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)

            if (picturesDir == null) {
                postStatus("Pictures 폴더를 찾지 못함")
                workerThread.quitSafely()
                return@post
            }

            val colorRoot = File(picturesDir, "KeplerYuvFusion")

            if (NoFollowFileSystem.inspect(colorRoot.toPath()) is NoFollowInspection.Absent) {
                postStatus("KeplerColorBurst 폴더가 없음. 먼저 Color Fusion 캡처를 해야 함.")
                workerThread.quitSafely()
                return@post
            }

            val latestJobDir = NoFollowFileSystem.requireDirectChildren(colorRoot)
                .filter { it.isDirectory && NoFollowFileSystem.optionalDirectChildFile(it, JOB_JSON_FILE_NAME) != null }
                ?.maxByOrNull { it.lastModified() }

            if (latestJobDir == null) {
                postStatus("Color Fusion job을 찾지 못함")
                workerThread.quitSafely()
                return@post
            }

            val jobFile = NoFollowFileSystem.requireDirectChildFile(latestJobDir, JOB_JSON_FILE_NAME)
            val job = JSONObject(NoFollowFileSystem.readTextVerified(jobFile))
            val framesArray = job.getJSONArray("frames")

            if (framesArray.length() == 0) {
                postStatus("job.json에 컬러 프레임이 없음")
                workerThread.quitSafely()
                return@post
            }

            val firstFileName = framesArray.getJSONObject(0).getString("file")
            val firstFile = NoFollowFileSystem.requireDirectChildFile(latestJobDir, firstFileName)
            val firstBitmap = BitmapFactory.decodeFile(firstFile.absolutePath)

            if (firstBitmap == null) {
                postStatus("첫 컬러 프레임을 읽지 못함")
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

            postStatus(
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

                postStatus(
                    "컬러 평균 합성 중...\n" +
                        "사용 프레임: $usedFrames / ${framesArray.length()}"
                )
            }

            if (usedFrames == 0) {
                postStatus("사용 가능한 컬러 프레임이 없음")
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

            postStatus(
                "컬러 평균 합성 완료\n" +
                    "사용 프레임: $usedFrames 장\n" +
                    "결과:\n${outFile.absolutePath}\n" +
                    "크기: ${outFile.length() / 1024 / 1024} MB"
            )
        } catch (e: Exception) {
            postStatus("컬러 평균 합성 실패\n${e.stackTraceToString()}")
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

    val matrix = Matrix().apply {
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
    postStatus: (String) -> Unit,
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
                postStatus(
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
        runCatching { JSONObject(NoFollowFileSystem.readTextVerified(jobFile)) }.getOrNull()
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
