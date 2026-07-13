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
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
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

@Composable
fun Camera2Preview(
    modifier: Modifier = Modifier,
    cameraId: String,
    physicalCameraId: String? = null,
    zoomRatio: Float = 1.0f,
    selectedLensSlot: LensSlot,
    selectedThreeXSource: ThreeXSourceMode,
    actualLensSource: ActualLensSource,
    focusAeState: FocusAeState = FocusAeState(),
    meteringMode: MeteringMode = MeteringModeState.mode,
    enabled: Boolean = true,
    onAeCapabilitiesChanged: (minIndex: Int, maxIndex: Int, stepEv: Float) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    val latestOnAeCapabilitiesChanged = rememberUpdatedState(onAeCapabilitiesChanged)

    val controller = remember(cameraId, physicalCameraId) {
        CameraPreviewController(
            context = context.applicationContext,
            cameraId = cameraId,
            physicalCameraId = physicalCameraId,
            actualLensSource = actualLensSource,
            onAeCapabilitiesChangedProvider = { latestOnAeCapabilitiesChanged.value }
        )
    }

    LaunchedEffect(zoomRatio) {
        controller.updateZoomRatio(zoomRatio)
    }

    LaunchedEffect(selectedLensSlot, selectedThreeXSource, actualLensSource) {
        controller.updateLensDiagnostics(
            selectedLensSlot = selectedLensSlot,
            selectedThreeXSource = selectedThreeXSource,
            actualLensSource = actualLensSource
        )
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
    private val physicalCameraId: String?,
    private val actualLensSource: ActualLensSource,
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
    @Volatile private var latestSelectedLensSlot: LensSlot = LensSlot.MAIN_1X
    @Volatile private var latestSelectedThreeXSource: ThreeXSourceMode = ThreeXSourceMode.OPTICAL
    @Volatile private var latestActualLensSource: ActualLensSource = ActualLensSource.MAIN_1X
    private var lastActivePhysicalLog: String? = null
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

    fun updateLensDiagnostics(
        selectedLensSlot: LensSlot,
        selectedThreeXSource: ThreeXSourceMode,
        actualLensSource: ActualLensSource
    ) {
        latestSelectedLensSlot = selectedLensSlot
        latestSelectedThreeXSource = selectedThreeXSource
        latestActualLensSource = actualLensSource
        lastActivePhysicalLog = null
    }

    private val activePhysicalCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val activePhysicalId = if (Build.VERSION.SDK_INT >= 28) {
                result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            } else {
                null
            }
            val resultZoomRatio = if (Build.VERSION.SDK_INT >= 30) {
                result.get(CaptureResult.CONTROL_ZOOM_RATIO)
            } else {
                null
            }
            val message =
                "requestedZoomRatio=$latestZoomRatio " +
                    "selectedLensSlot=$latestSelectedLensSlot " +
                    "selected3xSourceMode=$latestSelectedThreeXSource " +
                    "cameraId=$cameraId actualLensSource=$latestActualLensSource " +
                    "activePhysicalId=$activePhysicalId resultZoomRatio=$resultZoomRatio"
            if (message != lastActivePhysicalLog) {
                lastActivePhysicalLog = message
                Log.i("KeplerActivePhysical", message)
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
            if (!storeCameraCharacteristics(localGeneration, characteristics)) {
                thread.quitSafely()
                return
            }
            val aeRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val aeStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            onAeCapabilitiesChangedProvider().invoke(
                aeRange?.lower ?: 0,
                aeRange?.upper ?: 0,
                aeStep?.toFloat() ?: 0f
            )
            val previewSize = choosePreviewSize(characteristics)
            if (!storeCurrentPreviewSize(localGeneration, previewSize)) {
                thread.quitSafely()
                return
            }

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
            if (!storePreviewSurface(localGeneration, surface)) {
                runCatching { surface.release() }
                return
            }

            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (!isActive(localGeneration) || !textureView.isAvailable) {
                            Log.w(TAG, "stale onOpened ignored generation=$localGeneration")
                            runCatching { camera.close() }
                            return
                        }
                        if (!storeCameraDevice(localGeneration, camera)) {
                            Log.w(TAG, "stale onOpened dropped generation=$localGeneration")
                            runCatching { camera.close() }
                            return
                        }
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
        Log.i(
            "KeplerPhysicalRoute",
            "requestedPhysicalCameraId=$physicalCameraId cameraId=$cameraId " +
                "actualLensSource=$actualLensSource previewZoomRatio=$latestZoomRatio " +
                "sessionMode=${if (physicalCameraId != null && Build.VERSION.SDK_INT >= 28) "physicalOutput" else "normalOutput"}"
        )
        if (physicalCameraId != null && Build.VERSION.SDK_INT >= 28) {
            createPhysicalPreviewSession(
                camera = camera,
                surface = surface,
                characteristics = characteristics,
                localGeneration = localGeneration,
                textureView = textureView
            )
        } else {
            createNormalPreviewSession(
                camera = camera,
                surface = surface,
                localGeneration = localGeneration,
                textureView = textureView
            )
        }
    }

    private fun createPhysicalPreviewSession(
        camera: CameraDevice,
        surface: Surface,
        characteristics: CameraCharacteristics,
        localGeneration: Int,
        textureView: TextureView
    ) {
        val handler = backgroundHandler ?: return
        try {
            val output = OutputConfiguration(surface).apply {
                setPhysicalCameraId(physicalCameraId)
            }
            val callback = previewSessionStateCallback(
                localGeneration = localGeneration,
                textureView = textureView,
                sessionMode = "physicalOutput",
                onFailure = {
                    Log.w(
                        "KeplerPhysicalRoute",
                        "physical output failed; fallback=normalOutput previousZoomRatio=$latestZoomRatio " +
                            "selectedLensSlot=$latestSelectedLensSlot selectedThreeXSource=$latestSelectedThreeXSource " +
                            "actualLensSource=$latestActualLensSource cameraId=$cameraId " +
                            "physicalCameraId=$physicalCameraId"
                    )
                    createNormalPreviewSession(
                        camera = camera,
                        surface = surface,
                        localGeneration = localGeneration,
                        textureView = textureView
                    )
                }
            )
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(output),
                    { command -> handler.post(command) },
                    callback
                )
            )
        } catch (error: Exception) {
            if (!isActive(localGeneration)) {
                Log.d(TAG, "late physical preview failure ignored generation=$localGeneration")
                return
            }
            Log.e(
                "KeplerPhysicalRoute",
                "sessionMode=physicalOutput create failed cameraId=$cameraId " +
                    "physicalCameraId=$physicalCameraId exception=${error.javaClass.simpleName}:${error.message}",
                error
            )
            Log.w(
                "KeplerPhysicalRoute",
                "physical output create failed; fallback=normalOutput previousZoomRatio=$latestZoomRatio " +
                    "selectedLensSlot=$latestSelectedLensSlot selectedThreeXSource=$latestSelectedThreeXSource " +
                    "actualLensSource=$latestActualLensSource cameraId=$cameraId " +
                    "physicalCameraId=$physicalCameraId"
            )
            createNormalPreviewSession(
                camera = camera,
                surface = surface,
                localGeneration = localGeneration,
                textureView = textureView
            )
        }
    }

    private fun createNormalPreviewSession(
        camera: CameraDevice,
        surface: Surface,
        localGeneration: Int,
        textureView: TextureView
    ) {
        try {
            camera.createCaptureSession(
                listOf(surface),
                previewSessionStateCallback(
                    localGeneration = localGeneration,
                    textureView = textureView,
                    sessionMode = "normalOutput",
                    onFailure = {
                        if (isActive(localGeneration)) stop()
                    }
                ),
                backgroundHandler
            )
        } catch (error: Exception) {
            Log.e(
                "KeplerPhysicalRoute",
                "sessionMode=normalOutput create failed cameraId=$cameraId " +
                    "exception=${error.javaClass.simpleName}:${error.message}",
                error
            )
            stop()
        }
    }

    private fun previewSessionStateCallback(
        localGeneration: Int,
        textureView: TextureView,
        sessionMode: String,
        onFailure: () -> Unit
    ): CameraCaptureSession.StateCallback {
        return object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!isActive(localGeneration) || !textureView.isAvailable) {
                    Log.w(TAG, "stale onConfigured ignored generation=$localGeneration")
                    runCatching { session.close() }
                    return
                }
                if (!storeCaptureSession(localGeneration, session)) {
                    Log.w(TAG, "stale onConfigured dropped generation=$localGeneration")
                    runCatching { session.close() }
                    return
                }
                Log.i(
                    "KeplerPhysicalRoute",
                    "session configured mode=$sessionMode cameraId=$cameraId " +
                        "requestedPhysicalCameraId=$physicalCameraId previewZoomRatio=$latestZoomRatio"
                )

                try {
                    applyFocusAeStateOnCameraThread(
                        newState = latestFocusAeState,
                        previousState = FocusAeState(),
                        localGeneration = localGeneration,
                        forceTrigger = latestFocusAeState.point != null
                    )
                } catch (error: Exception) {
                    Log.e(
                        "KeplerPhysicalRoute",
                        "sessionMode=$sessionMode request failed " +
                            "exception=${error.javaClass.simpleName}:${error.message}",
                        error
                    )
                    runCatching { session.close() }
                    onFailure()
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(
                    "KeplerPhysicalRoute",
                    "session configuration failed mode=$sessionMode cameraId=$cameraId " +
                        "requestedPhysicalCameraId=$physicalCameraId"
                )
                runCatching { session.close() }
                onFailure()
            }
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
        val cropRegion = buildMeteringCropRegion(characteristics, zoom)

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
                    activePhysicalCaptureCallback,
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
                activePhysicalCaptureCallback,
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
        val zoomApplication = applyCamera2Zoom(characteristics, zoomRatio)
        Log.d(
            "KeplerPreview",
            "previewZoom cameraId=$cameraId requested=$zoomRatio " +
                "mode=${if (zoomApplication.usedControlZoomRatio) "CONTROL_ZOOM_RATIO" else "SCALER_CROP_REGION"} " +
                "applied=${zoomApplication.appliedZoomRatio} " +
                "range=${zoomApplication.zoomRatioRange} " +
                "cropFallback=${zoomApplication.cropRegion != null}"
        )

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
        val cropRegion = buildMeteringCropRegion(characteristics, zoomRatio)
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
        val cropRegion = buildMeteringCropRegion(characteristics, zoomRatio)
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

    private fun buildMeteringCropRegion(
        characteristics: CameraCharacteristics,
        zoomRatio: Float
    ): Rect {
        val usesControlZoomRatio =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null
        if (usesControlZoomRatio) {
            return characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?: Rect(0, 0, 1, 1)
        }
        return buildCenterCropRegion(characteristics, zoomRatio)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect(0, 0, 1, 1)
    }

    private fun isActive(localGeneration: Int): Boolean = synchronized(lock) {
        isActiveLocked(localGeneration)
    }

    private fun isActiveLocked(localGeneration: Int): Boolean {
        return started && generation == localGeneration
    }

    private fun storeCameraCharacteristics(
        localGeneration: Int,
        characteristics: CameraCharacteristics
    ): Boolean = synchronized(lock) {
        if (!isActiveLocked(localGeneration)) return false
        cameraCharacteristics = characteristics
        true
    }

    private fun storeCurrentPreviewSize(
        localGeneration: Int,
        previewSize: Size
    ): Boolean = synchronized(lock) {
        if (!isActiveLocked(localGeneration)) return false
        currentPreviewSize = previewSize
        true
    }

    private fun storePreviewSurface(
        localGeneration: Int,
        surface: Surface
    ): Boolean = synchronized(lock) {
        if (!isActiveLocked(localGeneration)) return false
        previewSurface = surface
        true
    }

    private fun storeCameraDevice(
        localGeneration: Int,
        camera: CameraDevice
    ): Boolean = synchronized(lock) {
        if (!isActiveLocked(localGeneration)) return false
        cameraDevice = camera
        true
    }

    private fun storeCaptureSession(
        localGeneration: Int,
        session: CameraCaptureSession
    ): Boolean = synchronized(lock) {
        if (!isActiveLocked(localGeneration)) return false
        captureSession = session
        true
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
                "displayDegrees=$displayDegrees swapDimensions=$swapDimensions " +
                "scaleX=$scaleX scaleY=$scaleY finalScale=$finalScale"
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
