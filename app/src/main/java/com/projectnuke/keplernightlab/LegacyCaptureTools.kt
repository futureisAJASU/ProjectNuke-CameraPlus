package com.projectnuke.keplernightlab

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
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
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val LEGACY_DEBUG_CAMERA_ID = "0"

data class MotionSample(
    val timestampNs: Long,
    val values: FloatArray
)

class MotionLogger(context: Context) {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gyroSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)

    private val rotationVectorSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val lock = Any()
    private val gyroSamples = mutableListOf<MotionSample>()
    private val rotationVectorSamples = mutableListOf<MotionSample>()

    private var running = false
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val sample = MotionSample(
                timestampNs = event.timestamp,
                values = event.values.copyOf()
            )

            synchronized(lock) {
                when (event.sensor.type) {
                    Sensor.TYPE_GYROSCOPE,
                    Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> gyroSamples.add(sample)
                    Sensor.TYPE_GAME_ROTATION_VECTOR,
                    Sensor.TYPE_ROTATION_VECTOR -> rotationVectorSamples.add(sample)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start(): String {
        synchronized(lock) {
            gyroSamples.clear()
            rotationVectorSamples.clear()
        }

        if (running) return "motion logger already running"

        val thread = HandlerThread("KeplerMotionSensorThread").apply { start() }
        val handler = Handler(thread.looper)

        sensorThread = thread
        sensorHandler = handler

        var registered = 0

        gyroSensor?.let {
            val ok = sensorManager.registerListener(
                listener,
                it,
                SensorManager.SENSOR_DELAY_FASTEST,
                handler
            )
            if (ok) registered++
        }

        rotationVectorSensor?.let {
            val ok = sensorManager.registerListener(
                listener,
                it,
                SensorManager.SENSOR_DELAY_FASTEST,
                handler
            )
            if (ok) registered++
        }

        running = registered > 0

        return "gyro=${gyroSensor != null}, rotation=${rotationVectorSensor != null}, registered=$registered"
    }

    fun stop() {
        if (!running && sensorThread == null) return

        try {
            sensorManager.unregisterListener(listener)
        } catch (_: Exception) {}

        running = false

        try {
            sensorThread?.quitSafely()
        } catch (_: Exception) {}

        sensorThread = null
        sensorHandler = null
    }

    fun gyroCount(): Int = synchronized(lock) { gyroSamples.size }

    fun rotationVectorCount(): Int = synchronized(lock) { rotationVectorSamples.size }

    fun saveToDirectory(dir: File): Pair<String?, String?> {
        val gyroCopy: List<MotionSample>
        val rotationCopy: List<MotionSample>

        synchronized(lock) {
            gyroCopy = gyroSamples.toList()
            rotationCopy = rotationVectorSamples.toList()
        }

        var gyroFileName: String? = null
        var rotationFileName: String? = null

        if (gyroCopy.isNotEmpty()) {
            val gyroFile = File(dir, "gyro.csv")
            gyroFile.printWriter().use { out ->
                out.println("timestampNs,xRadPerSec,yRadPerSec,zRadPerSec")
                gyroCopy.forEach { sample ->
                    val x = sample.values.getOrNull(0) ?: 0f
                    val y = sample.values.getOrNull(1) ?: 0f
                    val z = sample.values.getOrNull(2) ?: 0f
                    out.println("${sample.timestampNs},$x,$y,$z")
                }
            }
            gyroFileName = gyroFile.name
        }

        if (rotationCopy.isNotEmpty()) {
            val rotationFile = File(dir, "rotation_vector.csv")
            rotationFile.printWriter().use { out ->
                out.println("timestampNs,v0,v1,v2,v3,v4")
                rotationCopy.forEach { sample ->
                    val values = (0 until 5).joinToString(",") { index ->
                        (sample.values.getOrNull(index) ?: 0f).toString()
                    }
                    out.println("${sample.timestampNs},$values")
                }
            }
            rotationFileName = rotationFile.name
        }

        return Pair(gyroFileName, rotationFileName)
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
    var rawStatus by remember { mutableStateOf("촬영 대기 중") }

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

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                report = if (hasCameraPermission) {
                                    buildCameraReport(context)
                                } else {
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                    "카메라 권한 요청 중..."
                                }
                            }
                        ) {
                            Text("진단")
                        }

                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                copyToClipboard(context, "Kepler Camera Report", report)
                            }
                        ) {
                            Text("복사")
                        }
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
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
                        modifier = Modifier.fillMaxWidth(),
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
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            rawStatus = "YUV Burst + Motion 촬영 시작..."
                            captureYuvBurstGrayWithMotion(context, cameraId = "0", frameCount = 4) { status ->
                                rawStatus = status
                            }
                        }
                    ) {
                        Text("YUV 4장 + 센서")
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            rawStatus = "YUV 평균 합성 시작..."
                            averageLatestYuvBurstGray(context) { status ->
                                rawStatus = status
                            }
                        }
                    ) {
                        Text("YUV 평균 합성")
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            rawStatus = "최근 Motion Job 요약 생성 중..."
                            summarizeLatestYuvMotionJob(context) { status ->
                                rawStatus = status
                            }
                        }
                    ) {
                        Text("최근 Job 요약")
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val deleted = deleteKeplerCache(context)
                            rawStatus = "캐시 삭제 완료\n삭제된 파일/폴더: $deleted 개"
                        }
                    ) {
                        Text("캐시 삭제")
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
            val whiteLevel = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            val blackLevelPattern = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            val cfaPattern = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            val sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION)
            val activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val preCorrectionArray = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)

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
            sb.appendLine("Sensor Orientation: ${sensorOrientation ?: "unknown"}")
            sb.appendLine("Active Array: ${activeArray ?: "unknown"}")
            sb.appendLine("Pre-correction Active Array: ${preCorrectionArray ?: "unknown"}")
            sb.appendLine("RAW White Level: ${whiteLevel ?: "unknown"}")
            sb.appendLine("RAW Black Level Pattern: ${blackLevelPattern ?: "unknown"}")
            sb.appendLine("CFA Pattern: ${cfaPattern ?: "unknown"}")
            sb.appendLine()

            sb.appendLine("Output Sizes:")

            sb.appendLine("RAW_SENSOR:")
            sb.appendLine(formatSizes(map?.getOutputSizes(ImageFormat.RAW_SENSOR)))

            sb.appendLine("High-res RAW_SENSOR:")
            sb.appendLine(formatSizes(map?.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR)))

            sb.appendLine("YUV_420_888:")
            sb.appendLine(formatSizes(map?.getOutputSizes(ImageFormat.YUV_420_888)))

            sb.appendLine("High-res YUV_420_888:")
            sb.appendLine(formatSizes(map?.getHighResolutionOutputSizes(ImageFormat.YUV_420_888)))

            sb.appendLine("JPEG:")
            sb.appendLine(formatSizes(map?.getOutputSizes(ImageFormat.JPEG)))

            sb.appendLine("High-res JPEG:")
            sb.appendLine(formatSizes(map?.getHighResolutionOutputSizes(ImageFormat.JPEG)))

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
        .sortedWith(
            compareByDescending<Size> { it.width * it.height }
                .thenByDescending { it.width }
        )
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
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { backgroundThread.quitSafely() } catch (_: Exception) {}
    }

    try {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val rawSize = map
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
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
                    if (picturesDir == null) {
                        postStatus("DNG 저장 실패: Pictures 폴더가 null임")
                        return
                    }

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
                        "DNG 저장 완료\n" +
                                "${file.absolutePath}\n" +
                                "크기: ${file.length() / 1024 / 1024} MB"
                    )
                    postStatus("PIPELINE_COMPLETE: RAW DNG capture complete.")
                } catch (e: Exception) {
                    postStatus("PIPELINE_FAILED: DNG 저장 실패\n${e.stackTraceToString()}")
                } finally {
                    try { image.close() } catch (_: Exception) {}
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
                    postStatus("PIPELINE_FAILED: RAW 이미지 수신 실패\n${e.stackTraceToString()}")
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
                                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                                            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
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
                                                    postStatus("PIPELINE_FAILED: RAW 캡처 실패: $failure")
                                                    cleanup()
                                                }
                                            },
                                            backgroundHandler
                                        )
                                    } catch (e: Exception) {
                                        postStatus("PIPELINE_FAILED: RAW 캡처 요청 실패\n${e.stackTraceToString()}")
                                        cleanup()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    postStatus("PIPELINE_FAILED: RAW 캡처 세션 구성 실패")
                                    cleanup()
                                }
                            },
                            backgroundHandler
                        )
                    } catch (e: Exception) {
                        postStatus("PIPELINE_FAILED: RAW 세션 생성 실패\n${e.stackTraceToString()}")
                        cleanup()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    postStatus("PIPELINE_FAILED: 카메라 연결 해제됨")
                    cleanup()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    postStatus("PIPELINE_FAILED: 카메라 오류: $error")
                    cleanup()
                }
            },
            backgroundHandler
        )
    } catch (e: Exception) {
        postStatus("PIPELINE_FAILED: RAW 촬영 초기화 실패\n${e.stackTraceToString()}")
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
    var successfulCompletion = false

    fun cleanup() {
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { backgroundThread.quitSafely() } catch (_: Exception) {}
    }

    fun finish(message: String) {
        if (finished) return
        finished = true

        val prefix = if (successfulCompletion) "PIPELINE_COMPLETE: " else "PIPELINE_FAILED: "
        postStatus(prefix + message)

        images.values.forEach { image ->
            try { image.close() } catch (_: Exception) {}
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
        if (picturesDir == null) {
            finish("RAW Burst 초기화 실패: Pictures 폴더가 null임")
            return
        }

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
        val savedFrameFiles = mutableListOf<String>()

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

        fun trySaveReadyFrames() {
            if (finished) return

            val readyTimestamps = images.keys
                .filter { timestamp ->
                    results.containsKey(timestamp) && !savedTimestamps.contains(timestamp)
                }
                .sorted()

            for (timestamp in readyTimestamps) {
                if (finished) return

                val image = images[timestamp] ?: continue
                val result = results[timestamp] ?: continue

                savedTimestamps.add(timestamp)

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

                    savedFrames++
                    savedFrameFiles.add(file.name)
                    images.remove(timestamp)

                    try { image.close() } catch (_: Exception) {}

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
                successfulCompletion = true

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
                                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                                                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
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
                                                    val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)

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

@SuppressLint("MissingPermission")
fun captureYuvBurstGrayWithMotion(
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
    val backgroundThread = HandlerThread("KeplerYuvBurstThread").apply { start() }
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

    val motionWarmupMs = 500L
    val motionTailMs = 500L

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
            postStatus("Motion 저장 실패, YUV는 유지\n${e.stackTraceToString()}")
            motionFiles
        }
    }

    try {
        postStatus("YUV+Motion 초기화 1/7: 카메라 특성 확인 중...")

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        if (map == null) {
            finish("YUV+Motion 초기화 실패: StreamConfigurationMap이 null임")
            return
        }

        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)

        if (yuvSizes.isNullOrEmpty()) {
            finish("YUV+Motion 초기화 실패: YUV_420_888 출력 크기를 찾지 못함")
            return
        }

        val yuvSize = yuvSizes
            .filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width * it.height }
            ?: yuvSizes.minByOrNull { kotlin.math.abs(it.width - 1920) + kotlin.math.abs(it.height - 1080) }
            ?: yuvSizes.maxByOrNull { it.width * it.height }

        if (yuvSize == null) {
            finish("YUV+Motion 초기화 실패: 선택 가능한 YUV 크기가 없음")
            return
        }

        postStatus("YUV+Motion 초기화 2/7: 저장 폴더 준비 중...")

        val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)

        if (picturesDir == null) {
            finish("YUV+Motion 초기화 실패: getExternalFilesDir(Pictures)가 null임")
            return
        }

        val keplerDir = File(picturesDir, "KeplerYuvBurst").apply {
            if (!exists()) {
                val ok = mkdirs()
                if (!ok && !exists()) {
                    finish("YUV+Motion 초기화 실패: KeplerYuvBurst 폴더 생성 실패\n$absolutePath")
                    return
                }
            }
        }

        val burstTimestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val burstDir = File(keplerDir, "KPL_YUV_BURST_$burstTimestamp").apply {
            if (!exists()) {
                val ok = mkdirs()
                if (!ok && !exists()) {
                    finish("YUV+Motion 초기화 실패: Burst 폴더 생성 실패\n$absolutePath")
                    return
                }
            }
        }

        val jobFile = File(burstDir, "job.json")

        postStatus("YUV+Motion 초기화 3/7: job.json 생성 중...")

        writeYuvJobJson(
            jobFile = jobFile,
            status = "CAPTURING",
            cameraId = cameraId,
            width = yuvSize.width,
            height = yuvSize.height,
            requestedFrames = frameCount,
            savedFrames = 0,
            frameFiles = emptyList(),
            frameTimestampsNs = emptyList(),
            gyroFile = null,
            rotationVectorFile = null,
            gyroSampleCount = 0,
            rotationVectorSampleCount = 0,
            motionInfo = "not_started"
        )

        postStatus("YUV+Motion 초기화 4/7: ImageReader 생성 중...")

        val reader = ImageReader.newInstance(
            yuvSize.width,
            yuvSize.height,
            ImageFormat.YUV_420_888,
            frameCount + 2
        )

        imageReader = reader

        postStatus("YUV+Motion 초기화 5/7: 모션 센서 시작 중...")

        motionLogger = try {
            MotionLogger(context).also { logger ->
                motionInfo = logger.start()
            }
        } catch (e: Exception) {
            motionInfo = "motion_failed_but_continue: ${e.javaClass.simpleName}: ${e.message}"
            null
        }

        postStatus(
            "YUV Burst + Motion 준비 완료\n" +
                    "Camera $cameraId\n" +
                    "Size: ${yuvSize.width}x${yuvSize.height}\n" +
                    "Frames: $frameCount\n" +
                    "Motion: $motionInfo\n" +
                    "Sensor warmup: ${motionWarmupMs}ms\n" +
                    "Sensor tail: ${motionTailMs}ms\n" +
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

                    val fileName = "frame_${frameIndex.toString().padStart(2, '0')}_y.gray"
                    val outFile = File(burstDir, fileName)

                    writeYPlaneCompact(image, outFile)

                    savedFrames++
                    savedFrameFiles.add(fileName)
                    frameTimestampsNs.add(imageTimestampNs)

                    val logger = motionLogger

                    writeYuvJobJson(
                        jobFile = jobFile,
                        status = "CAPTURING",
                        cameraId = cameraId,
                        width = yuvSize.width,
                        height = yuvSize.height,
                        requestedFrames = frameCount,
                        savedFrames = savedFrames,
                        frameFiles = savedFrameFiles,
                        frameTimestampsNs = frameTimestampsNs,
                        gyroFile = null,
                        rotationVectorFile = null,
                        gyroSampleCount = logger?.gyroCount() ?: 0,
                        rotationVectorSampleCount = logger?.rotationVectorCount() ?: 0,
                        motionInfo = motionInfo
                    )

                    postStatus(
                        "YUV 저장 중...\n" +
                                "저장: $savedFrames / $frameCount\n" +
                                "timestampNs: $imageTimestampNs\n" +
                                "gyro samples: ${logger?.gyroCount() ?: 0}\n" +
                                "rotation samples: ${logger?.rotationVectorCount() ?: 0}\n" +
                                "폴더:\n${burstDir.absolutePath}"
                    )

                    if (savedFrames >= frameCount) {
                        postStatus(
                            "프레임 저장 완료. 센서 tail ${motionTailMs}ms 추가 수집 중...\n" +
                                    "현재 gyro samples: ${logger?.gyroCount() ?: 0}\n" +
                                    "현재 rotation samples: ${logger?.rotationVectorCount() ?: 0}"
                        )

                        backgroundHandler.postDelayed({
                            if (finished) return@postDelayed

                            val savedMotionFiles = saveMotionOnce(burstDir)
                            val finalLogger = motionLogger
                            val finalGyroCount = finalLogger?.gyroCount() ?: 0
                            val finalRotationCount = finalLogger?.rotationVectorCount() ?: 0

                            writeYuvJobJson(
                                jobFile = jobFile,
                                status = "CAPTURE_COMPLETE",
                                cameraId = cameraId,
                                width = yuvSize.width,
                                height = yuvSize.height,
                                requestedFrames = frameCount,
                                savedFrames = savedFrames,
                                frameFiles = savedFrameFiles,
                                frameTimestampsNs = frameTimestampsNs,
                                gyroFile = savedMotionFiles.first,
                                rotationVectorFile = savedMotionFiles.second,
                                gyroSampleCount = finalGyroCount,
                                rotationVectorSampleCount = finalRotationCount,
                                motionInfo = motionInfo
                            )

                            finish(
                                "YUV Burst + Motion 저장 완료\n" +
                                        "프레임: $savedFrames 장\n" +
                                        "gyro samples: $finalGyroCount\n" +
                                        "rotation samples: $finalRotationCount\n" +
                                        "job.json / gyro.csv / rotation_vector.csv 생성 시도 완료\n" +
                                        "폴더:\n${burstDir.absolutePath}"
                            )
                        }, motionTailMs)
                    }
                } catch (e: Exception) {
                    finish("YUV 이미지 저장 실패\n${e.stackTraceToString()}")
                } finally {
                    try { image?.close() } catch (_: Exception) {}
                }
            },
            backgroundHandler
        )

        postStatus("YUV+Motion 초기화 6/7: 센서 warmup ${motionWarmupMs}ms 후 카메라 열기...")

        backgroundHandler.postDelayed({
            if (finished) return@postDelayed

            postStatus("YUV+Motion 초기화 6/7: 카메라 여는 중...")

            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        postStatus("카메라 열림. YUV Burst 세션 생성 중...")

                        try {
                            camera.createCaptureSession(
                                listOf(reader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        captureSession = session
                                        postStatus("YUV+Motion 초기화 7/7: 세션 준비 완료. $frameCount 장 촬영 중...")

                                        try {
                                            val requests = List(frameCount) {
                                                camera.createCaptureRequest(
                                                    CameraDevice.TEMPLATE_STILL_CAPTURE
                                                ).apply {
                                                    addTarget(reader.surface)
                                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                                                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                                                }.build()
                                            }

                                            session.captureBurst(
                                                requests,
                                                object : CameraCaptureSession.CaptureCallback() {},
                                                backgroundHandler
                                            )
                                        } catch (e: Exception) {
                                            finish("YUV Burst 캡처 요청 실패\n${e.stackTraceToString()}")
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        finish("YUV Burst 세션 구성 실패")
                                    }
                                },
                                backgroundHandler
                            )
                        } catch (e: Exception) {
                            finish("YUV Burst 세션 생성 실패\n${e.stackTraceToString()}")
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
        }, motionWarmupMs)
    } catch (e: Exception) {
        finish(
            "YUV Burst + Motion 초기화 실패\n" +
                    "원인:\n${e.stackTraceToString()}"
        )
    }
}

fun averageLatestYuvBurstGray(
    context: Context,
    onStatus: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())

    fun postStatus(message: String) {
        mainHandler.post {
            onStatus(message)
        }
    }

    val workerThread = HandlerThread("KeplerAverageGrayThread").apply { start() }
    val workerHandler = Handler(workerThread.looper)

    workerHandler.post {
        try {
            val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)

            if (picturesDir == null) {
                postStatus("Pictures 폴더를 찾지 못함")
                workerThread.quitSafely()
                return@post
            }

            val yuvRoot = File(picturesDir, "KeplerYuvBurst")

            if (!yuvRoot.exists()) {
                postStatus("KeplerYuvBurst 폴더가 없음. 먼저 YUV 4장을 찍어야 함.")
                workerThread.quitSafely()
                return@post
            }

            val latestJobDir = yuvRoot
                .listFiles()
                ?.filter { it.isDirectory && File(it, "job.json").exists() }
                ?.maxByOrNull { it.lastModified() }

            if (latestJobDir == null) {
                postStatus("YUV job을 찾지 못함")
                workerThread.quitSafely()
                return@post
            }

            val jobFile = File(latestJobDir, "job.json")
            val job = JSONObject(jobFile.readText())

            val width = job.getInt("width")
            val height = job.getInt("height")
            val framesArray = job.getJSONArray("frames")

            if (framesArray.length() == 0) {
                postStatus("job.json에 프레임이 없음")
                workerThread.quitSafely()
                return@post
            }

            val pixelCount = width * height
            val accumulator = IntArray(pixelCount)
            var usedFrames = 0

            postStatus(
                "평균 합성 준비\n" +
                        "폴더: ${latestJobDir.name}\n" +
                        "해상도: ${width}x${height}\n" +
                        "프레임: ${framesArray.length()}장"
            )

            for (i in 0 until framesArray.length()) {
                val frameObj = framesArray.getJSONObject(i)
                val fileName = frameObj.getString("file")
                val frameFile = File(latestJobDir, fileName)

                if (!frameFile.exists()) {
                    continue
                }

                val bytes = frameFile.readBytes()

                if (bytes.size < pixelCount) {
                    continue
                }

                for (p in 0 until pixelCount) {
                    accumulator[p] += bytes[p].toInt() and 0xFF
                }

                usedFrames++

                postStatus(
                    "평균 합성 중...\n" +
                            "사용 프레임: $usedFrames / ${framesArray.length()}"
                )
            }

            if (usedFrames == 0) {
                postStatus("사용 가능한 gray 프레임이 없음")
                workerThread.quitSafely()
                return@post
            }

            val pixels = IntArray(pixelCount)

            for (p in 0 until pixelCount) {
                val y = accumulator[p] / usedFrames
                pixels[p] = Color.rgb(y, y, y)
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

            val outFile = File(latestJobDir, "average_gray.png")

            FileOutputStream(outFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }

            bitmap.recycle()

            val updatedJob = JSONObject(job.toString())
                .put("processStatus", "AVERAGE_GRAY_COMPLETE")
                .put("averageGrayFile", outFile.name)
                .put("averageUsedFrames", usedFrames)
                .put("processedAt", System.currentTimeMillis())

            jobFile.writeText(updatedJob.toString(2))

            postStatus(
                "YUV 평균 합성 완료\n" +
                        "사용 프레임: $usedFrames 장\n" +
                        "결과:\n${outFile.absolutePath}\n" +
                        "크기: ${outFile.length() / 1024 / 1024} MB"
            )
        } catch (e: Exception) {
            postStatus("YUV 평균 합성 실패\n${e.stackTraceToString()}")
        } finally {
            workerThread.quitSafely()
        }
    }
}

fun summarizeLatestYuvMotionJob(
    context: Context,
    onStatus: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())

    fun postStatus(message: String) {
        mainHandler.post {
            onStatus(message)
        }
    }

    val workerThread = HandlerThread("KeplerMotionSummaryThread").apply { start() }
    val workerHandler = Handler(workerThread.looper)

    workerHandler.post {
        try {
            val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)

            if (picturesDir == null) {
                postStatus("Pictures 폴더를 찾지 못함")
                workerThread.quitSafely()
                return@post
            }

            val yuvRoot = File(picturesDir, "KeplerYuvBurst")

            if (!yuvRoot.exists()) {
                postStatus("KeplerYuvBurst 폴더가 없음. 먼저 YUV 4장 + 센서를 찍어야 함.")
                workerThread.quitSafely()
                return@post
            }

            val latestJobDir = yuvRoot
                .listFiles()
                ?.filter { it.isDirectory && File(it, "job.json").exists() }
                ?.maxByOrNull { it.lastModified() }

            if (latestJobDir == null) {
                postStatus("YUV job을 찾지 못함")
                workerThread.quitSafely()
                return@post
            }

            val jobFile = File(latestJobDir, "job.json")
            val job = JSONObject(jobFile.readText())

            val status = job.optString("status", "unknown")
            val cameraId = job.optString("cameraId", "unknown")
            val width = job.optInt("width", -1)
            val height = job.optInt("height", -1)
            val framesArray = job.optJSONArray("frames") ?: JSONArray()
            val motion = job.optJSONObject("motion")

            val frameTimestamps = mutableListOf<Long>()

            for (i in 0 until framesArray.length()) {
                val frame = framesArray.optJSONObject(i) ?: continue
                if (frame.has("timestampNs")) {
                    frameTimestamps.add(frame.optLong("timestampNs"))
                }
            }

            val frameMin = frameTimestamps.minOrNull()
            val frameMax = frameTimestamps.maxOrNull()

            fun cleanFileName(value: String?): String? {
                if (value == null) return null
                if (value.isBlank()) return null
                if (value == "null") return null
                return value
            }

            val gyroFileName = cleanFileName(motion?.opt("gyroFile") as? String)
            val rotationFileName = cleanFileName(motion?.opt("rotationVectorFile") as? String)

            val gyroStats = gyroFileName?.let { name ->
                readMotionCsvStats(File(latestJobDir, name))
            }

            val rotationStats = rotationFileName?.let { name ->
                readMotionCsvStats(File(latestJobDir, name))
            }

            fun rangeText(min: Long?, max: Long?): String {
                return if (min == null || max == null) {
                    "없음"
                } else {
                    "$min ~ $max"
                }
            }

            fun overlapText(stats: MotionCsvStats?): String {
                if (frameMin == null || frameMax == null) return "프레임 timestamp 없음"
                if (stats == null || stats.count == 0 || stats.minTimestampNs == null || stats.maxTimestampNs == null) {
                    return "센서 로그 없음"
                }

                val inside = stats.minTimestampNs <= frameMin && stats.maxTimestampNs >= frameMax
                val beforeMs = (frameMin - stats.minTimestampNs) / 1_000_000.0
                val afterMs = (stats.maxTimestampNs - frameMax) / 1_000_000.0

                return if (inside) {
                    "OK, frame range inside sensor range (앞 ${"%.1f".format(beforeMs)}ms / 뒤 ${"%.1f".format(afterMs)}ms)"
                } else {
                    "WARN, frame range 밖으로 벗어남 (앞 ${"%.1f".format(beforeMs)}ms / 뒤 ${"%.1f".format(afterMs)}ms)"
                }
            }

            val summary = buildString {
                appendLine("Latest YUV Motion Job")
                appendLine("folder: ${latestJobDir.name}")
                appendLine("status: $status")
                appendLine("cameraId: $cameraId")
                appendLine("size: ${width}x${height}")
                appendLine("frames: ${framesArray.length()}")
                appendLine()
                appendLine("Frame timestamp range:")
                appendLine(rangeText(frameMin, frameMax))
                appendLine()
                appendLine("Gyro:")
                appendLine("file: ${gyroFileName ?: "없음"}")
                appendLine("samples: ${gyroStats?.count ?: 0}")
                appendLine("range: ${rangeText(gyroStats?.minTimestampNs, gyroStats?.maxTimestampNs)}")
                appendLine("check: ${overlapText(gyroStats)}")
                appendLine()
                appendLine("Rotation Vector:")
                appendLine("file: ${rotationFileName ?: "없음"}")
                appendLine("samples: ${rotationStats?.count ?: 0}")
                appendLine("range: ${rangeText(rotationStats?.minTimestampNs, rotationStats?.maxTimestampNs)}")
                appendLine("check: ${overlapText(rotationStats)}")
            }

            postStatus(summary)
        } catch (e: Exception) {
            postStatus("최근 Motion Job 요약 실패\n${e.stackTraceToString()}")
        } finally {
            workerThread.quitSafely()
        }
    }
}

data class MotionCsvStats(
    val count: Int,
    val minTimestampNs: Long?,
    val maxTimestampNs: Long?
)

fun readMotionCsvStats(file: File): MotionCsvStats {
    if (!file.exists()) {
        return MotionCsvStats(
            count = 0,
            minTimestampNs = null,
            maxTimestampNs = null
        )
    }

    var count = 0
    var minTimestamp: Long? = null
    var maxTimestamp: Long? = null

    file.bufferedReader().useLines { lines ->
        lines.drop(1).forEach { line ->
            val timestamp = line
                .substringBefore(',')
                .trim()
                .toLongOrNull()

            if (timestamp != null) {
                count++

                minTimestamp = if (minTimestamp == null) {
                    timestamp
                } else {
                    kotlin.math.min(minTimestamp!!, timestamp)
                }

                maxTimestamp = if (maxTimestamp == null) {
                    timestamp
                } else {
                    kotlin.math.max(maxTimestamp!!, timestamp)
                }
            }
        }
    }

    return MotionCsvStats(
        count = count,
        minTimestampNs = minTimestamp,
        maxTimestampNs = maxTimestamp
    )
}


fun writeYPlaneCompact(image: Image, outFile: File) {
    val width = image.width
    val height = image.height

    val plane = image.planes[0]
    val buffer: ByteBuffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val limit = buffer.limit()

    FileOutputStream(outFile).use { output ->
        val compactRow = ByteArray(width)

        for (y in 0 until height) {
            val rowStart = y * rowStride

            for (x in 0 until width) {
                val index = rowStart + x * pixelStride
                compactRow[x] = if (index in 0 until limit) {
                    buffer.get(index)
                } else {
                    0
                }
            }

            output.write(compactRow)
        }
    }
}

fun deleteKeplerCache(context: Context): Int {
    val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        ?: return 0

    val targets = listOf(
        File(picturesDir, "KeplerRaw"),
        File(picturesDir, "KeplerRawBurst"),
        File(picturesDir, "KeplerYuvBurst"),
        File(picturesDir, "KeplerColorBurst")
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

    val now = System.currentTimeMillis()

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

fun writeYuvJobJson(
    jobFile: File,
    status: String,
    cameraId: String,
    width: Int,
    height: Int,
    requestedFrames: Int,
    savedFrames: Int,
    frameFiles: List<String>,
    frameTimestampsNs: List<Long>,
    gyroFile: String?,
    rotationVectorFile: String?,
    gyroSampleCount: Int,
    rotationVectorSampleCount: Int,
    motionInfo: String
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
        .put("jobType", "YUV_BURST_GRAY")
        .put("status", status)
        .put("cameraId", cameraId)
        .put("width", width)
        .put("height", height)
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
