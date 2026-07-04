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
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
import kotlin.math.abs

private const val ENABLE_YUV_MEMORY_BURST_BUFFER = true
private const val MAX_YUV_MEMORY_BUFFER_FRAMES = 6
private const val MAX_YUV_MEMORY_BUFFER_BYTES = 160L * 1024L * 1024L

private data class BufferedYuvFrame(
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

private fun estimateYuvBufferBytes(width: Int, height: Int): Long =
    width.toLong() * height.toLong() * 3L / 2L

private fun canUseYuvMemoryBuffer(width: Int, height: Int, frameCount: Int): Boolean {
    if (!ENABLE_YUV_MEMORY_BURST_BUFFER) return false
    if (frameCount > MAX_YUV_MEMORY_BUFFER_FRAMES) return false
    val estimated = estimateYuvBufferBytes(width, height) * frameCount
    return estimated <= MAX_YUV_MEMORY_BUFFER_BYTES
}

private fun copyYuvFrameToMemory(image: Image, index: Int): BufferedYuvFrame {
    fun copyPlane(plane: Image.Plane): ByteArray {
        val buffer = plane.buffer.duplicate()
        buffer.position(0)
        return ByteArray(buffer.limit()).also(buffer::get)
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
    onComplete: (File) -> Unit = {},
    onError: (String) -> Unit = {},
    onStatus: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    fun postStatus(message: String) {
        mainHandler.post { onStatus(message) }
    }

    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val backgroundThread = HandlerThread("KeplerColorBurstThread").apply { start() }
    val backgroundHandler = Handler(backgroundThread.looper)

    var motionLogger: MotionLogger? = null
    var cameraDevice: CameraDevice? = null
    var captureSession: CameraCaptureSession? = null
    var imageReader: ImageReader? = null

    var savedFrames = 0
    var finished = false
    var motionSaved = false
    var motionFiles: Pair<String?, String?> = Pair(null, null)
    var motionInfo = "motion_not_started"

    val frameTimestampsNs = mutableListOf<Long>()
    val savedFrameFiles = mutableListOf<String>()

    fun cleanup() {
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { motionLogger?.stop() } catch (_: Exception) {}
        try { backgroundThread.quitSafely() } catch (_: Exception) {}
    }

    fun finish(message: String) {
        if (finished) return
        finished = true
        postStatus(message)
        cleanup()
    }

    fun saveMotionOnce(dir: File): Pair<String?, String?> {
        if (motionSaved) return motionFiles

        return try {
            val logger = motionLogger
            if (logger == null) {
                motionSaved = true
                motionFiles = Pair(null, null)
                motionFiles
            } else {
                logger.stop()
                motionFiles = logger.saveToDirectory(dir)
                motionSaved = true
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
            finish("Color Fusion 초기화 실패: StreamConfigurationMap이 null임")
            return
        }

        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)

        if (yuvSizes.isNullOrEmpty()) {
            finish("Color Fusion 초기화 실패: YUV_420_888 출력 크기를 찾지 못함")
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
        val rotationDegrees = calculateResultRotationDegrees(characteristics)

        postStatus("Color Fusion 초기화 2/7: 저장 폴더 준비 중...")

        val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)

        if (picturesDir == null) {
            finish("Color Fusion 초기화 실패: Pictures 폴더가 null임")
            return
        }

        val keplerDir = File(picturesDir, "KeplerYuvFusion").apply {
            if (!exists()) {
                val ok = mkdirs()
                if (!ok && !exists()) {
                    finish("Color Fusion 초기화 실패: KeplerColorBurst 폴더 생성 실패\n$absolutePath")
                    return
                }
            }
        }

        val burstTimestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val burstDir = File(keplerDir, "KPL_YUV_FUSION_$burstTimestamp").apply {
            if (!exists()) {
                val ok = mkdirs()
                if (!ok && !exists()) {
                    finish("Color Fusion 초기화 실패: Burst 폴더 생성 실패\n$absolutePath")
                    return
                }
            }
        }

        val outputWidth = if (rotationDegrees == 90 || rotationDegrees == 270) yuvSize.height else yuvSize.width
        val outputHeight = if (rotationDegrees == 90 || rotationDegrees == 270) yuvSize.width else yuvSize.height
        val useMemoryBuffer = canUseYuvMemoryBuffer(
            yuvSize.width,
            yuvSize.height,
            frameCount
        )
        val estimatedBufferBytes =
            estimateYuvBufferBytes(yuvSize.width, yuvSize.height) * frameCount

        val jobFile = File(burstDir, "job.json")
        var yuvCaptureRequestTemplate = "UNSELECTED"
        var yuvCaptureRequestTemplateFallbackUsed = false
        val yuvCaptureRequestTemplateFailures = mutableListOf<String>()

        postStatus("Color Fusion 초기화 3/7: job.json 생성 중...")

        writeColorJobJson(
            jobFile = jobFile,
            status = "CAPTURING",
            cameraId = cameraId,
            width = yuvSize.width,
            height = yuvSize.height,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            rotationDegrees = rotationDegrees,
            requestedFrames = frameCount,
            savedFrames = 0,
            frameFiles = emptyList(),
            frameTimestampsNs = emptyList(),
            gyroFile = null,
            rotationVectorFile = null,
            gyroSampleCount = 0,
            rotationVectorSampleCount = 0,
            motionInfo = "not_started",
            resolutionMode = resolutionMode,
            zoomRatio = zoomRatio,
            cropApplied = cropApplied,
            physicalCameraId = physicalCameraId,
            zoomRoute = zoomRoute,
            previewRoute = previewRoute,
            routeFallbackReason = routeFallbackReason,
            frameCountMode = frameCountMode,
            plannedFrames = frameCount,
            autoMinFrames = autoMinFrames,
            autoMaxFrames = autoMaxFrames,
            manualFrames = manualFrames,
            framePlanReason = framePlanReason,
            yuvMemoryBufferUsed = useMemoryBuffer,
            yuvMemoryBufferEstimatedBytes = estimatedBufferBytes
        )

        postStatus("Color Fusion 초기화 4/7: ImageReader 생성 중...")

        val reader = ImageReader.newInstance(
            yuvSize.width,
            yuvSize.height,
            ImageFormat.YUV_420_888,
            frameCount + 2
        )

        imageReader = reader
        val bufferedFrames = mutableListOf<BufferedYuvFrame>()
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
        postStatus("YUV 캡처 중입니다. 기기를 움직이지 마세요.")

        postStatus("Color Fusion 초기화 5/7: 모션 센서 시작 중...")

        motionLogger = try {
            MotionLogger(context).also { logger ->
                motionInfo = logger.start()
            }
        } catch (e: Exception) {
            motionInfo = "motion_failed_but_continue: ${e.javaClass.simpleName}: ${e.message}"
            null
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
                "Folder:\n${burstDir.absolutePath}"
        )

        reader.setOnImageAvailableListener(
            { r ->
                if (finished) return@setOnImageAvailableListener

                var image: Image? = null

                try {
                    image = r.acquireNextImage()

                    if (savedFrames >= frameCount) {
                        image.close()
                        return@setOnImageAvailableListener
                    }

                    val frameIndex = savedFrames
                    val imageTimestampNs = image.timestamp

                    if (useMemoryBuffer) {
                        val bufferedFrame = try {
                            copyYuvFrameToMemory(image, frameIndex)
                        } catch (oom: OutOfMemoryError) {
                            finish(
                                "YUV memory buffer failed: OutOfMemoryError before frame " +
                                    "${frameIndex + 1}/$frameCount"
                            )
                            return@setOnImageAvailableListener
                        }
                        image.close()
                        image = null
                        bufferedFrames.add(bufferedFrame)
                        savedFrames++
                        frameTimestampsNs.add(imageTimestampNs)
                        postStatus("YUV buffered frame $savedFrames/$frameCount")

                        if (savedFrames >= frameCount) {
                            val savedMotionFiles = saveMotionOnce(burstDir)
                            val finalLogger = motionLogger
                            bufferedFrames.sortedBy { it.index }.forEachIndexed { flushIndex, frame ->
                                val fileName =
                                    "frame_${frame.index.toString().padStart(2, '0')}_color.png"
                                saveRotatedColorPngFromBufferedYuv(
                                    frame = frame,
                                    outFile = File(burstDir, fileName),
                                    rotationDegrees = rotationDegrees
                                )
                                savedFrameFiles.add(fileName)
                                postStatus("YUV flushing frame ${flushIndex + 1}/${bufferedFrames.size}")
                            }
                            bufferedFrames.clear()
                            writeColorJobJson(
                                jobFile = jobFile,
                                status = "CAPTURE_COMPLETE",
                                cameraId = cameraId,
                                width = yuvSize.width,
                                height = yuvSize.height,
                                outputWidth = outputWidth,
                                outputHeight = outputHeight,
                                rotationDegrees = rotationDegrees,
                                requestedFrames = frameCount,
                                savedFrames = savedFrames,
                                frameFiles = savedFrameFiles,
                                frameTimestampsNs = frameTimestampsNs,
                                gyroFile = savedMotionFiles.first,
                                rotationVectorFile = savedMotionFiles.second,
                                gyroSampleCount = finalLogger?.gyroCount() ?: 0,
                                rotationVectorSampleCount = finalLogger?.rotationVectorCount() ?: 0,
                                motionInfo = motionInfo,
                                resolutionMode = resolutionMode,
                                zoomRatio = zoomRatio,
                                cropApplied = cropApplied,
                                physicalCameraId = physicalCameraId,
                                zoomRoute = zoomRoute,
                                previewRoute = previewRoute,
                                routeFallbackReason = routeFallbackReason,
                                frameCountMode = frameCountMode,
                                plannedFrames = frameCount,
                                autoMinFrames = autoMinFrames,
                                autoMaxFrames = autoMaxFrames,
                                manualFrames = manualFrames,
                                framePlanReason = framePlanReason,
                                yuvMemoryBufferUsed = useMemoryBuffer,
                                yuvMemoryBufferEstimatedBytes = estimatedBufferBytes
                            )
                            postStatus("CAPTURE_COMPLETE: 캡처가 완료되었습니다.")
                            onComplete(burstDir)
                            finish(
                                "Color Burst + Motion complete\n" +
                                    "Frames: $savedFrames\n" +
                                    "Output: ${outputWidth}x${outputHeight}\n" +
                                    "Folder:\n${burstDir.absolutePath}"
                            )
                        }
                        return@setOnImageAvailableListener
                    }

                    val fileName = "frame_${frameIndex.toString().padStart(2, '0')}_color.png"
                    val outFile = File(burstDir, fileName)

                    saveRotatedColorPngFromYuv(
                        image = image,
                        outFile = outFile,
                        rotationDegrees = rotationDegrees
                    )

                    savedFrames++
                    savedFrameFiles.add(fileName)
                    frameTimestampsNs.add(imageTimestampNs)

                    val logger = motionLogger

                    writeColorJobJson(
                        jobFile = jobFile,
                        status = "CAPTURING",
                        cameraId = cameraId,
                        width = yuvSize.width,
                        height = yuvSize.height,
                        outputWidth = outputWidth,
                        outputHeight = outputHeight,
                        rotationDegrees = rotationDegrees,
                        requestedFrames = frameCount,
                        savedFrames = savedFrames,
                        frameFiles = savedFrameFiles,
                        frameTimestampsNs = frameTimestampsNs,
                        gyroFile = null,
                        rotationVectorFile = null,
                        gyroSampleCount = logger?.gyroCount() ?: 0,
                        rotationVectorSampleCount = logger?.rotationVectorCount() ?: 0,
                        motionInfo = motionInfo,
                        resolutionMode = resolutionMode,
                        zoomRatio = zoomRatio,
                        cropApplied = cropApplied,
                        physicalCameraId = physicalCameraId,
                        zoomRoute = zoomRoute,
                        previewRoute = previewRoute,
                        routeFallbackReason = routeFallbackReason,
                        frameCountMode = frameCountMode,
                        plannedFrames = frameCount,
                        autoMinFrames = autoMinFrames,
                        autoMaxFrames = autoMaxFrames,
                        manualFrames = manualFrames,
                        framePlanReason = framePlanReason,
                        yuvMemoryBufferUsed = useMemoryBuffer,
                        yuvMemoryBufferEstimatedBytes = estimatedBufferBytes
                    )

                    postStatus("YUV capture: saved $savedFrames/$frameCount")

                    postStatus(
                        "컬러 프레임 저장 중...\n" +
                            "저장: $savedFrames / $frameCount\n" +
                            "timestampNs: $imageTimestampNs\n" +
                            "gyro samples: ${logger?.gyroCount() ?: 0}\n" +
                            "rotation samples: ${logger?.rotationVectorCount() ?: 0}\n" +
                            "폴더:\n${burstDir.absolutePath}"
                    )

                    if (savedFrames >= frameCount) {
                        val savedMotionFiles = saveMotionOnce(burstDir)
                        val finalLogger = motionLogger

                        writeColorJobJson(
                            jobFile = jobFile,
                            status = "CAPTURE_COMPLETE",
                            cameraId = cameraId,
                            width = yuvSize.width,
                            height = yuvSize.height,
                            outputWidth = outputWidth,
                            outputHeight = outputHeight,
                            rotationDegrees = rotationDegrees,
                            requestedFrames = frameCount,
                            savedFrames = savedFrames,
                            frameFiles = savedFrameFiles,
                            frameTimestampsNs = frameTimestampsNs,
                            gyroFile = savedMotionFiles.first,
                            rotationVectorFile = savedMotionFiles.second,
                            gyroSampleCount = finalLogger?.gyroCount() ?: 0,
                            rotationVectorSampleCount = finalLogger?.rotationVectorCount() ?: 0,
                            motionInfo = motionInfo,
                            resolutionMode = resolutionMode,
                            zoomRatio = zoomRatio,
                            cropApplied = cropApplied,
                            physicalCameraId = physicalCameraId,
                            zoomRoute = zoomRoute,
                            previewRoute = previewRoute,
                            routeFallbackReason = routeFallbackReason,
                            frameCountMode = frameCountMode,
                            plannedFrames = frameCount,
                            autoMinFrames = autoMinFrames,
                            autoMaxFrames = autoMaxFrames,
                            manualFrames = manualFrames,
                            framePlanReason = framePlanReason,
                            yuvMemoryBufferUsed = useMemoryBuffer,
                            yuvMemoryBufferEstimatedBytes = estimatedBufferBytes
                        )

                        postStatus("CAPTURE_COMPLETE: 캡처가 완료되었습니다.")
                        onComplete(burstDir)

                        finish(
                            "Color Burst + Motion 저장 완료\n" +
                                "프레임: $savedFrames 장\n" +
                                "출력: ${outputWidth}x${outputHeight}\n" +
                                "rotation: ${rotationDegrees}도\n" +
                                "gyro samples: ${finalLogger?.gyroCount() ?: 0}\n" +
                                "rotation samples: ${finalLogger?.rotationVectorCount() ?: 0}\n" +
                                "폴더:\n${burstDir.absolutePath}"
                        )
                    }
                } catch (oom: OutOfMemoryError) {
                    bufferedFrames.clear()
                    finish(
                        "YUV memory buffer failed: OutOfMemoryError while copying/flushing; " +
                            "job directory and completed source frames kept."
                    )
                } catch (e: Exception) {
                    finish("컬러 프레임 저장 실패\n${e.stackTraceToString()}")
                } finally {
                    try { image?.close() } catch (_: Exception) {}
                }
            },
            backgroundHandler
        )

        postStatus("Color Fusion 초기화 6/7: 카메라 여는 중...")

        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
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
                            onConfigured = { session, captureRoute ->
                                    captureSession = session
                                    postStatus("Color Fusion 초기화 7/7: 세션 준비 완료. $frameCount 장 촬영 중...")

                                    try {
                                        val requestZoomRatio = captureRoute.finalRequestZoomRatio(zoomRatio)
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
                                        updateYuvCaptureRequestTemplateMetadata(
                                            jobFile = jobFile,
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

                                        session.captureBurst(
                                            requests,
                                            object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    result: TotalCaptureResult
                                                ) {
                                                    Log.i(
                                                        "KeplerPhysicalRoute",
                                                        "capture completed selectedRoute=$zoomRoute actualRoute=$captureRoute " +
                                                            "requestedUiZoomRatio=$requestedUiZoomRatio " +
                                                            "requestedPhysicalCameraId=$physicalCameraId " +
                                                            "activePhysicalId=${result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)} " +
                                                            "finalRequestZoom=$requestZoomRatio"
                                                    )
                                                }
                                            },
                                            backgroundHandler
                                        )
                                    } catch (e: Exception) {
                                        val templateFailure =
                                            e.message?.contains(
                                                "YUV capture request template creation failed"
                                            ) == true
                                        if (templateFailure) {
                                            finish(
                                                "PIPELINE_FAILED: ${e.message}\n" +
                                                    "Failures: " +
                                                    yuvCaptureRequestTemplateFailures.joinToString(" | ")
                                            )
                                        } else {
                                            finish("Color Burst 캡처 요청 실패\n${e.stackTraceToString()}")
                                        }
                                    }
                            },

                            onFailed = { reason ->
                                    finish("Color Burst 세션 구성 실패")
                            }
                        )
                    } catch (e: Exception) {
                        finish("Color Burst 세션 생성 실패\n${e.stackTraceToString()}")
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    finish("카메라 연결 해제됨")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    finish("카메라 오류: $error")
                }
            },
            backgroundHandler
        )
    } catch (e: Exception) {
        finish(
            "Color Fusion 초기화 실패\n" +
                "원인:\n${e.stackTraceToString()}"
        )
    }
}

fun averageLatestYuvBurstColor(
    context: Context,
    onStatus: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    fun postStatus(message: String) {
        mainHandler.post { onStatus(message) }
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

            if (!colorRoot.exists()) {
                postStatus("KeplerColorBurst 폴더가 없음. 먼저 Color Fusion 캡처를 해야 함.")
                workerThread.quitSafely()
                return@post
            }

            val latestJobDir = colorRoot
                .listFiles()
                ?.filter { it.isDirectory && File(it, "job.json").exists() }
                ?.maxByOrNull { it.lastModified() }

            if (latestJobDir == null) {
                postStatus("Color Fusion job을 찾지 못함")
                workerThread.quitSafely()
                return@post
            }

            val jobFile = File(latestJobDir, "job.json")
            val job = JSONObject(jobFile.readText())
            val framesArray = job.getJSONArray("frames")

            if (framesArray.length() == 0) {
                postStatus("job.json에 컬러 프레임이 없음")
                workerThread.quitSafely()
                return@post
            }

            val firstFileName = framesArray.getJSONObject(0).getString("file")
            val firstBitmap = BitmapFactory.decodeFile(File(latestJobDir, firstFileName).absolutePath)

            if (firstBitmap == null) {
                postStatus("첫 컬러 프레임을 읽지 못함")
                workerThread.quitSafely()
                return@post
            }

            val width = firstBitmap.width
            val height = firstBitmap.height
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
                val frameFile = File(latestJobDir, fileName)

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

            firstBitmap.recycle()

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

            jobFile.writeText(updatedJob.toString(2))

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

    FileOutputStream(outFile).use { output ->
        rotated.compress(Bitmap.CompressFormat.PNG, 100, output)
    }

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
    FileOutputStream(outFile).use { output ->
        rotated.compress(Bitmap.CompressFormat.PNG, 100, output)
    }
    if (rotated !== bitmap) rotated.recycle()
    bitmap.recycle()
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
            val r = clampToByte((yValue + 1.402f * vValue).toInt())
            val g = clampToByte(
                (yValue - 0.344136f * uValue - 0.714136f * vValue).toInt()
            )
            val b = clampToByte((yValue + 1.772f * uValue).toInt())
            pixels[y * frame.width + x] = Color.rgb(r, g, b)
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

            val r = clampToByte((yValue + 1.402f * vValue).toInt())
            val g = clampToByte((yValue - 0.344136f * uValue - 0.714136f * vValue).toInt())
            val b = clampToByte((yValue + 1.772f * uValue).toInt())

            pixels[y * width + x] = Color.rgb(r, g, b)
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
    characteristics: CameraCharacteristics
): Int {
    val sensorOrientation = characteristics.get(
        CameraCharacteristics.SENSOR_ORIENTATION
    ) ?: 90

    val lensFacing = characteristics.get(
        CameraCharacteristics.LENS_FACING
    )

    return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
        (360 - sensorOrientation) % 360
    } else {
        sensorOrientation % 360
    }
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
            // TODO: 24M Fusion is currently placeholder and uses 12MP-ish input.
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
        val job = if (jobFile.isFile) JSONObject(jobFile.readText()) else JSONObject()
        job.put("yuvCaptureRequestTemplate", template)
            .put("yuvCaptureRequestTemplateFallbackUsed", fallbackUsed)
            .put("yuvCaptureRequestTemplateFailures", JSONArray(failures.take(6)))
            .put("updatedAt", System.currentTimeMillis())
        jobFile.writeText(job.toString(2))
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
    frameFiles: List<String>,
    frameTimestampsNs: List<Long>,
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
    yuvMemoryBufferUsed: Boolean = false,
    yuvMemoryBufferEstimatedBytes: Long = 0L,
    yuvCaptureRequestTemplate: String? = null,
    yuvCaptureRequestTemplateFallbackUsed: Boolean? = null,
    yuvCaptureRequestTemplateFailures: List<String>? = null
) {
    val metadataRoute = inferMetadataZoomRoute(
        requestedUiZoomRatio = zoomRatio,
        captureZoomRatio = zoomRatio,
        physicalCameraId = physicalCameraId,
        cropApplied = cropApplied,
        previewRoute = previewRoute
    )
    val framesArray = JSONArray()
    val previousJob = if (jobFile.exists()) {
        runCatching { JSONObject(jobFile.readText()) }.getOrNull()
    } else {
        null
    }

    frameFiles.forEachIndexed { index, fileName ->
        val frameObject = JSONObject()
            .put("index", index)
            .put("file", fileName)

        if (index < frameTimestampsNs.size) {
            frameObject.put("timestampNs", frameTimestampsNs[index])
        }

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
        .put("jobType", "YUV_NIGHT_FUSION")
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
        .put("processStatus", previousJob?.optString("processStatus", status) ?: status)
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
        .put(
            "timing",
            previousJob?.optJSONObject("timing") ?: JSONObject()
                .put("yuvCaptureMs", if (status == "CAPTURE_COMPLETE") now - (previousJob?.optLong("createdAt", now) ?: now) else 0L)
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
        .put("physicalCameraId", physicalCameraId ?: JSONObject.NULL)
        .put("requestedZoomRatio", zoomRatio.toDouble())
        .put("requestedZoomRoute", zoomRoute.name)
        .put("finalZoomRoute", metadataRoute)
        .put("previewRoute", previewRoute ?: JSONObject.NULL)
        .put("captureRoute", metadataRoute)
        .put("routeFallbackReason", routeFallbackReason ?: JSONObject.NULL)
        .put("resolutionMode", resolutionMode.label)
        .put("zoomRatio", zoomRatio.toDouble())
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

    jobFile.writeText(json.toString(2))
}
