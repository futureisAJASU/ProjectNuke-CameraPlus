package com.projectnuke.keplernightlab

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
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
import kotlin.math.max
import kotlin.math.roundToInt

// Galaxy S24 temporary preview rotation override. Try 0/90/180/270 only while sensor/display transform is being verified.
private const val PREVIEW_ROTATION_FIX_DEGREES = 0f

@Composable
fun Camera2Preview(
    modifier: Modifier = Modifier,
    cameraId: String,
    zoomRatio: Float = 1.0f,
    focusAeState: FocusAeState = FocusAeState(),
    meteringMode: MeteringMode = MeteringModeState.mode,
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

    LaunchedEffect(meteringMode) {
        controller.updateMeteringMode(meteringMode)
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
    @Volatile private var latestMeteringMode: MeteringMode = MeteringModeState.mode
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
        Log.d(TAG, "start generation=$localGeneration cameraId=$cameraId zoom=$latestZoomRatio metering=$latestMeteringMode")

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

    fun updateMeteringMode(newMode: MeteringMode) {
        val previous = latestMeteringMode
        latestMeteringMode = newMode
        val handler = backgroundHandler ?: return
        val localGeneration = synchronized(lock) { generation }
        handler.post {
            if (!isActive(localGeneration)) return@post
            applyFocusAeStateOnCameraThread(
                newState = latestFocusAeState,
                previousState = latestFocusAeState,
                localGeneration = localGeneration
            )
            if (previous != newMode) {
                Log.d(TAG, "metering changed mode=$newMode")
            }
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
            if (abs(previous - latestZoomRatio) >= 0.02f) {
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
        val meteringMode = latestMeteringMode
        val pointChanged = previousState.point != newState.point
        val lockChanged = previousState.locked != newState.locked
        val evChanged = previousState.exposureCompensationIndex != newState.exposureCompensationIndex
        val aeRegions = buildAeMeteringRegions(characteristics, zoom, meteringMode, newState.point)
        val cropRegion = buildCenterCropRegion(characteristics, zoom)

        runCatching {
            if ((pointChanged || forceTrigger) && newState.point != null) {
                session.capture(
                    buildPreviewRequest(
                        camera = camera,
                        surface = surface,
                        characteristics = characteristics,
                        state = newState,
                        zoomRatio = zoom,
                        meteringMode = meteringMode,
                        aeRegions = aeRegions,
                        afMode = CaptureRequest.CONTROL_AF_MODE_AUTO,
                        afTrigger = CaptureRequest.CONTROL_AF_TRIGGER_START
                    ),
                    null,
                    handler
                )
                Log.d(TAG, "AF trigger sent point=${newState.point} metering=$meteringMode")
            }

            session.setRepeatingRequest(
                buildPreviewRequest(
                    camera = camera,
                    surface = surface,
                    characteristics = characteristics,
                    state = newState,
                    zoomRatio = zoom,
                    meteringMode = meteringMode,
                    aeRegions = aeRegions,
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
                    "mode=$meteringMode aeRegions=${aeRegions.size} zoomRatio=$zoom cropRegion=$cropRegion"
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
        meteringMode: MeteringMode,
        aeRegions: Array<MeteringRectangle>,
        afMode: Int,
        afTrigger: Int
    ) = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(surface)
        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        set(CaptureRequest.CONTROL_AF_MODE, afMode)
        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        set(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger)
        applyZoomAndFocusAf(characteristics, zoomRatio, state)
        applyPreviewAeMetering(characteristics, meteringMode, aeRegions)
    }.build()

    private fun CaptureRequest.Builder.applyZoomAndFocusAf(
        characteristics: CameraCharacteristics,
        zoomRatio: Float,
        state: FocusAeState
    ) {
        val cropRegion = buildCenterCropRegion(characteristics, zoomRatio)
        set(CaptureRequest.SCALER_CROP_REGION, cropRegion)

        val aeRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val compensation = if (aeRange != null) {
            state.exposureCompensationIndex.coerceIn(aeRange.lower, aeRange.upper)
        } else {
            state.exposureCompensationIndex
        }
        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
        set(CaptureRequest.CONTROL_AE_LOCK, state.locked)

        val maxAfRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        if (maxAfRegions > 0 && state.point != null) {
            buildMeteringRectangle(
                characteristics = characteristics,
                zoomRatio = zoomRatio,
                point = state.point,
                fraction = 0.18f,
                weight = MeteringRectangle.METERING_WEIGHT_MAX
            )?.let { region ->
                set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
        }
    }

    private fun CaptureRequest.Builder.applyPreviewAeMetering(
        characteristics: CameraCharacteristics,
        meteringMode: MeteringMode,
        aeRegions: Array<MeteringRectangle>
    ) {
        val maxAeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        if (maxAeRegions <= 0) return
        if (meteringMode == MeteringMode.AVERAGE || aeRegions.isEmpty()) return
        set(CaptureRequest.CONTROL_AE_REGIONS, aeRegions.take(maxAeRegions).toTypedArray())
    }

    private fun buildAeMeteringRegions(
        characteristics: CameraCharacteristics,
        zoomRatio: Float,
        meteringMode: MeteringMode,
        touchPoint: NormalizedPoint?
    ): Array<MeteringRectangle> {
        val cropRegion = buildCenterCropRegion(characteristics, zoomRatio)
        return when (meteringMode) {
            MeteringMode.AVERAGE -> emptyArray()
            MeteringMode.CENTER_WEIGHTED -> {
                val full = MeteringRectangle(cropRegion, 120)
                val center = buildMeteringRectangle(
                    characteristics = characteristics,
                    zoomRatio = zoomRatio,
                    point = NormalizedPoint(0.5f, 0.5f),
                    fraction = 0.72f,
                    weight = MeteringRectangle.METERING_WEIGHT_MAX
                )
                listOfNotNull(full, center).toTypedArray()
            }
            MeteringMode.CENTER -> {
                buildMeteringRectangle(
                    characteristics = characteristics,
                    zoomRatio = zoomRatio,
                    point = NormalizedPoint(0.5f, 0.5f),
                    fraction = 0.34f,
                    weight = MeteringRectangle.METERING_WEIGHT_MAX
                )?.let { arrayOf(it) } ?: emptyArray()
            }
            MeteringMode.SPOT -> {
                buildMeteringRectangle(
                    characteristics = characteristics,
                    zoomRatio = zoomRatio,
                    point = touchPoint ?: NormalizedPoint(0.5f, 0.5f),
                    fraction = 0.13f,
                    weight = MeteringRectangle.METERING_WEIGHT_MAX
                )?.let { arrayOf(it) } ?: emptyArray()
            }
        }
    }

    private fun buildMeteringRectangle(
        characteristics: CameraCharacteristics,
        zoomRatio: Float,
        point: NormalizedPoint?,
        fraction: Float,
        weight: Int
    ): MeteringRectangle? {
        val safePoint = point ?: return null
        val cropRegion = buildCenterCropRegion(characteristics, zoomRatio)
        val regionWidth = max(48, (cropRegion.width() * fraction).roundToInt())
        val regionHeight = max(48, (cropRegion.height() * fraction).roundToInt())
        val centerX = cropRegion.left + (cropRegion.width() * safePoint.x.coerceIn(0f, 1f)).roundToInt()
        val centerY = cropRegion.top + (cropRegion.height() * safePoint.y.coerceIn(0f, 1f)).roundToInt()
        val left = (centerX - regionWidth / 2).coerceIn(cropRegion.left, cropRegion.right - regionWidth)
        val top = (centerY - regionHeight / 2).coerceIn(cropRegion.top, cropRegion.bottom - regionHeight)
        val rect = Rect(
            left,
            top,
            left + regionWidth,
            top + regionHeight
        )
        return MeteringRectangle(rect, weight.coerceIn(0, MeteringRectangle.METERING_WEIGHT_MAX))
    }

    private fun buildCenterCropRegion(
        characteristics: CameraCharacteristics,
        zoomRatio: Float
    ): Rect {
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return Rect(0, 0, 1, 1)
        val safeZoom = max(1f, zoomRatio)
        val cropWidth = max(1, (activeArray.width() / safeZoom).roundToInt())
        val cropHeight = max(1, (activeArray.height() / safeZoom).roundToInt())
        val left = activeArray.left + (activeArray.width() - cropWidth) / 2
        val top = activeArray.top + (activeArray.height() - cropHeight) / 2
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

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
        val finalScale = max(scaleX, scaleY)
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
