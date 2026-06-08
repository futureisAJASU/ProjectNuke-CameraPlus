package com.projectnuke.keplernightlab

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max

@Composable
fun Camera2Preview(
    modifier: Modifier = Modifier,
    cameraId: String,
    zoomRatio: Float = 1.0f,
    focusAeState: FocusAeState = FocusAeState(),
    enabled: Boolean = true,
    onAeCapabilitiesChanged: (minIndex: Int, maxIndex: Int, stepEv: Float) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    var textureView by remember { mutableStateOf<TextureView?>(null) }

    val controller = remember(
        cameraId,
        zoomRatio,
        focusAeState.point,
        focusAeState.locked,
        focusAeState.exposureCompensationIndex
    ) {
        CameraPreviewController(
            context = context.applicationContext,
            cameraId = cameraId,
            zoomRatio = zoomRatio,
            focusAeState = focusAeState,
            onAeCapabilitiesChanged = onAeCapabilitiesChanged
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).also { view ->
                textureView = view
            }
        }
    )

    DisposableEffect(
        textureView,
        enabled,
        cameraId,
        zoomRatio,
        focusAeState.point,
        focusAeState.locked,
        focusAeState.exposureCompensationIndex,
        controller
    ) {
        val view = textureView

        if (enabled && view != null) {
            controller.start(view)
        } else {
            controller.stop()
        }

        onDispose {
            controller.stop()
        }
    }
}

private class CameraPreviewController(
    private val context: Context,
    private val cameraId: String,
    private val zoomRatio: Float,
    private val focusAeState: FocusAeState,
    private val onAeCapabilitiesChanged: (minIndex: Int, maxIndex: Int, stepEv: Float) -> Unit
) {
    private val rotationOverrideDegrees: Int? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var previewSurface: Surface? = null
    private var started = false

    fun start(textureView: TextureView) {
        if (started) return

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                openCamera(textureView)
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                applyPreviewTransform(textureView)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stop()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        if (textureView.isAvailable) {
            openCamera(textureView)
        }
    }

    fun stop() {
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { previewSurface?.release() } catch (_: Exception) {}
        try { backgroundThread?.quitSafely() } catch (_: Exception) {}

        captureSession = null
        cameraDevice = null
        previewSurface = null
        backgroundThread = null
        backgroundHandler = null
        started = false
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(textureView: TextureView) {
        if (started) return
        started = true

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        backgroundThread = HandlerThread("KeplerPreviewThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val aeRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val aeStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            onAeCapabilitiesChanged(
                aeRange?.lower ?: 0,
                aeRange?.upper ?: 0,
                aeStep?.toFloat() ?: 0f
            )
            val previewSize = choosePreviewSize(characteristics)

            val surfaceTexture = textureView.surfaceTexture
            if (surfaceTexture == null) {
                stop()
                return
            }

            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            applyPreviewTransform(textureView)

            val surface = Surface(surfaceTexture)
            previewSurface = surface

            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createPreviewSession(camera, surface, characteristics)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        stop()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        stop()
                    }
                },
                backgroundHandler
            )
        } catch (_: Exception) {
            stop()
        }
    }

    private fun createPreviewSession(
        camera: CameraDevice,
        surface: Surface,
        characteristics: CameraCharacteristics
    ) {
        try {
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session

                        try {
                            val request = camera.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW
                            ).apply {
                                addTarget(surface)

                                set(
                                    CaptureRequest.CONTROL_MODE,
                                    CaptureRequest.CONTROL_MODE_AUTO
                                )

                                set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )

                                set(
                                    CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.CONTROL_AE_MODE_ON
                                )

                                applyZoomAndFocusAe(characteristics, zoomRatio, focusAeState)
                            }.build()

                            session.setRepeatingRequest(
                                request,
                                null,
                                backgroundHandler
                            )

                            if (focusAeState.point != null) {
                                val triggerRequest = camera.createCaptureRequest(
                                    CameraDevice.TEMPLATE_PREVIEW
                                ).apply {
                                    addTarget(surface)
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    applyZoomAndFocusAe(characteristics, zoomRatio, focusAeState)
                                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                                    // TODO: robust AF state machine; currently one trigger then repeating regions.
                                }.build()
                                session.capture(triggerRequest, null, backgroundHandler)
                            }
                        } catch (_: Exception) {
                            stop()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        stop()
                    }
                },
                backgroundHandler
            )
        } catch (_: Exception) {
            stop()
        }
    }

    private fun choosePreviewSize(
        characteristics: CameraCharacteristics
    ): Size {
        val map = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )

        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)

        if (sizes.isNullOrEmpty()) {
            return Size(1440, 1080)
        }

        val fourByThree = sizes.filter { size ->
            abs((size.width.toFloat() / size.height.toFloat()) - (4f / 3f)) < 0.05f
        }

        return fourByThree
            .filter { it.width <= 1920 && it.height <= 1440 }
            .maxByOrNull { it.width * it.height }
            ?: fourByThree.maxByOrNull { it.width * it.height }
            ?: sizes.maxByOrNull { it.width * it.height }
            ?: Size(1440, 1080)
    }

    private fun applyPreviewTransform(textureView: TextureView) {
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()

        if (viewWidth <= 0f || viewHeight <= 0f) return

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = runCatching {
            cameraManager.getCameraCharacteristics(cameraId)
        }.getOrNull() ?: return
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
        val displayRotation = textureView.display?.rotation ?: Surface.ROTATION_0
        val displayDegrees = surfaceRotationToDegrees(displayRotation)
        val rotationDegrees = rotationOverrideDegrees ?: calculatePreviewRotationDegrees(
            sensorOrientation = sensorOrientation,
            displayDegrees = displayDegrees,
            lensFacing = lensFacing
        )

        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        val bufferWidth = viewHeight
        val bufferHeight = viewWidth
        val viewRect = RectF(0f, 0f, viewWidth, viewHeight)
        val bufferRect = RectF(0f, 0f, bufferWidth, bufferHeight).apply {
            offset(centerX - centerX(), centerY - centerY())
        }

        // TODO: replace temporary rotationOverrideDegrees with fully verified device matrix table.
        val matrix = Matrix().apply {
            setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(viewHeight / bufferHeight, viewWidth / bufferWidth)
            postScale(scale, scale, centerX, centerY)
            postRotate(rotationDegrees.toFloat(), centerX, centerY)
        }

        textureView.setTransform(matrix)
    }

    private fun surfaceRotationToDegrees(rotation: Int): Int {
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun calculatePreviewRotationDegrees(
        sensorOrientation: Int,
        displayDegrees: Int,
        lensFacing: Int?
    ): Int {
        return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (360 - ((sensorOrientation + displayDegrees) % 360)) % 360
        } else {
            (sensorOrientation - displayDegrees + 360) % 360
        }
    }
}
