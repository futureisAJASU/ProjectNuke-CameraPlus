package com.projectnuke.keplernightlab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.hardware.camera2.CaptureResult
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KeplerNightLabApp()
        }
    }
}

@Composable
fun KeplerNightLabApp() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var report by remember { mutableStateOf("카메라 권한 확인 중...") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        report = if (granted) {
            buildCameraReport(context)
        } else {
            "카메라 권한이 거부됨. 설정에서 권한 켜야 함."
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            report = buildCameraReport(context)
        }
    }

    var rawStatus by remember { mutableStateOf("RAW 촬영 대기 중") }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Kepler Night Lab",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Camera2 Capability Diagnostics",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            report = if (hasCameraPermission) {
                                buildCameraReport(context)
                            } else {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                                "카메라 권한 요청 중..."
                            }
                        }
                    ) {
                        Text("진단 다시 실행")
                    }

                    Button(
                        onClick = {
                            copyToClipboard(context, "Kepler Camera Report", report)
                        }
                    ) {
                        Text("복사")
                    }
                    Button(
                        onClick = {
                            rawStatus = "RAW 촬영 시작..."
                            captureSingleRawDng(context, cameraId = "0") { status ->
                                rawStatus = status
                            }
                        }
                    ) {
                        Text("RAW DNG 촬영")
                    }
                    Button(
                        onClick = {
                            rawStatus = "RAW Burst 촬영 시작..."
                            captureRawBurstDng(context, cameraId = "0", frameCount = 4) { status ->
                                rawStatus = status
                            }
                        }
                    ) {
                        Text("RAW 4장")
                    }
                    Button(
                        onClick = {
                            val deleted = deleteKeplerRawCache(context)
                            rawStatus = "RAW 캐시 삭제 완료\n삭제된 파일: $deleted 개"
                        }
                    ) {
                        Text("RAW 캐시 삭제")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = rawStatus,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = report,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

fun buildCameraReport(context: Context): String {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val sb = StringBuilder()

    sb.appendLine("=== Kepler Night Lab Camera Report ===")
    sb.appendLine()

    try {
        val cameraIds = cameraManager.cameraIdList
        sb.appendLine("Detected cameras: ${cameraIds.size}")
        sb.appendLine()

        for (cameraId in cameraIds) {
            val c = cameraManager.getCameraCharacteristics(cameraId)

            val lensFacing = c.get(CameraCharacteristics.LENS_FACING)
            val hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: intArrayOf()

            val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)

            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            sb.appendLine("--------------------------------------------------")
            sb.appendLine("Camera ID: $cameraId")
            sb.appendLine("Lens Facing: ${lensFacingName(lensFacing)}")
            sb.appendLine("Hardware Level: ${hardwareLevelName(hardwareLevel)}")
            sb.appendLine()

            sb.appendLine("Capabilities:")
            sb.appendLine("RAW: ${yesNo(hasCapability(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW))}")
            sb.appendLine("MANUAL_SENSOR: ${yesNo(hasCapability(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR))}")
            sb.appendLine("MANUAL_POST_PROCESSING: ${yesNo(hasCapability(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING))}")
            sb.appendLine("BURST_CAPTURE: ${yesNo(hasCapability(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE))}")
            sb.appendLine("PRIVATE_REPROCESSING: ${yesNo(hasCapability(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING))}")
            sb.appendLine("YUV_REPROCESSING: ${yesNo(hasCapability(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING))}")
            sb.appendLine()

            sb.appendLine("Sensor:")
            sb.appendLine("ISO Range: ${isoRange ?: "unknown"}")
            sb.appendLine("Exposure Time Range: ${exposureRange ?: "unknown"} ns")
            sb.appendLine("AE FPS Ranges: ${fpsRanges?.joinToString() ?: "unknown"}")
            sb.appendLine()

            sb.appendLine("Output Sizes:")
            sb.appendLine("RAW_SENSOR:")
            sb.appendLine(formatSizes(map?.getOutputSizes(ImageFormat.RAW_SENSOR)))

            sb.appendLine("YUV_420_888:")
            sb.appendLine(formatSizes(map?.getOutputSizes(ImageFormat.YUV_420_888)))

            sb.appendLine("JPEG:")
            sb.appendLine(formatSizes(map?.getOutputSizes(ImageFormat.JPEG)))

            sb.appendLine("HEIC:")
            sb.appendLine(formatSizes(map?.getOutputSizes(ImageFormat.HEIC)))

            sb.appendLine()
        }
    } catch (e: Exception) {
        sb.appendLine("ERROR:")
        sb.appendLine(e.stackTraceToString())
    }

    return sb.toString()
}

fun hasCapability(capabilities: IntArray, target: Int): Boolean {
    return capabilities.contains(target)
}

fun yesNo(value: Boolean): String {
    return if (value) "YES" else "NO"
}

fun lensFacingName(value: Int?): String {
    return when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($value)"
    }
}

fun hardwareLevelName(value: Int?): String {
    return when (value) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($value)"
    }
}

fun formatSizes(sizes: Array<Size>?): String {
    if (sizes.isNullOrEmpty()) return "  none"

    return sizes
        .sortedWith(compareByDescending<Size> { it.width * it.height }.thenByDescending { it.width })
        .take(12)
        .joinToString(separator = "\n") { size ->
            "  ${size.width} x ${size.height}"
        }
}
fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)

    Toast.makeText(context, "진단 결과 복사됨", Toast.LENGTH_SHORT).show()
}
@SuppressLint("MissingPermission")
fun captureSingleRawDng(
    context: Context,
    cameraId: String = "0",
    onStatus: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())

    fun postStatus(message: String) {
        mainHandler.post {
            onStatus(message)
        }
    }

    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    val backgroundThread = HandlerThread("KeplerRawCaptureThread").apply { start() }
    val backgroundHandler = Handler(backgroundThread.looper)

    var cameraDevice: CameraDevice? = null
    var captureSession: CameraCaptureSession? = null
    var imageReader: ImageReader? = null

    fun cleanup() {
        try {
            captureSession?.close()
        } catch (_: Exception) {}

        try {
            cameraDevice?.close()
        } catch (_: Exception) {}

        try {
            imageReader?.close()
        } catch (_: Exception) {}

        try {
            backgroundThread.quitSafely()
        } catch (_: Exception) {}
    }

    try {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
        val rawSize = rawSizes
            ?.maxByOrNull { it.width * it.height }

        if (rawSize == null) {
            postStatus("RAW_SENSOR 출력 크기를 찾지 못함")
            cleanup()
            return
        }

        postStatus("RAW 준비: Camera $cameraId / ${rawSize.width}x${rawSize.height}")

        val reader = ImageReader.newInstance(
            rawSize.width,
            rawSize.height,
            ImageFormat.RAW_SENSOR,
            2
        )

        imageReader = reader

        val lock = Any()
        var capturedImage: Image? = null
        var captureResult: TotalCaptureResult? = null
        var saved = false

        fun trySaveDng() {
            synchronized(lock) {
                if (saved) return

                val image = capturedImage
                val result = captureResult

                if (image == null || result == null) return

                saved = true

                try {
                    val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    val keplerDir = File(picturesDir, "KeplerRaw").apply {
                        if (!exists()) mkdirs()
                    }

                    val timestamp = SimpleDateFormat(
                        "yyyyMMdd_HHmmss",
                        Locale.US
                    ).format(Date())

                    val file = File(keplerDir, "KPL_RAW_$timestamp.dng")

                    FileOutputStream(file).use { output ->
                        DngCreator(characteristics, result).use { dngCreator ->
                            dngCreator.writeImage(output, image)
                        }
                    }

                    postStatus(
                        "DNG 저장 완료\n${file.absolutePath}\n크기: ${file.length() / 1024 / 1024} MB"
                    )
                } catch (e: Exception) {
                    postStatus("DNG 저장 실패\n${e.stackTraceToString()}")
                } finally {
                    try {
                        image.close()
                    } catch (_: Exception) {}

                    cleanup()
                }
            }
        }

        reader.setOnImageAvailableListener(
            { r ->
                try {
                    val image = r.acquireNextImage()
                    synchronized(lock) {
                        capturedImage = image
                    }
                    postStatus("RAW 이미지 수신 완료. CaptureResult 대기 중...")
                    trySaveDng()
                } catch (e: Exception) {
                    postStatus("RAW 이미지 수신 실패\n${e.stackTraceToString()}")
                    cleanup()
                }
            },
            backgroundHandler
        )

        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    postStatus("카메라 열림. RAW 세션 생성 중...")

                    try {
                        camera.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    postStatus("RAW 세션 준비 완료. 촬영 중...")

                                    try {
                                        val request = camera.createCaptureRequest(
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
                                                CaptureRequest.NOISE_REDUCTION_MODE_OFF
                                            )

                                            set(
                                                CaptureRequest.EDGE_MODE,
                                                CaptureRequest.EDGE_MODE_OFF
                                            )
                                        }.build()

                                        session.capture(
                                            request,
                                            object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    result: TotalCaptureResult
                                                ) {
                                                    synchronized(lock) {
                                                        captureResult = result
                                                    }
                                                    postStatus("CaptureResult 수신 완료. DNG 저장 준비...")
                                                    trySaveDng()
                                                }

                                                override fun onCaptureFailed(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    failure: android.hardware.camera2.CaptureFailure
                                                ) {
                                                    postStatus("RAW 캡처 실패: $failure")
                                                    cleanup()
                                                }
                                            },
                                            backgroundHandler
                                        )
                                    } catch (e: Exception) {
                                        postStatus("RAW 캡처 요청 실패\n${e.stackTraceToString()}")
                                        cleanup()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    postStatus("RAW 캡처 세션 구성 실패")
                                    cleanup()
                                }
                            },
                            backgroundHandler
                        )
                    } catch (e: Exception) {
                        postStatus("RAW 세션 생성 실패\n${e.stackTraceToString()}")
                        cleanup()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    postStatus("카메라 연결 해제됨")
                    cleanup()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    postStatus("카메라 오류: $error")
                    cleanup()
                }
            },
            backgroundHandler
        )
    } catch (e: Exception) {
        postStatus("RAW 촬영 초기화 실패\n${e.stackTraceToString()}")
        cleanup()
    }
}
@SuppressLint("MissingPermission")
fun captureRawBurstDng(
    context: Context,
    cameraId: String = "0",
    frameCount: Int = 4,
    onStatus: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())

    fun postStatus(message: String) {
        mainHandler.post {
            onStatus(message)
        }
    }

    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val backgroundThread = HandlerThread("KeplerRawBurstThread").apply { start() }
    val backgroundHandler = Handler(backgroundThread.looper)

    var cameraDevice: CameraDevice? = null
    var captureSession: CameraCaptureSession? = null
    var imageReader: ImageReader? = null

    val images = mutableMapOf<Long, Image>()
    val results = mutableMapOf<Long, TotalCaptureResult>()
    val savedTimestamps = mutableSetOf<Long>()

    var completedResults = 0
    var receivedImages = 0
    var savedFrames = 0
    var finished = false

    fun cleanup() {
        try {
            captureSession?.close()
        } catch (_: Exception) {}

        try {
            cameraDevice?.close()
        } catch (_: Exception) {}

        try {
            imageReader?.close()
        } catch (_: Exception) {}

        try {
            backgroundThread.quitSafely()
        } catch (_: Exception) {}
    }

    fun finish(message: String) {
        if (finished) return
        finished = true

        postStatus(message)

        images.values.forEach { image ->
            try {
                image.close()
            } catch (_: Exception) {}
        }

        cleanup()
    }

    try {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val rawSize = map
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width * it.height }

        if (rawSize == null) {
            finish("RAW_SENSOR 출력 크기를 찾지 못함")
            return
        }

        postStatus("RAW Burst 준비: Camera $cameraId / ${rawSize.width}x${rawSize.height} / ${frameCount}장")

        val reader = ImageReader.newInstance(
            rawSize.width,
            rawSize.height,
            ImageFormat.RAW_SENSOR,
            frameCount + 2
        )

        imageReader = reader

        val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val keplerDir = File(picturesDir, "KeplerRawBurst").apply {
            if (!exists()) mkdirs()
        }

        val burstTimestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val burstDir = File(keplerDir, "KPL_BURST_$burstTimestamp").apply {
            if (!exists()) mkdirs()
        }

        val jobFile = File(burstDir, "job.json")

        writeBurstJobJson(
            jobFile = jobFile,
            status = "CAPTURING",
            cameraId = cameraId,
            rawWidth = rawSize.width,
            rawHeight = rawSize.height,
            requestedFrames = frameCount,
            savedFrames = 0,
            frameFiles = emptyList()
        )

        val savedFrameFiles = mutableListOf<String>()

        fun trySaveReadyFrames() {
            val readyTimestamps = images.keys
                .filter { timestamp ->
                    results.containsKey(timestamp) && !savedTimestamps.contains(timestamp)
                }
                .sorted()

            for (timestamp in readyTimestamps) {
                val image = images[timestamp] ?: continue
                val result = results[timestamp] ?: continue

                try {
                    val file = File(
                        burstDir,
                        "frame_${savedFrames.toString().padStart(2, '0')}_$timestamp.dng"
                    )

                    FileOutputStream(file).use { output ->
                        DngCreator(characteristics, result).use { dngCreator ->
                            dngCreator.writeImage(output, image)
                        }
                    }

                    savedTimestamps.add(timestamp)
                    savedFrames++
                    savedFrameFiles.add(file.name)

                    writeBurstJobJson(
                        jobFile = jobFile,
                        status = "CAPTURING",
                        cameraId = cameraId,
                        rawWidth = rawSize.width,
                        rawHeight = rawSize.height,
                        requestedFrames = frameCount,
                        savedFrames = savedFrames,
                        frameFiles = savedFrameFiles
                    )

                    try {
                        image.close()
                    } catch (_: Exception) {}

                    images.remove(timestamp)

                    postStatus(
                        "RAW Burst 저장 중...\n" +
                                "저장: $savedFrames / $frameCount\n" +
                                "수신 이미지: $receivedImages / $frameCount\n" +
                                "결과: $completedResults / $frameCount\n" +
                                "폴더:\n${burstDir.absolutePath}"
                    )
                } catch (e: Exception) {
                    finish("Burst DNG 저장 실패\n${e.stackTraceToString()}")
                    return
                }
            }

            if (savedFrames >= frameCount) {
                writeBurstJobJson(
                    jobFile = jobFile,
                    status = "CAPTURE_COMPLETE",
                    cameraId = cameraId,
                    rawWidth = rawSize.width,
                    rawHeight = rawSize.height,
                    requestedFrames = frameCount,
                    savedFrames = savedFrames,
                    frameFiles = savedFrameFiles
                )

                finish(
                    "RAW Burst 저장 완료\n" +
                            "프레임: $savedFrames 장\n" +
                            "job.json 생성 완료\n" +
                            "폴더:\n${burstDir.absolutePath}"
                )
            }
        }

        reader.setOnImageAvailableListener(
            { r ->
                try {
                    val image = r.acquireNextImage()
                    val timestamp = image.timestamp

                    images[timestamp] = image
                    receivedImages++

                    postStatus(
                        "RAW 이미지 수신: $receivedImages / $frameCount\n" +
                                "timestamp=$timestamp"
                    )

                    trySaveReadyFrames()
                } catch (e: Exception) {
                    finish("RAW 이미지 수신 실패\n${e.stackTraceToString()}")
                }
            },
            backgroundHandler
        )

        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    postStatus("카메라 열림. RAW Burst 세션 생성 중...")

                    try {
                        camera.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    postStatus("RAW Burst 세션 준비 완료. $frameCount 장 촬영 중...")

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
                                                    CaptureRequest.NOISE_REDUCTION_MODE_OFF
                                                )

                                                set(
                                                    CaptureRequest.EDGE_MODE,
                                                    CaptureRequest.EDGE_MODE_OFF
                                                )
                                            }.build()
                                        }

                                        session.captureBurst(
                                            requests,
                                            object : CameraCaptureSession.CaptureCallback() {
                                                override fun onCaptureCompleted(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    result: TotalCaptureResult
                                                ) {
                                                    val timestamp = result.get(
                                                        CaptureResult.SENSOR_TIMESTAMP
                                                    )

                                                    if (timestamp != null) {
                                                        results[timestamp] = result
                                                    }

                                                    completedResults++

                                                    postStatus(
                                                        "CaptureResult 수신: $completedResults / $frameCount\n" +
                                                                "timestamp=$timestamp"
                                                    )

                                                    trySaveReadyFrames()
                                                }

                                                override fun onCaptureFailed(
                                                    session: CameraCaptureSession,
                                                    request: CaptureRequest,
                                                    failure: android.hardware.camera2.CaptureFailure
                                                ) {
                                                    finish("RAW Burst 캡처 실패: $failure")
                                                }
                                            },
                                            backgroundHandler
                                        )
                                    } catch (e: Exception) {
                                        finish("RAW Burst 캡처 요청 실패\n${e.stackTraceToString()}")
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    finish("RAW Burst 세션 구성 실패")
                                }
                            },
                            backgroundHandler
                        )
                    } catch (e: Exception) {
                        finish("RAW Burst 세션 생성 실패\n${e.stackTraceToString()}")
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
        finish("RAW Burst 초기화 실패\n${e.stackTraceToString()}")
    }
}
fun deleteKeplerRawCache(context: Context): Int {
    val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        ?: return 0

    val targets = listOf(
        File(picturesDir, "KeplerRaw"),
        File(picturesDir, "KeplerRawBurst")
    )

    var deletedCount = 0

    fun deleteRecursive(file: File) {
        if (!file.exists()) return

        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                deleteRecursive(child)
            }
        }

        if (file.delete()) {
            deletedCount++
        }
    }

    targets.forEach { dir ->
        deleteRecursive(dir)
    }

    return deletedCount
}
fun writeBurstJobJson(
    jobFile: File,
    status: String,
    cameraId: String,
    rawWidth: Int,
    rawHeight: Int,
    requestedFrames: Int,
    savedFrames: Int,
    frameFiles: List<String>
) {
    val framesArray = JSONArray()

    frameFiles.forEachIndexed { index, fileName ->
        framesArray.put(
            JSONObject()
                .put("index", index)
                .put("file", fileName)
        )
    }

    val json = JSONObject()
        .put("app", "Kepler Night Lab")
        .put("jobType", "RAW_BURST")
        .put("status", status)
        .put("cameraId", cameraId)
        .put("rawWidth", rawWidth)
        .put("rawHeight", rawHeight)
        .put("requestedFrames", requestedFrames)
        .put("savedFrames", savedFrames)
        .put("frames", framesArray)
        .put("createdAt", System.currentTimeMillis())
        .put("updatedAt", System.currentTimeMillis())

    jobFile.writeText(json.toString(2))
}