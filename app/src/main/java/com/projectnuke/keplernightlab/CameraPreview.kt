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
import android.os.Looper
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

enum class PreviewAvailability { AVAILABLE, PERMISSION_REQUIRED, DISABLED, FAILED }

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
    onAeCapabilitiesChanged: (minIndex: Int, maxIndex: Int, stepEv: Float) -> Unit = { _, _, _ -> },
    onPreviewAvailabilityChanged: (PreviewAvailability) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    val latestOnAeCapabilitiesChanged = rememberUpdatedState(onAeCapabilitiesChanged)
    val latestOnPreviewAvailabilityChanged = rememberUpdatedState(onPreviewAvailabilityChanged)
    var lifecycleStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    val controller = remember(cameraId, physicalCameraId) {
        CameraPreviewController(
            context = context.applicationContext,
            cameraId = cameraId,
            physicalCameraId = physicalCameraId,
            actualLensSource = actualLensSource,
            onAeCapabilitiesChangedProvider = { latestOnAeCapabilitiesChanged.value },
            onPreviewAvailabilityChangedProvider = { latestOnPreviewAvailabilityChanged.value }
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

    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            lifecycleStarted = event == Lifecycle.Event.ON_START ||
                event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(enabled, lifecycleStarted) {
        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        latestOnPreviewAvailabilityChanged.value(
            when {
                !permissionGranted -> PreviewAvailability.PERMISSION_REQUIRED
                !enabled || !lifecycleStarted -> PreviewAvailability.DISABLED
                controller.isFailed() -> PreviewAvailability.FAILED
                else -> PreviewAvailability.AVAILABLE
            }
        )
    }

    DisposableEffect(controller) {
        onDispose { controller.dispose() }
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
        lifecycleStarted,
        cameraId,
        controller
    ) {
        val view = textureView

        if (enabled && lifecycleStarted && view != null) {
            controller.start(view)
        } else {
            controller.stop()
        }

        onDispose {
            controller.stop()
        }
    }
}

internal class CameraPreviewController(
    private val context: Context?,
    private val cameraId: String,
    private val physicalCameraId: String?,
    private val actualLensSource: ActualLensSource,
    private val onAeCapabilitiesChangedProvider: () -> (minIndex: Int, maxIndex: Int, stepEv: Float) -> Unit,
    private val onPreviewAvailabilityChangedProvider: () -> (PreviewAvailability) -> Unit,
    private val mainDispatch: (Runnable) -> Boolean = { runnable ->
        Handler(Looper.getMainLooper()).post(runnable)
    },
    private val emergencyReleaseExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "KeplerPreviewEmergencyRelease").apply { isDaemon = true }
    }
) {
    private val lock = Any()
    private val generationOwner = PreviewGenerationOwner()
    private val cameraDeviceSlot = PreviewResourceSlot<CameraDevice>()
    private val captureSessionSlot = PreviewResourceSlot<CameraCaptureSession>()
    private val previewSurfaceSlot = PreviewResourceSlot<Surface>()
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
    private var openRequestedGeneration: Int? = null
    private var lastTextureView: TextureView? = null
    private var cleanupDiagnostics = PreviewCleanupDiagnostics()
    private val cleanupAccumulator = PreviewCleanupAccumulator()
    private var commandSnapshot = PreviewCommandSnapshot()
    private var previewFailed = false
    private var disposed = false
    private var emergencyExecutorShutdown = false

    fun start(textureView: TextureView) {
        synchronized(lock) {
            if (disposed) return
            lastTextureView = textureView
        }
        val appContext = context ?: return
        if (
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val localGeneration = synchronized(lock) {
            val state = generationOwner.snapshot()
            if (state.state == PreviewGenerationOwner.State.STARTING || state.state == PreviewGenerationOwner.State.STOPPING) {
                generationOwner.start()
                return
            }
            if (state.state == PreviewGenerationOwner.State.OPEN && backgroundHandler != null && previewSurface != null) return
            val requestedGeneration = generationOwner.start() ?: return
            openRequestedGeneration = null
            requestedGeneration.toInt()
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
        stopInternal()
    }

    fun dispose() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
        }
        stopInternal()
    }

    private fun stopInternal() {
        val refs = synchronized(lock) {
            val ownedGeneration = generationOwner.snapshot().generation
            val stopGeneration = generationOwner.stop()
            if (stopGeneration == null) {
                if (disposed && generationOwner.snapshot().state == PreviewGenerationOwner.State.STOPPED) {
                    shutdownEmergencyExecutorIfNeededLocked()
                }
                return
            }
            openRequestedGeneration = null
            StopRefs(
                cleanupGeneration = ownedGeneration.toInt(),
                stopGeneration = stopGeneration.toInt(),
                session = captureSession,
                device = cameraDevice,
                surface = previewSurface,
                thread = backgroundThread,
                handler = backgroundHandler
            )
                .also {
                    captureSession = null
                    cameraDevice = null
                    cameraCharacteristics = null
                    previewSurface = null
                    backgroundThread = null
                    backgroundHandler = null
                    currentPreviewSize = null
                    captureSessionSlot.clear(ownedGeneration)
                    cameraDeviceSlot.clear(ownedGeneration)
                    previewSurfaceSlot.clear(ownedGeneration)
                }
        }
        fun markStoppedWhenTerminated() {
            val thread = refs.thread
            if (thread == null) {
                finishStop(refs.stopGeneration)
                return
            }
            val quit = settleAndRecord(
                generation = refs.cleanupGeneration.toLong(),
                operations = listOf(PreviewResourceOperation.HANDLER_THREAD_QUIT to { thread.quitSafely() })
            )
            quit.failures.forEach { record ->
                Log.e(TAG, "preview cleanup failed generation=${record.generation} operation=${record.operation}", record.failure)
            }
            try {
                emergencyReleaseExecutor.execute {
                    val termination = settleAndRecord(
                        generation = refs.cleanupGeneration.toLong(),
                        operations = listOf(PreviewResourceOperation.HANDLER_THREAD_TERMINATION to {
                            thread.join(2_000L)
                            if (thread.isAlive) error("THREAD_TERMINATION_TIMEOUT")
                        })
                    )
                    termination.failures.forEach { record ->
                        Log.e(TAG, "preview cleanup failed generation=${record.generation} operation=${record.operation}", record.failure)
                    }
                    finishStop(refs.stopGeneration)
                }
            } catch (failure: Throwable) {
                    recordCleanupDispatchFailure(failure)
                    Log.e(TAG, "preview emergency release dispatch rejected", failure)
                finishStop(refs.stopGeneration)
            }
        }
        val closeResources = Runnable {
            val cleanup = settleAndRecord(
                generation = refs.cleanupGeneration.toLong(),
                operations = listOf(
                    PreviewResourceOperation.STOP_REPEATING to { refs.session?.stopRepeating() },
                    PreviewResourceOperation.CAPTURE_SESSION_CLOSE to { refs.session?.close() },
                    PreviewResourceOperation.CAMERA_DEVICE_CLOSE to { refs.device?.close() },
                    PreviewResourceOperation.SURFACE_RELEASE to { refs.surface?.release() }
                )
            )
            cleanup.failures.forEach { record ->
                Log.e(TAG, "preview cleanup failed generation=${record.generation} operation=${record.operation}", record.failure)
            }
            markStoppedWhenTerminated()
        }
        if (refs.handler != null) {
            val posted = refs.handler.post(closeResources)
            if (!posted) {
                recordCleanupDispatchFailure(IllegalStateException("preview handler rejected cleanup dispatch"))
                try {
                    emergencyReleaseExecutor.execute(closeResources)
                } catch (failure: Throwable) {
                    recordCleanupDispatchFailure(failure)
                    Log.e(TAG, "preview cleanup dispatch rejected", failure)
                }
            }
        } else {
            try {
                emergencyReleaseExecutor.execute(closeResources)
            } catch (failure: Throwable) {
                recordCleanupDispatchFailure(failure)
                Log.e(TAG, "preview cleanup dispatch rejected", failure)
            }
        }
    }

    private fun finishStop(stopGeneration: Int) {
        val restart = synchronized(lock) {
            val finished = generationOwner.finishStop(stopGeneration = stopGeneration.toLong())
            val shouldRestart = finished && generationOwner.snapshot().desiredRunning && !disposed
            shouldRestart to lastTextureView
        }
        if (restart.first && restart.second != null) start(restart.second!!)
        synchronized(lock) {
            if (finishedStopCanShutdownLocked(stopGeneration)) {
                shutdownEmergencyExecutorIfNeededLocked()
            }
        }
    }

    private fun finishedStopCanShutdownLocked(stopGeneration: Int): Boolean =
        disposed && generationOwner.snapshot().generation == stopGeneration.toLong() &&
            generationOwner.snapshot().state == PreviewGenerationOwner.State.STOPPED

    private fun shutdownEmergencyExecutorIfNeededLocked() {
        if (emergencyExecutorShutdown) return
        emergencyExecutorShutdown = true
        emergencyReleaseExecutor.shutdown()
    }

    private fun settleLateResource(
        localGeneration: Int,
        operation: PreviewResourceOperation,
        release: () -> Unit
    ) {
        settleAndRecord(
            generation = localGeneration.toLong(),
            operations = listOf(operation to release),
            late = true
        ).failures.forEach { record ->
            Log.e(TAG, "late preview resource settlement failed generation=${record.generation} operation=${record.operation}", record.failure)
        }
    }

    private fun settleAndRecord(
        generation: Long,
        operations: List<Pair<PreviewResourceOperation, () -> Unit>>,
        late: Boolean = false
    ): PreviewCleanupSnapshot {
        val settlement = settlePreviewResources(generation, operations)
        synchronized(lock) {
            val snapshot = cleanupAccumulator.record(settlement, late)
            val lateSnapshots = if (late) {
                (cleanupDiagnostics.lateResourceSettlements + settlement).takeLast(8)
            } else {
                cleanupDiagnostics.lateResourceSettlements
            }
            val termination = snapshot.records.firstOrNull {
                it.operation == PreviewResourceOperation.HANDLER_THREAD_TERMINATION
            } ?: cleanupDiagnostics.threadTerminationOutcome
            cleanupDiagnostics = cleanupDiagnostics.copy(
                lastCleanupSnapshot = if (late) cleanupDiagnostics.lastCleanupSnapshot else snapshot,
                lateResourceSettlements = lateSnapshots,
                threadTerminationOutcome = termination
            )
            return snapshot
        }
    }

    private fun recordCleanupDispatchFailure(failure: Throwable) {
        synchronized(lock) {
            cleanupDiagnostics = cleanupDiagnostics.copy(cleanupDispatchFailure = failure)
        }
    }

    internal fun cleanupDiagnostics(): PreviewCleanupDiagnostics = synchronized(lock) { cleanupDiagnostics }

    fun updateFocusAeState(newState: FocusAeState) {
        if (isDisposed()) {
            recordCommandOutcome(currentCommandGeneration(), PreviewCommandApplyOutcome.DISPATCH_REJECTED)
            return
        }
        val previous = latestFocusAeState
        latestFocusAeState = newState
        val localGeneration = synchronized(lock) { generationOwner.snapshot().generation.toInt() }
        synchronized(lock) {
            commandSnapshot = commandSnapshot.copy(
                generation = localGeneration,
                requestedFocusAeState = newState
            )
        }
        dispatchPreviewCommand(localGeneration, PreviewCommandKind.FOCUS_AE) {
            applyFocusAeStateOnCameraThread(
                newState = newState,
                previousState = previous,
                localGeneration = localGeneration
            )
        }
    }

    fun updateMeteringMode(newMode: MeteringMode) {
        if (isDisposed()) {
            recordCommandOutcome(currentCommandGeneration(), PreviewCommandApplyOutcome.DISPATCH_REJECTED)
            return
        }
        val previous = latestMeteringMode
        latestMeteringMode = newMode
        val localGeneration = synchronized(lock) { generationOwner.snapshot().generation.toInt() }
        synchronized(lock) {
            commandSnapshot = commandSnapshot.copy(
                generation = localGeneration,
                requestedMeteringMode = newMode
            )
        }
        dispatchPreviewCommand(localGeneration, PreviewCommandKind.METERING) {
            val applied = applyFocusAeStateOnCameraThread(
                newState = latestFocusAeState,
                previousState = latestFocusAeState,
                localGeneration = localGeneration
            )
            if (previous != newMode) {
                Log.d(TAG, "metering changed mode=$newMode")
            }
            applied
        }
    }

    fun updateZoomRatio(newZoomRatio: Float) {
        if (isDisposed()) {
            recordCommandOutcome(currentCommandGeneration(), PreviewCommandApplyOutcome.DISPATCH_REJECTED)
            return
        }
        val previous = latestZoomRatio
        latestZoomRatio = (if (newZoomRatio.isFinite()) newZoomRatio else 1.0f).coerceAtLeast(0.1f)
        val localGeneration = synchronized(lock) { generationOwner.snapshot().generation.toInt() }
        synchronized(lock) {
            commandSnapshot = commandSnapshot.copy(
                generation = localGeneration,
                requestedZoomRatio = latestZoomRatio
            )
        }
        dispatchPreviewCommand(localGeneration, PreviewCommandKind.ZOOM) {
            val applied = applyFocusAeStateOnCameraThread(
                newState = latestFocusAeState,
                previousState = latestFocusAeState,
                localGeneration = localGeneration
            )
            if (abs(previous - latestZoomRatio) >= 0.02f) {
                Log.d(TAG, "zoom changed ratio=$latestZoomRatio")
            }
            applied
        }
    }

    private fun dispatchPreviewCommand(
        localGeneration: Int,
        kind: PreviewCommandKind,
        command: () -> Boolean
    ) {
        val handler = synchronized(lock) {
            if (!acceptsPreviewCommand(localGeneration, isActiveLocked(localGeneration), PreviewCommand(localGeneration, kind))) {
                null
            } else {
                backgroundHandler
            }
        }
        if (handler == null) {
            recordCommandOutcome(localGeneration, PreviewCommandApplyOutcome.DISPATCH_REJECTED)
            return
        }
        val outcome = try {
            if (handler.post {
                    if (acceptsPreviewCommand(localGeneration, isActive(localGeneration), PreviewCommand(localGeneration, kind))) {
                        if (command()) {
                            recordCommandApplied(localGeneration, kind)
                        } else {
                            recordCommandOutcome(localGeneration, PreviewCommandApplyOutcome.CAMERA_REQUEST_FAILED)
                        }
                    } else {
                        recordCommandOutcome(localGeneration, PreviewCommandApplyOutcome.STALE_GENERATION)
                    }
                }
            ) CameraUiDispatchOutcome.ACCEPTED else CameraUiDispatchOutcome.REJECTED
        } catch (failure: Throwable) {
            Log.e(TAG, "preview command dispatch threw kind=$kind generation=$localGeneration", failure)
            recordCommandOutcome(localGeneration, PreviewCommandApplyOutcome.DISPATCH_THROWN)
            CameraUiDispatchOutcome.DISPATCH_THREW
        }
        if (outcome != CameraUiDispatchOutcome.ACCEPTED) {
            if (outcome == CameraUiDispatchOutcome.REJECTED) {
                recordCommandOutcome(localGeneration, PreviewCommandApplyOutcome.DISPATCH_REJECTED)
            }
            Log.w(TAG, "preview command dispatch outcome=$outcome kind=$kind generation=$localGeneration")
        }
    }

    private fun recordCommandOutcome(localGeneration: Int, outcome: PreviewCommandApplyOutcome) {
        synchronized(lock) {
            commandSnapshot = commandSnapshot.withGenerationOutcome(localGeneration, outcome)
        }
    }

    private fun recordCommandApplied(localGeneration: Int, kind: PreviewCommandKind) {
        synchronized(lock) {
            if (localGeneration < commandSnapshot.generation) return
            commandSnapshot = when (kind) {
                PreviewCommandKind.ZOOM -> commandSnapshot.copy(
                    generation = localGeneration,
                    appliedZoomRatio = commandSnapshot.requestedZoomRatio,
                    lastOutcome = PreviewCommandApplyOutcome.APPLIED
                )
                PreviewCommandKind.FOCUS_AE -> commandSnapshot.copy(
                    generation = localGeneration,
                    appliedFocusAeState = commandSnapshot.requestedFocusAeState,
                    lastOutcome = PreviewCommandApplyOutcome.APPLIED
                )
                PreviewCommandKind.METERING -> commandSnapshot.copy(
                    generation = localGeneration,
                    appliedMeteringMode = commandSnapshot.requestedMeteringMode,
                    lastOutcome = PreviewCommandApplyOutcome.APPLIED
                )
            }
        }
    }

    internal fun commandSnapshot(): PreviewCommandSnapshot = synchronized(lock) { commandSnapshot }

    internal fun isFailed(): Boolean = synchronized(lock) { previewFailed }

    private fun isDisposed(): Boolean = synchronized(lock) { disposed }

    private fun currentCommandGeneration(): Int = synchronized(lock) {
        generationOwner.snapshot().generation.toInt()
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
        synchronized(lock) {
            if (!isActiveLocked(localGeneration) || openRequestedGeneration == localGeneration) return
            openRequestedGeneration = localGeneration
        }

        val cameraManager = (context ?: return).getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val thread = try {
            HandlerThread("KeplerPreviewThread").also { it.start() }
        } catch (failure: Throwable) {
            Log.e(TAG, "preview thread start failed generation=$localGeneration", failure)
            previewThreadStartFailed(localGeneration, failure)
            return
        }
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
            val minIndex = aeRange?.lower ?: 0
            val maxIndex = aeRange?.upper ?: 0
            val stepEv = aeStep?.toFloat() ?: 0f
            val posted = try {
                Handler(android.os.Looper.getMainLooper()).post {
                    if (isActive(localGeneration)) {
                        onAeCapabilitiesChangedProvider().invoke(minIndex, maxIndex, stepEv)
                    }
                }
            } catch (failure: Throwable) {
                Log.e(TAG, "AE capability dispatch threw generation=$localGeneration", failure)
                false
            }
            if (!posted) {
                Log.w(TAG, "AE capability dispatch rejected generation=$localGeneration")
            }
            val previewSize = choosePreviewSize(characteristics)
            if (!storeCurrentPreviewSize(localGeneration, previewSize)) {
                thread.quitSafely()
                return
            }

            val surfaceTexture = textureView.surfaceTexture
            if (surfaceTexture == null) {
                failPreview(localGeneration, IllegalStateException("preview surface texture unavailable"))
                return
            }

            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            configureTransform(textureView, previewSize)

            val surface = Surface(surfaceTexture)
            if (!isActive(localGeneration) || !textureView.isAvailable) {
                settleLateResource(localGeneration, PreviewResourceOperation.SURFACE_RELEASE) { surface.release() }
                return
            }
            when (storePreviewSurface(localGeneration, surface)) {
                PreviewResourceAttachment.ACCEPTED,
                PreviewResourceAttachment.ALREADY_OWNED -> Unit
                PreviewResourceAttachment.SETTLED_DUPLICATE,
                PreviewResourceAttachment.SETTLED_STALE -> {
                    Log.w(TAG, "supplied preview surface was not adopted generation=$localGeneration")
                }
            }
            if (previewSurfaceSlot.peek(localGeneration.toLong()) !== surface) {
                return
            }

            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (!isActive(localGeneration) || !textureView.isAvailable) {
                            Log.w(TAG, "stale onOpened ignored generation=$localGeneration")
                            settleLateResource(localGeneration, PreviewResourceOperation.CAMERA_DEVICE_CLOSE) { camera.close() }
                            return
                        }
                        when (storeCameraDevice(localGeneration, camera)) {
                            PreviewResourceAttachment.ACCEPTED -> Unit
                            PreviewResourceAttachment.ALREADY_OWNED -> return
                            PreviewResourceAttachment.SETTLED_DUPLICATE,
                            PreviewResourceAttachment.SETTLED_STALE -> {
                            Log.w(TAG, "stale onOpened dropped generation=$localGeneration")
                            return
                            }
                        }
                        createPreviewSession(camera, surface, characteristics, localGeneration, textureView)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        if (detachOwnedCamera(localGeneration, camera)) {
                            settleAndRecord(
                                localGeneration.toLong(),
                                listOf(PreviewResourceOperation.CAMERA_DEVICE_CLOSE to { camera.close() })
                            )
                        } else {
                            settleLateResource(localGeneration, PreviewResourceOperation.CAMERA_DEVICE_CLOSE) { camera.close() }
                        }
                        if (isActive(localGeneration)) failPreview(localGeneration, IllegalStateException("camera disconnected"))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.w(TAG, "camera error=$error generation=$localGeneration")
                        if (detachOwnedCamera(localGeneration, camera)) {
                            settleAndRecord(
                                localGeneration.toLong(),
                                listOf(PreviewResourceOperation.CAMERA_DEVICE_CLOSE to { camera.close() })
                            )
                        } else {
                            settleLateResource(localGeneration, PreviewResourceOperation.CAMERA_DEVICE_CLOSE) { camera.close() }
                        }
                        if (isActive(localGeneration)) failPreview(localGeneration, IllegalStateException("camera error=$error"))
                    }
                },
                handler
            )
        } catch (failure: Exception) {
            failPreview(localGeneration, failure)
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
            settleLateResource(localGeneration, PreviewResourceOperation.CAMERA_DEVICE_CLOSE) { camera.close() }
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
        val handler = backgroundHandler
        if (handler == null) {
            failPreview(localGeneration, IllegalStateException("preview callback handler unavailable"))
            return
        }
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
                    PreviewCameraCallbackExecutor(
                        dispatch = { command -> handler.post(command) },
                        onDispatchFailure = { failure ->
                            callbackDispatchFailed(localGeneration, failure)
                        }
                    ),
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

    @Suppress("DEPRECATION")
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
                        if (isActive(localGeneration)) normalPreviewFailed(localGeneration)
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
            normalPreviewFailed(localGeneration, error)
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
                    settleLateResource(localGeneration, PreviewResourceOperation.CAPTURE_SESSION_CLOSE) { session.close() }
                    return
                }
                when (storeCaptureSession(localGeneration, session)) {
                    PreviewResourceAttachment.ACCEPTED -> Unit
                    PreviewResourceAttachment.ALREADY_OWNED -> return
                    PreviewResourceAttachment.SETTLED_DUPLICATE,
                    PreviewResourceAttachment.SETTLED_STALE -> {
                    Log.w(TAG, "stale onConfigured dropped generation=$localGeneration")
                    return
                    }
                }
                Log.i(
                    "KeplerPhysicalRoute",
                    "session configured mode=$sessionMode cameraId=$cameraId " +
                        "requestedPhysicalCameraId=$physicalCameraId previewZoomRatio=$latestZoomRatio"
                )

                try {
                    val applied = applyFocusAeStateOnCameraThread(
                        newState = latestFocusAeState,
                        previousState = FocusAeState(),
                        localGeneration = localGeneration,
                        forceTrigger = latestFocusAeState.point != null
                    )
                    check(applied) { "initial preview request was not applied" }
                    if (!markPreviewOpen(localGeneration)) {
                        Log.w(TAG, "preview session request completed for stale generation=$localGeneration")
                    }
                } catch (error: Exception) {
                    Log.e(
                        "KeplerPhysicalRoute",
                        "sessionMode=$sessionMode request failed " +
                            "exception=${error.javaClass.simpleName}:${error.message}",
                        error
                    )
                    if (detachOwnedSession(localGeneration, session)) {
                        settleAndRecord(
                            localGeneration.toLong(),
                            listOf(PreviewResourceOperation.CAPTURE_SESSION_CLOSE to { session.close() })
                        )
                    } else {
                        settleLateResource(localGeneration, PreviewResourceOperation.CAPTURE_SESSION_CLOSE) { session.close() }
                    }
                    onFailure()
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(
                    "KeplerPhysicalRoute",
                    "session configuration failed mode=$sessionMode cameraId=$cameraId " +
                        "requestedPhysicalCameraId=$physicalCameraId"
                )
                settleLateResource(localGeneration, PreviewResourceOperation.CAPTURE_SESSION_CLOSE) { session.close() }
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
    ): Boolean {
        if (!isActive(localGeneration)) return false
        val session = captureSession ?: return false
        val camera = cameraDevice ?: return false
        val surface = previewSurface ?: return false
        val characteristics = cameraCharacteristics ?: return false
        val handler = backgroundHandler ?: return false
        val zoom = latestZoomRatio
        val meteringMode = latestMeteringMode
        val pointChanged = previousState.point != newState.point
        val lockChanged = previousState.locked != newState.locked
        val evChanged = previousState.exposureCompensationIndex != newState.exposureCompensationIndex
        val aeRegions = buildAeMeteringRegions(characteristics, zoom, meteringMode, newState.point)
        val cropRegion = buildMeteringCropRegion(characteristics, zoom)

        return try {
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
            true
        } catch (failure: Throwable) {
            Log.w(TAG, "applyFocusAeState failed", failure)
            false
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

    internal fun callbackDispatchFailed(localGeneration: Int, failure: Throwable) {
        synchronized(lock) {
            cleanupDiagnostics = cleanupDiagnostics.copy(callbackDispatchFailure = failure)
        }
        failPreview(localGeneration, failure)
    }

    internal fun previewThreadStartFailed(localGeneration: Int, failure: Throwable) {
        failPreview(localGeneration, failure)
    }

    internal fun normalPreviewFailed(
        localGeneration: Int,
        failure: Throwable = IllegalStateException("normal preview session failed")
    ) {
        failPreview(localGeneration, failure)
    }

    private fun failPreview(localGeneration: Int, failure: Throwable) {
        val shouldStop = synchronized(lock) {
            if (disposed || generationOwner.snapshot().generation != localGeneration.toLong()) {
                false
            } else {
                previewFailed = true
                generationOwner.fail(localGeneration.toLong())
            }
        }
        if (!shouldStop) return
        try {
            if (!mainDispatch(Runnable { onPreviewAvailabilityChangedProvider().invoke(PreviewAvailability.FAILED) })) {
                synchronized(lock) {
                    cleanupDiagnostics = cleanupDiagnostics.copy(callbackDispatchFailure =
                        IllegalStateException("preview availability dispatch rejected"))
                }
            }
        } catch (dispatchFailure: Throwable) {
            synchronized(lock) {
                cleanupDiagnostics = cleanupDiagnostics.copy(callbackDispatchFailure = dispatchFailure)
            }
        }
        stopInternal()
    }

    private fun markPreviewOpen(localGeneration: Int): Boolean {
        val opened = synchronized(lock) {
            if (!generationOwner.markOpen(localGeneration.toLong())) return@synchronized false
            previewFailed = false
            true
        }
        if (opened) {
            try {
                mainDispatch(Runnable {
                    onPreviewAvailabilityChangedProvider().invoke(PreviewAvailability.AVAILABLE)
                })
            } catch (failure: Throwable) {
                synchronized(lock) {
                    cleanupDiagnostics = cleanupDiagnostics.copy(callbackDispatchFailure = failure)
                }
            }
        }
        return opened
    }

    internal fun beginGenerationForTest(): Int = synchronized(lock) {
        generationOwner.start()?.toInt() ?: error("preview generation did not start")
    }

    internal fun markOpenForTest(localGeneration: Int): Boolean = markPreviewOpen(localGeneration)

    private fun isActive(localGeneration: Int): Boolean = synchronized(lock) {
        isActiveLocked(localGeneration)
    }

    private fun isActiveLocked(localGeneration: Int): Boolean {
        return generationOwner.accepts(localGeneration.toLong())
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
    ): PreviewResourceAttachment = synchronized(lock) {
        val attachment = previewSurfaceSlot.attach(
            expectedGeneration = localGeneration.toLong(),
            currentGeneration = generationOwner.snapshot().generation,
            supplied = surface
        ) { supplied -> settleLateResource(localGeneration, PreviewResourceOperation.SURFACE_RELEASE) { supplied.release() } }
        if (attachment == PreviewResourceAttachment.ACCEPTED) previewSurface = surface
        attachment
    }

    private fun detachOwnedCamera(localGeneration: Int, camera: CameraDevice): Boolean = synchronized(lock) {
        val detached = cameraDeviceSlot.detach(localGeneration.toLong(), camera) ?: return false
        if (cameraDevice === detached) cameraDevice = null
        true
    }

    private fun detachOwnedSession(localGeneration: Int, session: CameraCaptureSession): Boolean = synchronized(lock) {
        val detached = captureSessionSlot.detach(localGeneration.toLong(), session) ?: return false
        if (captureSession === detached) captureSession = null
        true
    }

    private fun storeCameraDevice(
        localGeneration: Int,
        camera: CameraDevice
    ): PreviewResourceAttachment = synchronized(lock) {
        val attachment = cameraDeviceSlot.attach(
            expectedGeneration = localGeneration.toLong(),
            currentGeneration = generationOwner.snapshot().generation,
            supplied = camera
        ) { supplied -> settleLateResource(localGeneration, PreviewResourceOperation.CAMERA_DEVICE_CLOSE) { supplied.close() } }
        if (attachment == PreviewResourceAttachment.ACCEPTED) cameraDevice = camera
        attachment
    }

private fun storeCaptureSession(
        localGeneration: Int,
        session: CameraCaptureSession
    ): PreviewResourceAttachment = synchronized(lock) {
        val attachment = captureSessionSlot.attach(
            expectedGeneration = localGeneration.toLong(),
            currentGeneration = generationOwner.snapshot().generation,
            supplied = session
        ) { supplied -> settleLateResource(localGeneration, PreviewResourceOperation.CAPTURE_SESSION_CLOSE) { supplied.close() } }
        if (attachment == PreviewResourceAttachment.ACCEPTED) {
            captureSession = session
        }
        attachment
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
        val cleanupGeneration: Int,
        val stopGeneration: Int,
        val session: CameraCaptureSession?,
        val device: CameraDevice?,
        val surface: Surface?,
        val thread: HandlerThread?,
        val handler: Handler?
    )

    private companion object {
        private const val TAG = "KeplerCameraPreview"
    }
}
