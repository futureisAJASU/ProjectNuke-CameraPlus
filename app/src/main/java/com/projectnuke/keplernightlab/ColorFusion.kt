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
import android.hardware.camera2.CaptureRequest
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@SuppressLint("MissingPermission")
fun captureYuvBurstColorWithMotion(
    context: Context,
    cameraId: String,
    frameCount: Int = 4,
    resolutionMode: CaptureResolutionMode = CaptureResolutionMode.MP12,
    zoomRatio: Float = 1.0f,
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

        val keplerDir = File(picturesDir, "KeplerColorBurst").apply {
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

        val burstDir = File(keplerDir, "KPL_COLOR_BURST_$burstTimestamp").apply {
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

        val jobFile = File(burstDir, "job.json")

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
            frameCountMode = frameCountMode,
            plannedFrames = frameCount,
            autoMinFrames = autoMinFrames,
            autoMaxFrames = autoMaxFrames,
            manualFrames = manualFrames,
            framePlanReason = framePlanReason
        )

        postStatus("Color Fusion 초기화 4/7: ImageReader 생성 중...")

        val reader = ImageReader.newInstance(
            yuvSize.width,
            yuvSize.height,
            ImageFormat.YUV_420_888,
            frameCount + 2
        )

        imageReader = reader

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
                        frameCountMode = frameCountMode,
                        plannedFrames = frameCount,
                        autoMinFrames = autoMinFrames,
                        autoMaxFrames = autoMaxFrames,
                        manualFrames = manualFrames,
                        framePlanReason = framePlanReason
                    )

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
                            frameCountMode = frameCountMode,
                            plannedFrames = frameCount,
                            autoMinFrames = autoMinFrames,
                            autoMaxFrames = autoMaxFrames,
                            manualFrames = manualFrames,
                            framePlanReason = framePlanReason
                        )

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
                        camera.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    postStatus("Color Fusion 초기화 7/7: 세션 준비 완료. $frameCount 장 촬영 중...")

                                    try {
                                        val requests = List(frameCount) {
                                            camera.createCaptureRequest(
                                                CameraDevice.TEMPLATE_STILL_CAPTURE
                                            ).apply {
                                                addTarget(reader.surface)

                                                set(
                                                    CaptureRequest.CONTROL_MODE,
                                                    CaptureRequest.CONTROL_MODE_AUTO
                                                )

                                                set(
                                                    CaptureRequest.CONTROL_AF_MODE,
                                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                                )

                                                set(
                                                    CaptureRequest.NOISE_REDUCTION_MODE,
                                                    CaptureRequest.NOISE_REDUCTION_MODE_FAST
                                                )

                                                set(
                                                    CaptureRequest.EDGE_MODE,
                                                    CaptureRequest.EDGE_MODE_FAST
                                                )

                                                applyZoomAndFocusAe(characteristics, zoomRatio, focusAeState)
                                            }.build()
                                        }

                                        session.captureBurst(
                                            requests,
                                            object : CameraCaptureSession.CaptureCallback() {},
                                            backgroundHandler
                                        )
                                    } catch (e: Exception) {
                                        finish("Color Burst 캡처 요청 실패\n${e.stackTraceToString()}")
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    finish("Color Burst 세션 구성 실패")
                                }
                            },
                            backgroundHandler
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

            val colorRoot = File(picturesDir, "KeplerColorBurst")

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
    frameCountMode: FrameCountMode = FrameCountMode.AUTO,
    plannedFrames: Int = requestedFrames,
    autoMinFrames: Int = 4,
    autoMaxFrames: Int = 8,
    manualFrames: Int = 4,
    framePlanReason: String = "Default"
) {
    val framesArray = JSONArray()

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
        .put("jobType", "YUV_BURST_COLOR")
        .put("status", status)
        .put("cameraId", cameraId)
        .put("selectedCameraId", cameraId)
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
        .put("frames", framesArray)
        .put("motion", motionObject)
        .put("updatedAt", now)

    if (!jobFile.exists()) {
        json.put("createdAt", now)
    } else {
        val oldCreatedAt = runCatching {
            JSONObject(jobFile.readText()).optLong("createdAt", now)
        }.getOrDefault(now)

        json.put("createdAt", oldCreatedAt)
    }

    jobFile.writeText(json.toString(2))
}
