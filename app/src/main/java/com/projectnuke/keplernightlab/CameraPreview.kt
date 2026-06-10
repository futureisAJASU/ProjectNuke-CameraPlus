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
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlin.math.abs

// Galaxy S24 temporary preview rotation override. Try 0/90/180/270 only while sensor/display transform is being verified.
private const val PREVIEW_ROTATION_FIX_DEGREES = 0f

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
    val latestOnAeCapabilitiesChanged = rememberUpdatedState(onAeCapabilitiesChanged)

    val controller = remember(cameraId) {
        CameraPreviewController(
            context = context.applicationContext,
            cameraId = cameraId,
            onAeCapabilitiesChangedProvider = { latestOnAeCapabilitiesChanged.value }
        )
    }

    LaunchedEffect(zoomRatio) {
        controller.updateZoomRatio(zoomRatio)
    }

    LaunchedEffect(focusAeState) {
        controller.updateFocusAeState(focusAeState)
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
    private val onAeCapabilitiesChangedProvider: () -> (minIndex: Int, maxIndex: Int, stepEv: Float) -> Unit
) {
    private val lock = Any()
    private var generation = 0
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var previewSurface: Surface? = null
    private var currentPreviewSize: Size? = null
    @Volatile private var latestZoomRatio: Float = 1.0f
    @Volatile private var latestFocusAeState: FocusAeState = FocusAeState()
    @Volatile private var started = false

    fun start(textureView: TextureView) {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val localGeneration = synchronized(lock) {
            if (started && backgroundHandler != null && previewSurface != null) return
            generation += 1
            started = true
            generation
        }
        Log.d(TAG, "start generation=$localGeneration cameraId=$cameraId zoom=$latestZoomRatio")

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                openCamera(textureView, localGeneration)
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                currentPreviewSize?.let { previewSize ->
                    configureTransform(textureView, previewSize)
                }
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stop()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        if (textureView.isAvailable) {
            openCamera(textureView, localGeneration)
        }
    }

    fun stop() {
        val refs = synchronized(lock) {
            generation += 1
            started = false
            Log.d(TAG, "stop generation=$generation")
            StopRefs(captureSession, cameraDevice, previewSurface, backgroundThread)
                .also {
                    captureSession = null
                    cameraDevice = null
                    cameraCharacteristics = null
                    previewSurface = null
                    backgroundThread = null
                    backgroundHandler = null
                    currentPreviewSize = null
                }
        }

        runCatching { refs.session?.stopRepeating() }
        runCatching { refs.session?.close() }
        runCatching { refs.device?.close() }
        runCatching { refs.surface?.release() }
        runCatching { refs.thread?.quitSafely() }
    }

    fun updateFocusAeState(newState: FocusAeState) {
        val previous = latestFocusAeState
        latestFocusAeState = newState
        val handler = backgroundHandler ?: return
        val localGeneration = synchronized(lock) { generation }
        handler.post {
            applyFocusAeStateOnCameraThread(
                newState = newState,
                previousState = previous,
                localGeneration = localGeneration
            )
        }
    }

    fun updateZoomRatio(newZoomRatio: Float) {
        val previous = latestZoomRatio
        latestZoomRatio = newZoomRatio.coerceAtLeast(0.1f)
        val handler = backgroundHandler ?: return
        val localGeneration = synchronized(lock) { generation }
        handler.post {
            if (!isActive(localGeneration)) return@post
            applyFocusAeStateOnCameraThread(
                newState = latestFocusAeState,
                previousState = latestFocusAeState,
                localGeneration = localGeneration
            )
            if (kotlin.math.abs(previous - latestZoomRatio) >= 0.02f) {
                Log.d(TAG, "zoom changed ratio=$latestZoomRatio")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(textureView: TextureView, localGeneration: Int) {
        if (!isActive(localGeneration) || !textureView.isAvailable) return

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val thread = HandlerThread("KeplerPreviewThread").apply { start() }
        val handler = Handler(thread.looper)
        synchronized(lock) {
            if (!isActiveLocked(localGeneration)) {
                thread.quitSafely()
                return
            }
            backgroundThread = thread
            backgroundHandler = handler
        }

        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            cameraCharacteristics = characteristics
            val aeRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val aeStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            onAeCapabilitiesChangedProvider().invoke(
                aeRange?.lower ?: 0,
                aeRange?.upper ?: 0,
                aeStep?.toFloat() ?: 0f
            )
            val previewSize = choosePreviewSize(characteristics)
            currentPreviewSize = previewSize

            val surfaceTexture = textureView.surfaceTexture
            if (surfaceTexture == null) {
                stop()
                return
            }

            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            configureTransform(textureView, previewSize)

            val surface = Surface(surfaceTexture)
            if (!isActive(localGeneration) || !textureView.isAvailable) {
                runCatching { surface.release() }
                return
            }
            previewSurface = surface

            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (!isActive(localGeneration) || !textureView.isAvailable) {
                            Log.w(TAG, "stale onOpened ignored generation=$localGeneration")
                            runCatching { camera.close() }
                            return
                        }
                        cameraDevice = camera
                        createPreviewSession(camera, surface, characteristics, localGeneration, textureView)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        runCatching { camera.close() }
                        if (isActive(localGeneration)) stop()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.w(TAG, "camera error=$error generation=$localGeneration")
                        runCatching { camera.close() }
                        if (isActive(localGeneration)) stop()
                    }
                },
                handler
            )
        } catch (_: Exception) {
            stop()
        }
    }

    private fun createPreviewSession(
        camera: CameraDevice,
        surface: Surface,
        characteristics: CameraCharacteristics,
        localGeneration: Int,
        textureView: TextureView
    ) {
        if (!isActive(localGeneration) || !textureView.isAvailable) {
            runCatching { camera.close() }
            return
        }
        try {
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!isActive(localGeneration) || !textureView.isAvailable) {
                            Log.w(TAG, "stale onConfigured ignored generation=$localGeneration")
                            runCatching { session.close() }
                            return
                        }
                        captureSession = session

                        try {
                            applyFocusAeStateOnCameraThread(
                                newState = latestFocusAeState,
                                previousState = FocusAeState(),
                                localGeneration = localGeneration,
                                forceTrigger = latestFocusAeState.point != null
                            )
                        } catch (_: Exception) {
                            stop()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runCatching { session.close() }
                        if (isActive(localGeneration)) stop()
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
            size.width > size.height &&
                    abs((size.width.toFloat() / size.height.toFloat()) - (4f / 3f)) < 0.05f
        }

        return fourByThree
            .filter { it.width <= 1920 && it.height <= 1440 }
            .maxByOrNull { it.width * it.height }
            ?: fourByThree.maxByOrNull { it.width * it.height }
            ?: sizes.maxByOrNull { it.width * it.height }
            ?: Size(1440, 1080)
    }

    private fun applyFocusAeStateOnCameraThread(
        newState: FocusAeState,
        previousState: FocusAeState,
        localGeneration: Int,
        forceTrigger: Boolean = false
    ) {
        if (!isActive(localGeneration)) return
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val surface = previewSurface ?: return
        val characteristics = cameraCharacteristics ?: return
        val handler = backgroundHandler ?: return
        val zoom = latestZoomRatio
        val pointChanged = previousState.point != newState.point
        val lockChanged = previousState.locked != newState.locked
        val evChanged = previousState.exposureCompensationIndex != newState.exposureCompensationIndex
        val meteringRegion = buildFocusAeMeteringRectangle(characteristics, zoom, newState.point)
        val cropRegion = buildCenterCropRegion(characteristics, zoom)

        runCatching {
            if ((pointChanged || forceTrigger) && newState.point != null) {
                // Tap is focus trigger. LOCK only holds current AE/AF behavior.
                session.capture(
                    buildPreviewRequest(
                        camera = camera,
                        surface = surface,
                        characteristics = characteristics,
                        state = newState,
                        zoomRatio = zoom,
                        afMode = CaptureRequest.CONTROL_AF_MODE_AUTO,
                        afTrigger = CaptureRequest.CONTROL_AF_TRIGGER_START
                    ),
                    null,
                    handler
                )
                Log.d(TAG, "AF trigger sent point=${newState.point}")
            }

            session.setRepeatingRequest(
                buildPreviewRequest(
                    camera = camera,
                    surface = surface,
                    characteristics = characteristics,
                    state = newState,
                    zoomRatio = zoom,
                    afMode = if (newState.point != null) {
                        CaptureRequest.CONTROL_AF_MODE_AUTO
                    } else {
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    },
                    afTrigger = CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                ),
                null,
                handler
            )
            Log.d(
                TAG,
                "AF/AE apply pointChanged=$pointChanged lockChanged=$lockChanged evChanged=$evChanged " +
                    "afRegions=${if (meteringRegion != null) 1 else 0} aeRegions=${if (meteringRegion != null) 1 else 0} " +
                    "zoomRatio=$zoom cropRegion=$cropRegion"
            )
            if (pointChanged) Log.d(TAG, "AF/AE point applied point=${newState.point}")
            if (lockChanged) Log.d(TAG, "AE lock changed locked=${newState.locked}")
            if (evChanged) Log.d(TAG, "EV compensation changed index=${newState.exposureCompensationIndex}")
        }.onFailure {
            Log.w(TAG, "applyFocusAeState failed", it)
        }
    }

    private fun buildPreviewRequest(
        camera: CameraDevice,
        surface: Surface,
        characteristics: CameraCharacteristics,
        state: FocusAeState,
        zoomRatio: Float,
        afMode: Int,
        afTrigger: Int
    ) = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(surface)
        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        set(CaptureRequest.CONTROL_AF_MODE, afMode)
        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        set(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger)
        applyZoomAndFocusAe(characteristics, zoomRatio, state)
    }.build()

    private fun isActive(localGeneration: Int): Boolean = synchronized(lock) {
        isActiveLocked(localGeneration)
    }

    private fun isActiveLocked(localGeneration: Int): Boolean {
        return started && generation == localGeneration
    }

    private fun configureTransform(
        textureView: TextureView,
        previewSize: Size
    ) {
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()

        if (viewWidth <= 0f || viewHeight <= 0f) return

        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        val viewRect = RectF(0f, 0f, viewWidth, viewHeight)
        val characteristics = cameraCharacteristics
        val sensorOrientation = characteristics
            ?.get(CameraCharacteristics.SENSOR_ORIENTATION)
            ?: 0
        val displayRotation = textureView.display?.rotation ?: Surface.ROTATION_0
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val relativeRotation = (sensorOrientation - displayDegrees + 360) % 360
        val swapDimensions = relativeRotation == 90 || relativeRotation == 270
        val rotatedBufferWidth = if (swapDimensions) {
            previewSize.height.toFloat()
        } else {
            previewSize.width.toFloat()
        }
        val rotatedBufferHeight = if (swapDimensions) {
            previewSize.width.toFloat()
        } else {
            previewSize.height.toFloat()
        }
        val scaleX = viewWidth / rotatedBufferWidth
        val scaleY = viewHeight / rotatedBufferHeight
        val finalScale = kotlin.math.max(scaleX, scaleY)
        Log.d(
            TAG,
            "configureTransform view=${viewWidth}x$viewHeight previewSize=${previewSize.width}x${previewSize.height} " +
                "sensorOrientation=$sensorOrientation displayRotation=$displayRotation relativeRotation=$relativeRotation " +
                "swapDimensions=$swapDimensions scaleX=$scaleX scaleY=$scaleY finalScale=$finalScale " +
                "rotationFix=$PREVIEW_ROTATION_FIX_DEGREES"
        )

        val bufferRect = RectF(
            0f,
            0f,
            rotatedBufferWidth,
            rotatedBufferHeight
        ).apply {
            offset(centerX - centerX(), centerY - centerY())
        }

        val matrix = Matrix().apply {
            setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            postScale(finalScale, finalScale, centerX, centerY)
            val displayCorrection = when (displayRotation) {
                Surface.ROTATION_90 -> -90f
                Surface.ROTATION_180 -> 180f
                Surface.ROTATION_270 -> 90f
                else -> 0f
            }
            postRotate(displayCorrection, centerX, centerY)
            postRotate(PREVIEW_ROTATION_FIX_DEGREES, centerX, centerY)
        }

        textureView.setTransform(matrix)
    }

    private data class StopRefs(
        val session: CameraCaptureSession?,
        val device: CameraDevice?,
        val surface: Surface?,
        val thread: HandlerThread?
    )

    private companion object {
        private const val TAG = "KeplerCameraPreview"
    }
}
