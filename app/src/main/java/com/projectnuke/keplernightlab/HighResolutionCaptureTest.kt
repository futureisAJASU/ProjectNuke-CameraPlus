package com.projectnuke.keplernightlab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private data class Processed50Selection(
    val size: Size,
    val format: Int,
    val source: String,
    val requiresMaximumResolutionPixelMode: Boolean
)

@SuppressLint("MissingPermission")
fun capture50MpProcessedTest(
    context: Context,
    cameraId: String,
    onStatus: (String) -> Unit,
    onComplete: (File) -> Unit,
    onError: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    fun post(message: String) = mainHandler.post { onStatus(message) }
    val thread = HandlerThread("Kepler50MpProcessedTest").apply { start() }
    val handler = Handler(thread.looper)
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val finished = AtomicBoolean(false)
    var camera: CameraDevice? = null
    var session: CameraCaptureSession? = null
    var reader: ImageReader? = null
    var jobFile: File? = null
    var job: JSONObject? = null

    fun cleanup() {
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { reader?.close() }
        runCatching { thread.quitSafely() }
    }

    fun fail(message: String) {
        if (!finished.compareAndSet(false, true)) return
        job?.put("status", "CAPTURE_FAILED")
            ?.put("failureReason", message)
            ?.put("updatedAt", System.currentTimeMillis())
        runCatching {
            jobFile?.parentFile?.let { dir ->
                KeplerJobMetadata.update(dir) { current ->
                    current.put("status", "CAPTURE_FAILED")
                        .put("failureReason", message)
                        .put("updatedAt", System.currentTimeMillis())
                }
            }
        }
        post("PIPELINE_FAILED: Test 50M YUV/JPEG Capture failed. $message")
        mainHandler.post { onError(message) }
        cleanup()
    }

    try {
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val selection = chooseProcessed50Selection(characteristics)
            ?: error("No >=40MP YUV/JPEG stream exposed in normal or maximum-resolution maps.")
        val pixelModeKeyAvailable = characteristics.getAvailableCaptureRequestKeys()
            ?.contains(CaptureRequest.SENSOR_PIXEL_MODE) == true
        if (selection.requiresMaximumResolutionPixelMode && !pixelModeKeyAvailable) {
            error("50MP processed stream exists only in maximum-resolution map, but SENSOR_PIXEL_MODE request key is unavailable.")
        }

        val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: error("Pictures directory unavailable.")
        val root = File(pictures, "Kepler50MpTest").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val jobDir = File(root, "KPL_50MP_PROCESSED_$stamp").apply { mkdirs() }
        jobFile = File(jobDir, JOB_JSON_FILE_NAME)
        val isJpeg = selection.format == ImageFormat.JPEG
        val outputName = if (isJpeg) "capture_50mp.jpg" else "capture_50mp_yuv.png"
        job = JSONObject()
            .put("jobType", "50MP_PROCESSED_TEST")
            .put("status", "CAPTURING")
            .put("cameraId", cameraId)
            .put("physicalCameraId", JSONObject.NULL)
            .put("sizeSource", selection.source)
            .put("requiresMaximumResolutionPixelMode", selection.requiresMaximumResolutionPixelMode)
            .put("sensorPixelModeUsed", false)
            .put("exportFormatUsed", if (isJpeg) "JPEG" else "PNG_FROM_YUV")
            .put(if (isJpeg) "jpegWidth" else "yuvWidth", selection.size.width)
            .put(if (isJpeg) "jpegHeight" else "yuvHeight", selection.size.height)
            .put("outputFile", outputName)
            .put("createdAt", System.currentTimeMillis())
        KeplerJobMetadata.write(jobFile.parentFile ?: error("Job directory missing"), job)

        reader = ImageReader.newInstance(
            selection.size.width,
            selection.size.height,
            selection.format,
            2
        )
        reader?.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (finished.get()) return@setOnImageAvailableListener
                val output = File(jobDir, outputName)
                if (isJpeg) {
                    saveJpegImage(image, output)
                } else {
                    saveRotatedColorPngFromYuv(image, output, 0)
                }
                if (!finished.compareAndSet(false, true)) return@setOnImageAvailableListener
                job?.put("status", "CAPTURE_COMPLETE")
                    ?.put("fileSizeBytes", output.length())
                    ?.put("updatedAt", System.currentTimeMillis())
                KeplerJobMetadata.update(jobFile.parentFile ?: error("Job directory missing")) { current ->
                    current.put("status", "CAPTURE_COMPLETE")
                        .put("fileSizeBytes", output.length())
                        .put("updatedAt", System.currentTimeMillis())
                }
                post(
                    "PIPELINE_COMPLETE: Test 50M ${if (isJpeg) "JPEG" else "YUV"} Capture success. " +
                        "cameraId=$cameraId, size=${selection.size.width}x${selection.size.height}, " +
                        "source=${selection.source}, sensorPixelModeUsed=${job.optBoolean("sensorPixelModeUsed")}."
                )
                mainHandler.post { onComplete(jobDir) }
                cleanup()
            } catch (error: Exception) {
                fail("${error.javaClass.simpleName}: ${error.message}")
            } finally {
                image.close()
            }
        }, handler)

        manager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(opened: CameraDevice) {
                    camera = opened
                    opened.createCaptureSession(
                        listOf(reader!!.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                session = configured
                                try {
                                    val request = opened.createCaptureRequest(
                                        CameraDevice.TEMPLATE_STILL_CAPTURE
                                    ).apply {
                                        addTarget(reader!!.surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        if (selection.requiresMaximumResolutionPixelMode) {
                                            set(
                                                CaptureRequest.SENSOR_PIXEL_MODE,
                                                CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
                                            )
                                            job?.put("sensorPixelModeUsed", true)
                                            jobFile?.parentFile?.let { dir ->
                                                KeplerJobMetadata.update(dir) { current ->
                                                    current.put("sensorPixelModeUsed", true)
                                                }
                                            }
                                        }
                                    }.build()
                                    configured.capture(
                                        request,
                                        object : CameraCaptureSession.CaptureCallback() {},
                                        handler
                                    )
                                } catch (error: Exception) {
                                    fail("${error.javaClass.simpleName}: ${error.message}")
                                }
                            }

                            override fun onConfigureFailed(failed: CameraCaptureSession) {
                                fail("Camera capture session configuration failed.")
                            }
                        },
                        handler
                    )
                }

                override fun onDisconnected(disconnected: CameraDevice) {
                    fail("Camera disconnected.")
                }

                override fun onError(failed: CameraDevice, error: Int) {
                    fail("Camera error=$error")
                }
            },
            handler
        )
        post(
            "Test 50M processed capture: cameraId=$cameraId, " +
                "${selection.size.width}x${selection.size.height}, source=${selection.source}."
        )
    } catch (error: Exception) {
        fail("${error.javaClass.simpleName}: ${error.message}")
    }
}

private fun chooseProcessed50Selection(
    characteristics: CameraCharacteristics
): Processed50Selection? {
    val normal = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val maximum = if (Build.VERSION.SDK_INT >= 31) {
        characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
        )
    } else {
        null
    }
    fun candidates(map: android.hardware.camera2.params.StreamConfigurationMap?, format: Int) =
        map?.getOutputSizes(format)
            ?.filter { megapixels(it) >= HIGH_RES_RAW_INPUT_MIN_MP }
            ?.sortedByDescending { it.width.toLong() * it.height }
            .orEmpty()

    candidates(maximum, ImageFormat.JPEG).firstOrNull()?.let {
        return Processed50Selection(it, ImageFormat.JPEG, "MAXIMUM_RESOLUTION_MAP", true)
    }
    candidates(normal, ImageFormat.JPEG).firstOrNull()?.let {
        return Processed50Selection(it, ImageFormat.JPEG, "NORMAL_MAP", false)
    }
    candidates(maximum, ImageFormat.YUV_420_888).firstOrNull()?.let {
        return Processed50Selection(it, ImageFormat.YUV_420_888, "MAXIMUM_RESOLUTION_MAP", true)
    }
    candidates(normal, ImageFormat.YUV_420_888).firstOrNull()?.let {
        return Processed50Selection(it, ImageFormat.YUV_420_888, "NORMAL_MAP", false)
    }
    return null
}

private fun saveJpegImage(image: Image, output: File) {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    FileOutputStream(output).use { it.write(bytes) }
}
