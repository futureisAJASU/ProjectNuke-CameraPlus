package com.projectnuke.keplernightlab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class LatestKeplerResult(
    val bitmap: Bitmap?,
    val summary: String,
    val jobType: String = "",
    val fusionEngine: String = "",
    val usedFrames: Int = 0,
    val requestedFrames: Int = 0,
    val outputWidth: Int? = null,
    val outputHeight: Int? = null,
    val fileName: String = "",
    val jobName: String = "",
    val filePath: String = ""
)

private fun logLongReport(tag: String, report: String) {
    report.chunked(3000).forEachIndexed { index, chunk ->
        Log.i(tag, "part ${index + 1}:\n$chunk")
    }
}

fun lensSlotForZoomRatio(zoomRatio: Float): LensSlot {
    return when {
        zoomRatio < 0.85f -> LensSlot.ULTRAWIDE
        zoomRatio < 1.5f -> LensSlot.MAIN_1X
        zoomRatio < 2.7f -> LensSlot.MAIN_2X
        else -> LensSlot.THREE_X
    }
}

internal val KeplerDarkScheme = darkColorScheme(
    background = Color.Black,
    surface = Color(0xFF0B0C10),
    primary = Color.White,
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

private const val SHOW_LEGACY_RAW_ACTIONS = false
private val ZoomSelectorHorizontalPadding: Dp = 7.dp
private val ZoomSelectorVerticalPadding: Dp = 4.dp
private val ZoomSelectorItemWidth: Dp = 44.dp
private val ZoomSelectorItemHeight: Dp = 30.dp
private val ZoomSelectorItemCornerRadius: Dp = 15.dp
private val CompactSliderHeight: Dp = 26.dp
private val CompactSliderTrackHeight: Dp = 4.dp
private val CompactSliderThumbSize: Dp = 12.dp
private val BottomOverlayHorizontalPadding: Dp = 16.dp
private val BottomOverlayBottomPadding: Dp = 8.dp
private val BottomOverlaySpacing: Dp = 4.dp
private val FloatingClusterHorizontalPadding: Dp = 10.dp
private val FloatingClusterVerticalPadding: Dp = 8.dp
private const val ZoomSliderAutoHideDelayMillis = 2500L

private fun mapSliderPositionToValue(
    x: Float,
    width: Float,
    valueRange: ClosedFloatingPointRange<Float>
): Float {
    if (width <= 0f) return valueRange.start
    val range = max(valueRange.endInclusive - valueRange.start, 0.0001f)
    val fraction = (x / width).coerceIn(0f, 1f)
    return valueRange.start + (range * fraction)
}

private enum class MainScreen {
    CAMERA,
    SETTINGS
}

private val progressPairRegex = Regex("(saved|images|results)\\s+(\\d+)\\s*/\\s*(\\d+)", RegexOption.IGNORE_CASE)
private val usedFramesRegex = Regex("Used\\s+(\\d+)\\s*/\\s*(\\d+)\\s+frames", RegexOption.IGNORE_CASE)
private val yuvSavedRegex = Regex("YUV capture:\\s*saved\\s+(\\d+)\\s*/\\s*(\\d+)", RegexOption.IGNORE_CASE)
private val colorSavedRegex = Regex("Color frame saved\\s+(\\d+)\\s*/\\s*(\\d+)", RegexOption.IGNORE_CASE)

internal fun isCommittedPipelineCompletionStatus(status: String): Boolean =
    status.trimStart().startsWith("PIPELINE_COMPLETE", ignoreCase = true)

internal fun shouldIgnoreCancelledPipelineStatus(
    cancelled: Boolean,
    timedOutGeneration: Int,
    localGeneration: Int,
    pipelineGeneration: Int,
    status: String
): Boolean {
    if (!cancelled) return false
    val isLateCommittedCompletion =
        timedOutGeneration == localGeneration &&
            localGeneration == pipelineGeneration &&
            isCommittedPipelineCompletionStatus(status)
    return !isLateCommittedCompletion
}

fun parseCaptureProgress(
    text: String,
    fallback: CaptureProgressState
): CaptureProgressState {
    val lower = text.lowercase()
    val normalized = text.trimStart()
    val stage = when {
        normalized.startsWith("CAPTURE_TIMEOUT", ignoreCase = true) ||
                normalized.startsWith("PROCESS_TIMEOUT", ignoreCase = true) ||
                normalized.startsWith("EXPORT_TIMEOUT", ignoreCase = true) -> CaptureStage.TIMEOUT
        normalized.startsWith("PIPELINE_FAILED", ignoreCase = true) ||
                normalized.startsWith("CAPTURE_FAILED", ignoreCase = true) ||
                normalized.startsWith("PROCESS_FAILED", ignoreCase = true) ||
                normalized.startsWith("EXPORT_FAILED", ignoreCase = true) -> CaptureStage.FAILED
        normalized.startsWith("PIPELINE_CANCELLED", ignoreCase = true) -> CaptureStage.CANCELLED
        normalized.startsWith("PIPELINE_COMPLETE", ignoreCase = true) ||
                normalized.startsWith("EXPORT_COMPLETE", ignoreCase = true) -> CaptureStage.COMPLETE
        lower.contains("cleanup") || lower.contains("cleaning") -> CaptureStage.CLEANING
        lower.contains("verifying") || lower.contains("verification") -> CaptureStage.VERIFYING
        text.contains("결과 미리보기") ||
                lower.contains("exporting") || lower.contains("export ") || lower.contains("tone/export") -> CaptureStage.EXPORTING
        lower.contains("demosaic") || text.contains("렌더링") -> CaptureStage.DEMOSAICING
        normalized.startsWith("CAPTURE_COMPLETE", ignoreCase = true) ||
                normalized.startsWith("CAPTURE_COMPLETE_PARTIAL", ignoreCase = true) ||
                text.contains("Classic RAW fusion:", ignoreCase = true) ||
                text.contains("Classic YUV fusion:", ignoreCase = true) ||
                text.contains("처리하는 중") ||
                text.contains("정렬하는 중") ||
                text.contains("병합하는 중") ||
                text.contains("Processing RAW fusion:", ignoreCase = true) ||
                lower.contains("processing") || lower.contains("merging") || lower.contains("loading frames") -> CaptureStage.PROCESSING
        lower.contains("capture") || lower.contains("capturing") || lower.contains("frame saved") || text.contains("캡처 중") -> CaptureStage.CAPTURING
        lower.contains("prepar") || text.contains("준비") || text.contains("초기화") -> CaptureStage.PREPARING
        else -> fallback.stage
    }

    var requested = fallback.requestedFrames
    var saved = fallback.savedFrames
    var images = fallback.receivedImages
    var results = fallback.completedResults

    progressPairRegex.findAll(text).forEach { match ->
        val value = match.groupValues[2].toIntOrNull() ?: return@forEach
        val total = match.groupValues[3].toIntOrNull() ?: return@forEach
        requested = total
        when (match.groupValues[1].lowercase()) {
            "saved" -> saved = value
            "images" -> images = value
            "results" -> results = value
        }
    }

    listOf(yuvSavedRegex, colorSavedRegex).forEach { regex ->
        regex.find(text)?.let { match ->
            saved = match.groupValues[1].toIntOrNull() ?: saved
            requested = match.groupValues[2].toIntOrNull() ?: requested
        }
    }
    usedFramesRegex.find(text)?.let { match ->
        saved = match.groupValues[1].toIntOrNull() ?: saved
        requested = match.groupValues[2].toIntOrNull() ?: requested
    }

    val progress = when (stage) {
        CaptureStage.IDLE -> 0f
        CaptureStage.PREPARING -> 0.05f
        CaptureStage.CAPTURING -> if (requested > 0) saved.toFloat() / requested.toFloat() else fallback.progressPercent
        CaptureStage.PROCESSING -> 0.65f
        CaptureStage.DEMOSAICING -> 0.75f
        CaptureStage.EXPORTING -> 0.85f
        CaptureStage.VERIFYING -> 0.92f
        CaptureStage.CLEANING -> 0.97f
        CaptureStage.COMPLETE,
        CaptureStage.FAILED,
        CaptureStage.CANCELLED,
        CaptureStage.TIMEOUT -> 1f
    }.coerceIn(0f, 1f)

    return fallback.copy(
        stage = stage,
        message = text.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { fallback.message },
        requestedFrames = requested,
        savedFrames = saved,
        receivedImages = images,
        completedResults = results,
        progressPercent = progress
    )
}

@Composable
fun MainCameraScreen(
    onOpenDebug: () -> Unit,
    onOpenCacheJobs: () -> Unit,
    onOpenGallery: () -> Unit
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val savedSettings = remember { CameraSettingsStore.load(context) }

    var status by remember { mutableStateOf("대기 중") }
    var previewEnabled by remember { mutableStateOf(true) }
    var isCapturing by remember { mutableStateOf(false) }
    var isPipelineBusy by remember { mutableStateOf(false) }
    var currentScreen by remember { mutableStateOf(MainScreen.CAMERA) }
    var captureProgress by remember { mutableStateOf(CaptureProgressState()) }
    var pipelineGeneration by remember { mutableIntStateOf(0) }
    var timedOutGeneration by remember { mutableIntStateOf(-1) }
    val activeCancellationToken = remember { AtomicReference<KeplerPipelineCancellationToken?>(null) }
    val activeCaptureCancellation = remember { AtomicReference<KeplerCaptureCancellationHandle?>(null) }
    val activeWatchdog = remember { AtomicReference<Runnable?>(null) }
    val activeJobStart = remember { AtomicReference<Runnable?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            pipelineGeneration++
            activeCancellationToken.getAndSet(null)?.cancel()
            activeCaptureCancellation.getAndSet(null)?.cancelCapture("camera screen disposed")
            activeWatchdog.getAndSet(null)?.let(mainHandler::removeCallbacks)
            activeJobStart.getAndSet(null)?.let(mainHandler::removeCallbacks)
            timedOutGeneration = -1
            Log.i("KeplerPipelineState", "camera screen disposed; active pipeline cancelled")
        }
    }

    val selectedMode = "사진"
    var selectedResolution by remember {
        mutableStateOf(CaptureResolutionMode.entries.firstOrNull { it.name == savedSettings.selectedResolutionName } ?: CaptureResolutionMode.MP12)
    }
    var selectedLensSlot by remember {
        mutableStateOf(LensSlot.entries.firstOrNull { it.name == savedSettings.selectedLensSlotName } ?: LensSlot.MAIN_1X)
    }
    var selectedThreeXSource by remember {
        mutableStateOf(parseThreeXSourceModeOrDefault(savedSettings.selectedThreeXSourceName))
    }
    var zoomUiState by remember {
        mutableStateOf(
            ZoomUiState(
                zoomRatio = savedSettings.zoomRatio,
                lensSlot = LensSlot.entries.firstOrNull { it.name == savedSettings.selectedLensSlotName } ?: LensSlot.MAIN_1X,
                useOpticalTeleAt3x = parseThreeXSourceModeOrDefault(savedSettings.selectedThreeXSourceName) == ThreeXSourceMode.OPTICAL
            )
        )
    }
    var pipelineMode by remember {
        mutableStateOf(PipelineMode.entries.firstOrNull { it.name == savedSettings.pipelineModeName } ?: PipelineMode.YUV_NIGHT_FUSION)
    }
    var finalOutputFormat by remember { mutableStateOf(OutputSettingsStore.load(context)) }
    var focusAeState by remember { mutableStateOf(FocusAeState()) }
    var showFocusAeControls by remember { mutableStateOf(false) }
    var showZoomSlider by remember { mutableStateOf(false) }
    var focusAeUiNonce by remember { mutableStateOf(0) }
    var overlaySettings by remember {
        mutableStateOf(UiOverlaySettingsStore.load(context))
    }
    var frameCountMode by remember {
        mutableStateOf(FrameCountMode.entries.firstOrNull { it.name == savedSettings.frameCountModeName } ?: FrameCountMode.AUTO)
    }
    var autoMinFrames by remember { mutableStateOf(savedSettings.autoMinFrames) }
    var autoMaxFrames by remember { mutableStateOf(savedSettings.autoMaxFrames) }
    var manualFrames by remember { mutableStateOf(savedSettings.manualFrames) }
    var rawSpeedMode by remember {
        mutableStateOf(RawSpeedMode.entries.firstOrNull { it.name == savedSettings.rawSpeedModeName } ?: RawSpeedMode.BALANCED)
    }
    var latestSceneLuma by remember { mutableStateOf<Double?>(null) }
    var latestMotionScore by remember { mutableStateOf<Double?>(null) }
    var capabilityRefreshNonce by remember { mutableStateOf(0) }
    var latestFramePlan by remember {
        mutableStateOf(
            FramePlan(
                framesToCapture = 4,
                maxFrames = 8,
                reason = "Default"
            )
        )
    }

    val cameraState = rememberCameraSelectionState(
        context = context,
        selectedResolution = selectedResolution,
        selectedLensSlot = selectedLensSlot,
        selectedThreeXSource = selectedThreeXSource,
        zoomUiState = zoomUiState,
        capabilityRefreshNonce = capabilityRefreshNonce
    )
    val resolutionState = rememberResolutionState(
        context = context,
        cameraSelection = cameraState.selection,
        selectedResolution = selectedResolution,
        selectedLensSlot = selectedLensSlot,
        selectedThreeXSource = selectedThreeXSource,
        pipelineMode = pipelineMode,
        capabilityRefreshNonce = capabilityRefreshNonce
    )
    val levelState = rememberDeviceLevelState(enabled = overlaySettings.showLevel)

    LaunchedEffect(Unit) {
        logLongReport("KeplerCameraDump", buildFullCameraDumpReport(context))
    }

    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var latestSummary by remember { mutableStateOf("최근 결과 없음") }
    var latestResult by remember { mutableStateOf<LatestKeplerResult?>(null) }
    var showResultPreview by remember { mutableStateOf(false) }
    val cameraScope = rememberCoroutineScope()
    var refreshGeneration by remember { mutableIntStateOf(0) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }

    fun refreshLatestResult(showPreview: Boolean = false) {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        refreshJob = cameraScope.launch {
            val (result, estimate) = withContext(Dispatchers.IO + NonCancellable) {
                loadLatestKeplerResultV2(context) to estimateLatestColorBurstScene(context)
            }
            if (!currentCoroutineContext().isActive || generation != refreshGeneration) {
                result.bitmap?.takeIf { !it.isRecycled }?.recycle()
                return@launch
            }
            latestBitmap?.takeIf { it !== result.bitmap && !it.isRecycled }?.recycle()
            latestBitmap = result.bitmap
            latestSummary = result.summary
            latestResult = result
            if (showPreview && result.fileName.isNotBlank()) showResultPreview = true
            latestSceneLuma = estimate.meanLuma
            latestMotionScore = estimate.motionScore
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ++refreshGeneration
            refreshJob?.cancel()
            latestBitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    LaunchedEffect(Unit) {
        Log.d("KeplerSmoke", "CameraScreen mounted")
        refreshLatestResult()
    }

    LaunchedEffect(isPipelineBusy) {
        Log.i("KeplerPipelineState", if (isPipelineBusy) "busy indicator shown" else "busy indicator hidden")
    }

    LaunchedEffect(focusAeUiNonce, focusAeState.locked) {
        if (showFocusAeControls && !focusAeState.locked) {
            delay(2_000L)
            showFocusAeControls = false
        }
    }

    LaunchedEffect(showZoomSlider, zoomUiState.zoomRatio) {
        if (showZoomSlider) {
            delay(ZoomSliderAutoHideDelayMillis)
            showZoomSlider = false
        }
    }

    LaunchedEffect(selectedLensSlot, selectedThreeXSource, resolutionState.allowedModes) {
        if (selectedResolution !in resolutionState.allowedModes) {
            selectedResolution = CaptureResolutionMode.MP12
            status = "Resolution changed to 12M: selected lens does not support current mode."
        }
    }

    LaunchedEffect(
        selectedResolution,
        selectedLensSlot,
        selectedThreeXSource,
        pipelineMode,
        frameCountMode,
        autoMinFrames,
        autoMaxFrames,
        manualFrames,
        zoomUiState.zoomRatio,
        rawSpeedMode
    ) {
        CameraSettingsStore.save(
            context,
            CameraUiSettings(
                selectedResolutionName = selectedResolution.name,
                selectedLensSlotName = selectedLensSlot.name,
                selectedThreeXSourceName = selectedThreeXSource.name,
                pipelineModeName = pipelineMode.name,
                frameCountModeName = frameCountMode.name,
                autoMinFrames = autoMinFrames,
                autoMaxFrames = autoMaxFrames,
                manualFrames = manualFrames,
                zoomRatio = zoomUiState.zoomRatio,
                rawSpeedModeName = rawSpeedMode.name
            )
        )
    }

    LaunchedEffect(frameCountMode, autoMinFrames, autoMaxFrames, manualFrames, selectedMode, latestSceneLuma, latestMotionScore) {
        val settings = currentFrameCountSettings(
            mode = frameCountMode,
            autoMinFrames = autoMinFrames,
            autoMaxFrames = autoMaxFrames,
            manualFrames = manualFrames
        )
        latestFramePlan = estimateFramePlan(
            settings = settings,
            selectedModeLabel = selectedMode,
            latestSceneLuma = latestSceneLuma,
            latestMotionScore = latestMotionScore
        )
    }

    fun applyZoomRatio(newZoom: Float, explicitLensSlot: LensSlot? = null) {
        val clamped = newZoom.coerceIn(zoomUiState.minZoom, zoomUiState.maxZoom)
        val nextSlot = explicitLensSlot ?: lensSlotForZoomRatioHysteresis(
            zoomRatio = clamped,
            current = selectedLensSlot,
            useOpticalTeleAt3x = selectedThreeXSource == ThreeXSourceMode.OPTICAL
        )
        val enteringThreeX =
            nextSlot == LensSlot.THREE_X && selectedLensSlot != LensSlot.THREE_X
        val effectiveThreeXSource = if (enteringThreeX) {
            ThreeXSourceMode.OPTICAL
        } else {
            selectedThreeXSource
        }
        val displayZoom = (clamped * 10f).roundToInt() / 10f
        zoomUiState = zoomUiState.copy(
            zoomRatio = clamped,
            lensSlot = nextSlot,
            useOpticalTeleAt3x = effectiveThreeXSource == ThreeXSourceMode.OPTICAL
        )
        if (enteringThreeX) {
            selectedThreeXSource = ThreeXSourceMode.OPTICAL
            selectedResolution = CaptureResolutionMode.MP12
        }
        if (nextSlot != selectedLensSlot) {
            selectedLensSlot = nextSlot
        }
        status = "Zoom ${"%.1f".format(displayZoom)}x"
    }

    fun runCameraJob(
        startMessage: String,
        requestedFrames: Int = 0,
        timeoutMillis: Long = 120_000L,
        job: (KeplerPipelineCancellationToken, KeplerCaptureCancellationHandle, (String) -> Unit) -> Unit
    ) {
        if (isPipelineBusy) {
            status = "Pipeline busy: current fusion/export is still running."
            Log.i("KeplerPipelineState", "capture ignored: pipeline busy status=$status")
            return
        }
        val localGeneration = ++pipelineGeneration
        val cancellationToken = KeplerPipelineCancellationToken()
        val captureCancellationHandle = KeplerCaptureCancellationHandle()
        activeCancellationToken.set(cancellationToken)
        activeCaptureCancellation.set(captureCancellationHandle)
        status = startMessage
        isPipelineBusy = true
        isCapturing = true
        previewEnabled = false
        Log.i(
            "KeplerPipelineState",
            "pipeline start generation=$localGeneration message=$startMessage requestedFrames=$requestedFrames"
        )
        captureProgress = CaptureProgressState(
            stage = CaptureStage.PREPARING,
            message = "Preparing capture...",
            requestedFrames = requestedFrames,
            progressPercent = 0.05f
        )
        val watchdog = Runnable {
            if (localGeneration != pipelineGeneration) {
                Log.i(
                    "KeplerPipelineState",
                    "stale watchdog ignored generation=$localGeneration current=$pipelineGeneration"
                )
                return@Runnable
            }
            cancellationToken.cancel()
            captureCancellationHandle.cancelCapture("watchdog timeout")
            activeCancellationToken.compareAndSet(cancellationToken, null)
            activeCaptureCancellation.compareAndSet(captureCancellationHandle, null)
            activeWatchdog.set(null)
            timedOutGeneration = localGeneration
            val timeoutStatus = "CAPTURE_TIMEOUT: Capture timeout. Preview recovered."
            status = timeoutStatus
            captureProgress = parseCaptureProgress(timeoutStatus, captureProgress)
            isCapturing = false
            isPipelineBusy = false
            previewEnabled = true
            Log.i("KeplerPipelineState", "pipeline timeout; preview re-enabled")
        }
        activeWatchdog.set(watchdog)
        mainHandler.postDelayed(watchdog, timeoutMillis)

        fun finishIfTerminal(newStatus: String) {
            if (localGeneration != pipelineGeneration) {
                Log.i(
                    "KeplerPipelineState",
                    "stale terminal ignored generation=$localGeneration current=$pipelineGeneration status=$newStatus"
                )
                return
            }
            if (shouldIgnoreCancelledPipelineStatus(cancellationToken.isCancelled, timedOutGeneration, localGeneration, pipelineGeneration, newStatus)) {
                Log.i("KeplerPipelineState", "post-timeout status ignored generation=$localGeneration status=$newStatus")
                return
            }
            val acceptLateCompletion =
                cancellationToken.isCancelled &&
                    timedOutGeneration == localGeneration &&
                    localGeneration == pipelineGeneration &&
                    isCommittedPipelineCompletionStatus(newStatus)
            if (acceptLateCompletion) {
                timedOutGeneration = -1
            }
            captureProgress = parseCaptureProgress(newStatus, captureProgress)
            if (isCaptureStageCompleteButPipelineStillRunning(newStatus)) {
                isCapturing = false
                previewEnabled = false
                captureProgress = captureProgress.copy(
                    stage = CaptureStage.PROCESSING,
                    message = "캡처가 완료되었습니다.",
                    progressPercent = max(captureProgress.progressPercent, 0.65f)
                )
                Log.i("KeplerPipelineState", "Capture stage complete; waiting for processing/export")
                return
            }
            if (isTerminalStatus(newStatus)) {
                val terminalSuccess =
                    newStatus.trimStart().startsWith("PIPELINE_COMPLETE", ignoreCase = true) ||
                        newStatus.trimStart().startsWith("EXPORT_COMPLETE", ignoreCase = true)
                mainHandler.removeCallbacks(watchdog)
                activeWatchdog.compareAndSet(watchdog, null)
                activeCancellationToken.compareAndSet(cancellationToken, null)
                activeCaptureCancellation.compareAndSet(captureCancellationHandle, null)
                if (timedOutGeneration == localGeneration) timedOutGeneration = -1
                isPipelineBusy = false
                isCapturing = false
                refreshLatestResult(showPreview = terminalSuccess)
                Log.i("KeplerPipelineState", "pipeline final terminalSuccess=$terminalSuccess status=$newStatus")

                mainHandler.postDelayed(
                    {
                        if (localGeneration != pipelineGeneration) {
                            Log.i(
                                "KeplerPipelineState",
                                "stale preview enable ignored generation=$localGeneration current=$pipelineGeneration"
                            )
                            return@postDelayed
                        }
                        previewEnabled = true
                        Log.i("KeplerPipelineState", "preview re-enabled")
                    },
                    250L
                )
            }
        }

        val jobStart = Runnable {
                activeJobStart.set(null)
                if (localGeneration != pipelineGeneration || cancellationToken.isCancelled) {
                    Log.i(
                        "KeplerPipelineState",
                        "stale job start ignored generation=$localGeneration current=$pipelineGeneration"
                    )
                    return@Runnable
                }
                try {
                    job(cancellationToken, captureCancellationHandle, jobCallback@{ newStatus ->
                        if (localGeneration != pipelineGeneration) {
                            Log.i(
                                "KeplerPipelineState",
                                "stale pipeline status ignored generation=$localGeneration current=$pipelineGeneration status=$newStatus"
                            )
                            return@jobCallback
                        }
                        if (shouldIgnoreCancelledPipelineStatus(cancellationToken.isCancelled, timedOutGeneration, localGeneration, pipelineGeneration, newStatus)) {
                            Log.i("KeplerPipelineState", "cancelled pipeline status ignored generation=$localGeneration status=$newStatus")
                            return@jobCallback
                        }
                        status = newStatus
                        finishIfTerminal(newStatus)
                    })
                } catch (_: CancellationException) {
                } catch (t: Exception) {
                    Log.e("KeplerPipelineState", "pipeline crashed generation=$localGeneration", t)
                    if (localGeneration == pipelineGeneration) {
                        finishIfTerminal("PIPELINE_FAILED: ${t.javaClass.simpleName}")
                    }
                }
        }
        activeJobStart.set(jobStart)
        mainHandler.postDelayed(jobStart, 250L)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        PreviewStage(
            modifier = Modifier.fillMaxSize(),
            state = CameraPreviewPaneState(
                cameraSelection = cameraState.selection,
                previewZoomRatio = cameraState.previewZoomRatio,
                selectedLensSlot = selectedLensSlot,
                selectedThreeXSource = selectedThreeXSource,
                focusAeState = focusAeState,
                previewEnabled = previewEnabled,
                isCapturing = isCapturing,
                showFocusAeControls = showFocusAeControls,
                overlaySettings = overlaySettings,
                levelState = levelState
            ),
            callbacks = CameraPreviewPaneCallbacks(
                onFocusPoint = { point ->
                    focusAeState = focusAeState.copy(point = point, locked = false)
                    showFocusAeControls = true
                    focusAeUiNonce++
                    showZoomSlider = false
                    status = "AF/AE point set"
                },
                onAeCapabilitiesChanged = { minIndex, maxIndex, stepEv ->
                    if (
                        focusAeState.supportedMinIndex != minIndex ||
                        focusAeState.supportedMaxIndex != maxIndex ||
                        focusAeState.exposureStepEv != stepEv
                    ) {
                        val index = focusAeState.exposureCompensationIndex.coerceIn(minIndex, maxIndex)
                        focusAeState = focusAeState.copy(
                            supportedMinIndex = minIndex,
                            supportedMaxIndex = maxIndex,
                            exposureStepEv = stepEv,
                            exposureCompensationIndex = index,
                            exposureCompensationEv = index * stepEv
                        )
                    }
                },
                onToggleFocusLock = {
                    val locked = !focusAeState.locked
                    focusAeState = focusAeState.copy(locked = locked)
                    showFocusAeControls = true
                    focusAeUiNonce++
                    showZoomSlider = false
                    status = if (locked) "AF/AE locked" else "AF/AE unlocked"
                },
                onExposureStep = { delta ->
                    val index = (focusAeState.exposureCompensationIndex + delta)
                        .coerceIn(focusAeState.supportedMinIndex, focusAeState.supportedMaxIndex)
                    focusAeState = focusAeState.copy(
                        exposureCompensationIndex = index,
                        exposureCompensationEv = index * focusAeState.exposureStepEv
                    )
                    showFocusAeControls = true
                    focusAeUiNonce++
                    showZoomSlider = false
                    status = "EV ${"%.1f".format(index * focusAeState.exposureStepEv)}"
                }
            )
        )

        CameraTopOverlay(
            modifier = Modifier.align(Alignment.TopCenter),
            status = shortStatus(status),
            selectedResolution = selectedResolution,
            onHideFocusAeControls = {
                showFocusAeControls = false
                showZoomSlider = false
            },
            onResolutionClick = {
                val result = handleResolutionClick(
                    selectedResolution = selectedResolution,
                    allowedResolutionModes = resolutionState.allowedModes,
                    resolutionCapability = resolutionState.capability,
                    resolutionPlans = resolutionState.plans,
                    selectedLensSlot = selectedLensSlot
                )
                selectedResolution = result.selectedResolution
                status = result.status
            },
            onSettings = {
                currentScreen = MainScreen.SETTINGS
            }
        )

        CameraBottomPanel(
                modifier = Modifier.align(Alignment.BottomCenter),
                latestBitmap = latestBitmap,
                selectedLensSlot = selectedLensSlot,
                onLensSlotChange = { lensSlot ->
                    val result = handleLensSlotChange(
                        context = context,
                        lensSlot = lensSlot,
                        selectedResolution = selectedResolution,
                        selectedThreeXSource = selectedThreeXSource,
                        zoomUiState = zoomUiState
                    )
                    selectedLensSlot = result.lensSlot
                    selectedThreeXSource = result.threeXSource
                    zoomUiState = result.zoomUiState
                    showZoomSlider = false
                    result.forcedResolution?.let { selectedResolution = it }
                    status = result.status
                },
                selectedThreeXSource = selectedThreeXSource,
                onThreeXSourceChange = { source ->
                    Log.d(
                        "Kepler3xSelection",
                        "phase=uiEvent selectedSource=$source previousSource=$selectedThreeXSource " +
                            "selectedLensSlot=$selectedLensSlot " +
                            "cameraId=${cameraState.selection.cameraId} " +
                            "actualLensSource=${cameraState.selection.actualLensSource} " +
                            "physicalCameraId=${cameraState.selection.physicalCameraId} " +
                            "requestedUiZoomRatio=${zoomUiState.zoomRatio} " +
                            "previewZoomRatio=${cameraState.previewZoomRatio} " +
                            "captureZoomRatio=${cameraState.captureZoomRatio} " +
                            "finalRequestZoom=${cameraState.captureZoomRatio}"
                    )
                    val result = handleThreeXSourceChange(
                        context = context,
                        source = source,
                        previousThreeXSource = selectedThreeXSource,
                        selectedResolution = selectedResolution,
                        zoomUiState = zoomUiState
                    )
                    selectedLensSlot = result.lensSlot
                    selectedThreeXSource = result.threeXSource
                    zoomUiState = result.zoomUiState
                    showZoomSlider = false
                    result.forcedResolution?.let { selectedResolution = it }
                    status = result.status
                },
                frameCountMode = frameCountMode,
                latestFramePlan = latestFramePlan,
                pipelineMode = pipelineMode,
                zoomUiState = zoomUiState,
                showZoomSlider = showZoomSlider,
                onToggleZoomSlider = {
                    showFocusAeControls = false
                    showZoomSlider = !showZoomSlider
                },
                onZoomRatioChange = {
                    showZoomSlider = true
                    applyZoomRatio(it)
                },
                isCapturing = isCapturing,
                isPipelineBusy = isPipelineBusy,
                captureProgress = captureProgress,
                onHideFocusAeControls = {
                    showFocusAeControls = false
                    showZoomSlider = false
                },
                onCapture = captureClick@{
                    if (isPipelineBusy) {
                        status = "Pipeline busy: current fusion/export is still running."
                        Log.i("KeplerPipelineState", "click ignored while busy status=$status")
                        return@captureClick
                    }
                    val clickResult = handleCaptureClick(
                        CaptureClickInput(
                            context = context,
                            selectedResolution = selectedResolution,
                            resolutionPlans = resolutionState.plans,
                            selectedResolutionPlan = resolutionState.selectedPlan,
                            buildPreparationInput = { activePlan ->
                                CapturePreparationInput(
                                    cameraSelection = cameraState.selection,
                                    requestedUiZoomRatio = zoomUiState.zoomRatio,
                                    captureZoomRatio = cameraState.captureZoomRatio,
                                    resolutionPlan = activePlan,
                                    selectedResolution = selectedResolution,
                                    frameCountMode = frameCountMode,
                                    autoMinFrames = autoMinFrames,
                                    autoMaxFrames = autoMaxFrames,
                                    manualFrames = manualFrames,
                                    selectedMode = selectedMode,
                                    latestSceneLuma = latestSceneLuma,
                                    latestMotionScore = latestMotionScore
                                )
                            }
                        )
                    )
                    when (clickResult) {
                        is CaptureClickResult.InvalidResolution -> {
                            selectedResolution = CaptureResolutionMode.MP12
                            status = clickResult.status
                        }
                        is CaptureClickResult.Ready -> {
                            latestFramePlan = clickResult.prepared.framePlan
                            runCameraJob(
                                startMessage = clickResult.prepared.startMessage,
                                requestedFrames = clickResult.prepared.framePlan.framesToCapture,
                                timeoutMillis = if (pipelineMode == PipelineMode.RAW_NIGHT_FUSION) {
                                    120_000L
                                } else {
                                    60_000L
                                }
                            ) { cancellation, captureCancellation, callback ->
                                startCapturePipeline(
                                    CapturePipelineRequest(
                                        context = context,
                                        pipelineMode = pipelineMode,
                                        prepared = clickResult.prepared,
                                        selectedResolution = selectedResolution,
                                        resolutionPlan = clickResult.resolutionPlan,
                                        finalOutputFormat = finalOutputFormat,
                                        focusAeState = focusAeState,
                                        rawSpeedMode = rawSpeedMode,
                                        captureCancellationHandle = captureCancellation,
                                        cancellation = cancellation
                                    ),
                                    onStatus = callback
                                )
                            }
                        }
                    }
                },
                onAverage = average@{
                    if (isPipelineBusy) {
                        status = "Pipeline busy: current fusion/export is still running."
                        Log.i("KeplerPipelineState", "average/reprocess ignored while busy status=$status")
                        return@average
                    }
                    runCameraJob("최근 촬영 컬러 합성 시작...") { cancellation, _, callback ->
                        processLatestNightFusionV02(context, cancellation) { callback(it) }
                    }
                },
                onRaw = {
                    runCameraJob("RAW DNG 촬영 준비 중...", requestedFrames = 1) { cancellation, _, callback ->
                        cancellation.throwIfCancelled()
                        captureSingleRawDng(
                            context = context,
                            cameraId = LEGACY_DEBUG_CAMERA_ID,
                            onStatus = callback
                        )
                    }
                },
                onRawBurst = {
                    runCameraJob("RAW Burst 촬영 준비 중...", requestedFrames = 4) { cancellation, _, callback ->
                        cancellation.throwIfCancelled()
                        captureRawBurstDng(
                            context = context,
                            cameraId = LEGACY_DEBUG_CAMERA_ID,
                            frameCount = 4,
                            onStatus = callback
                        )
                    }
                },
                onClear = {
                    val deleted = deleteKeplerCache(context)
                    latestBitmap = null
                    latestSummary = "최근 결과 없음"
                    status = "캐시 삭제 완료 ($deleted)"
                },
                onThumbnail = {
                    onOpenGallery()
                }
            )

        ResultPreviewCard(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(18.dp),
            visible = showResultPreview,
            result = latestResult,
            onOpenGallery = onOpenGallery,
            onDismiss = { showResultPreview = false }
        )

        if (currentScreen == MainScreen.SETTINGS) {
            SettingsScreen(
                frameCountMode = frameCountMode,
                onFrameCountModeChange = { frameCountMode = it },
                autoMinFrames = autoMinFrames,
                onAutoMinFramesChange = { value ->
                    autoMinFrames = value.coerceIn(MIN_CAPTURE_FRAMES, autoMaxFrames.coerceAtLeast(MIN_CAPTURE_FRAMES))
                },
                autoMaxFrames = autoMaxFrames,
                onAutoMaxFramesChange = { value ->
                    autoMaxFrames = value.coerceIn(autoMinFrames.coerceAtMost(MAX_CAPTURE_FRAMES), MAX_CAPTURE_FRAMES)
                },
                manualFrames = manualFrames,
                onManualFramesChange = { value ->
                    manualFrames = value.coerceIn(MIN_CAPTURE_FRAMES, MAX_CAPTURE_FRAMES)
                },
                latestFramePlan = latestFramePlan,
                pipelineMode = pipelineMode,
                onPipelineModeChange = { pipelineMode = it },
                rawSpeedMode = rawSpeedMode,
                onRawSpeedModeChange = { rawSpeedMode = it },
                finalOutputFormat = finalOutputFormat,
                onFinalOutputFormatChange = { newFormat ->
                    finalOutputFormat = newFormat
                    OutputSettingsStore.save(context, newFormat)
                    status = "Output: ${newFormat.label}"
                },
                overlaySettings = overlaySettings,
                onOverlaySettingsChange = { newSettings ->
                    overlaySettings = newSettings
                    UiOverlaySettingsStore.save(context, newSettings)
                },
                onResetFocusAe = {
                    focusAeState = focusAeState.copy(
                        point = null,
                        locked = false,
                        exposureCompensationIndex = 0,
                        exposureCompensationEv = 0f
                    )
                    showFocusAeControls = false
                    status = "AF/AE reset"
                },
                onRaw = {
                    currentScreen = MainScreen.CAMERA
                    runCameraJob("RAW DNG capture preparing...") { cancellation, _, callback ->
                        cancellation.throwIfCancelled()
                        captureSingleRawDng(
                            context = context,
                            cameraId = LEGACY_DEBUG_CAMERA_ID,
                            onStatus = callback
                        )
                    }
                },
                onRawBurst = {
                    currentScreen = MainScreen.CAMERA
                    runCameraJob("RAW Burst capture preparing...") { cancellation, _, callback ->
                        cancellation.throwIfCancelled()
                        captureRawBurstDng(
                            context = context,
                            cameraId = LEGACY_DEBUG_CAMERA_ID,
                            frameCount = 4,
                            onStatus = callback
                        )
                    }
                },
                onTest50MRaw = test50@{
                    currentScreen = MainScreen.CAMERA
                    val mainSelection = selectCameraForOptions(
                        context,
                        SelectedCaptureOptions(LensSlot.MAIN_1X, CaptureResolutionMode.MP50, ThreeXSourceMode.MAIN_CROP)
                    )
                    CameraCapabilityCache.clear()
                    val mainCapability = queryCameraResolutionCapability(
                        context,
                        mainSelection.cameraId,
                        LensSlot.MAIN_1X
                    )
                    capabilityRefreshNonce++
                    if (!mainCapability.raw50Available) {
                        status =
                            "50MP RAW unavailable: ${mainCapability.raw50ReasonCode.name}. " +
                                "cameraId=${mainSelection.cameraId}, checked normal/max maps, " +
                                "YUV50=${mainCapability.yuv50Available}, " +
                                "JPEG50=${mainCapability.jpeg50Available}. " +
                                mainCapability.raw50Reason
                        return@test50
                    }
                    runCameraJob("Test 50M RAW Capture: cameraId=${mainSelection.cameraId}", requestedFrames = 1) { cancellation, captureCancellation, callback ->
                        cancellation.throwIfCancelled()
                        captureRawBurstForFusion(
                            context = context,
                            cameraId = mainSelection.cameraId,
                            frameCount = 1,
                            resolutionMode = CaptureResolutionMode.MP50,
                            zoomRatio = 1.0f,
                            requestedUiZoomRatio = 1.0f,
                            focusAeState = focusAeState,
                            captureCancellationHandle = captureCancellation,
                            onStatus = callback,
                            onComplete = { jobDir ->
                                val job = JSONObject(File(jobDir, "job.json").readText())
                                callback(
                                    "PIPELINE_COMPLETE: Test 50M RAW Capture success. cameraId=${mainSelection.cameraId}, size=${job.optInt("rawWidth")}x${job.optInt("rawHeight")} ${"%.1f".format(job.optInt("rawWidth") * job.optInt("rawHeight") / 1_000_000.0)}MP, source=${job.optString("rawSizeSource")}, maxPixelMode=${job.optBoolean("requiresMaximumResolutionPixelMode")}, job=${jobDir.name}"
                                )
                            },
                            onError = { callback("PIPELINE_FAILED: Test 50M RAW Capture failed. $it") }
                        )
                    }
                },
                onTest50MProcessed = test50Processed@{
                    currentScreen = MainScreen.CAMERA
                    val mainSelection = selectCameraForOptions(
                        context,
                        SelectedCaptureOptions(
                            LensSlot.MAIN_1X,
                            CaptureResolutionMode.MP50,
                            ThreeXSourceMode.MAIN_CROP
                        )
                    )
                    CameraCapabilityCache.clear()
                    val capability = queryCameraResolutionCapability(
                        context,
                        mainSelection.cameraId,
                        LensSlot.MAIN_1X
                    )
                    if (!capability.yuv50Available && !capability.jpeg50Available) {
                        status = "50MP processed capture unavailable. ${capability.processed50Reason}"
                        return@test50Processed
                    }
                    runCameraJob(
                        "Test 50M YUV/JPEG Capture: cameraId=${mainSelection.cameraId}",
                        requestedFrames = 1
                    ) { cancellation, _, callback ->
                        cancellation.throwIfCancelled()
                        capture50MpProcessedTest(
                            context = context,
                            cameraId = mainSelection.cameraId,
                            onStatus = callback,
                            onComplete = {},
                            onError = {}
                        )
                    }
                },
                onPrintResolutionReport = {
                    val report = buildResolutionCapabilityReport(context)
                    logLongReport("KeplerCaps", report)
                    status = "Capability report printed to logcat: KeplerCaps"
                    capabilityRefreshNonce++
                    currentScreen = MainScreen.CAMERA
                },
                onRefreshCameraCapabilities = {
                    CameraCapabilityCache.clear()
                    capabilityRefreshNonce++
                    status = "Camera capabilities refreshed."
                    currentScreen = MainScreen.CAMERA
                },
                onBack = {
                    currentScreen = MainScreen.CAMERA
                },
                onOpenDebug = {
                    currentScreen = MainScreen.CAMERA
                    onOpenDebug()
                },
                onOpenCacheJobs = {
                    currentScreen = MainScreen.CAMERA
                    onOpenCacheJobs()
                },
                onClearCache = {
                    val deleted = deleteKeplerCache(context)
                    latestBitmap = null
                    latestSummary = "최근 결과 없음"
                    status = "캐시 삭제 완료 ($deleted)"
                    currentScreen = MainScreen.CAMERA
                }
            )
        }
    }
}

@Composable
fun FocusAeOverlay(
    focusAeState: FocusAeState,
    onToggleLock: () -> Unit,
    onExposureStep: (Int) -> Unit
) {
    val point = focusAeState.point ?: return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val x = maxWidth * point.x
        val y = maxHeight * point.y

        Column(
            modifier = Modifier.offset(
                x = x - 78.dp,
                y = y - 86.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                modifier = Modifier.height(32.dp),
                onClick = onToggleLock,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (focusAeState.locked) Color(0xFFFFD33D) else Color(0xAA111111),
                    contentColor = if (focusAeState.locked) Color.Black else Color.White
                )
            ) {
                Text(if (focusAeState.locked) "LOCKED" else "LOCK")
            }

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .border(
                        width = 2.dp,
                        color = Color(0xFFFFD33D),
                        shape = CircleShape
                    )
            )

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xBB111111))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FrameStepButton(
                    text = "-",
                    enabled = focusAeState.exposureCompensationIndex > focusAeState.supportedMinIndex,
                    onClick = { onExposureStep(-1) }
                )
                Text(
                    text = "EV ${"%.1f".format(focusAeState.exposureCompensationEv)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
                FrameStepButton(
                    text = "+",
                    enabled = focusAeState.exposureCompensationIndex < focusAeState.supportedMaxIndex,
                    onClick = { onExposureStep(1) }
                )
            }
        }
    }
}

@Composable
fun CameraBottomPanel(
    modifier: Modifier = Modifier,
    latestBitmap: Bitmap?,
    selectedLensSlot: LensSlot,
    onLensSlotChange: (LensSlot) -> Unit,
    selectedThreeXSource: ThreeXSourceMode,
    onThreeXSourceChange: (ThreeXSourceMode) -> Unit,
    frameCountMode: FrameCountMode,
    latestFramePlan: FramePlan,
    pipelineMode: PipelineMode,
    zoomUiState: ZoomUiState,
    showZoomSlider: Boolean,
    onToggleZoomSlider: () -> Unit,
    onZoomRatioChange: (Float) -> Unit,
    isCapturing: Boolean,
    isPipelineBusy: Boolean,
    captureProgress: CaptureProgressState,
    onHideFocusAeControls: () -> Unit,
    onCapture: () -> Unit,
    onAverage: () -> Unit,
    onRaw: () -> Unit,
    onRawBurst: () -> Unit,
    onClear: () -> Unit,
    onThumbnail: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = BottomOverlayHorizontalPadding,
                end = BottomOverlayHorizontalPadding,
                top = 0.dp,
                bottom = BottomOverlayBottomPadding
            ),
        verticalArrangement = Arrangement.spacedBy(BottomOverlaySpacing)
    ) {
        AnimatedVisibility(
            visible = showZoomSlider,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xAA111218))
                    .padding(
                        horizontal = FloatingClusterHorizontalPadding,
                        vertical = FloatingClusterVerticalPadding
                    ),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "${"%.1f".format(zoomUiState.zoomRatio)}x",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable(onClick = onToggleZoomSlider)
                )
                CompactZoomSlider(
                    value = zoomUiState.zoomRatio,
                    onZoomRatioChange = onZoomRatioChange,
                    valueRange = zoomUiState.minZoom..zoomUiState.maxZoom,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0x54111218))
                .padding(
                    horizontal = FloatingClusterHorizontalPadding,
                    vertical = FloatingClusterVerticalPadding
                ),
            verticalArrangement = Arrangement.spacedBy(BottomOverlaySpacing)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleZoomSlider),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ZoomSelector(
                    selected = selectedLensSlot,
                    onSelect = onLensSlotChange
                )

                if (selectedLensSlot == LensSlot.THREE_X) {
                    Spacer(modifier = Modifier.size(12.dp))

                    ThreeXSourceDots(
                        selected = selectedThreeXSource,
                        onSelect = onThreeXSourceChange
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ResultThumbnail(
                    bitmap = latestBitmap,
                    onClick = onThumbnail
                )

                Spacer(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onHideFocusAeControls)
                )

                if (isPipelineBusy) {
                    PipelineBusyShutterIndicator(
                        captureProgress = captureProgress,
                        modifier = Modifier.size(ShutterOuterSize)
                    )
                } else {
                    ShutterButton(
                        enabled = true,
                        isCapturing = false,
                        onClick = onCapture
                    )
                }

                Spacer(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onHideFocusAeControls)
                )

                CameraSwitchButton(
                    enabled = !isPipelineBusy,
                    onClick = onAverage
                )
            }

            Text(
                text = if (frameCountMode == FrameCountMode.AUTO) {
                    "${pipelineMode.label}  |  Auto: ${latestFramePlan.framesToCapture} / ${latestFramePlan.maxFrames} frames"
                } else {
                    "${pipelineMode.label}  |  Manual: ${latestFramePlan.framesToCapture} frames"
                },
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            if (isPipelineBusy) {
                CaptureProgressRow(captureProgress = captureProgress)
            }

            ModeTabs()
        }

        if (SHOW_LEGACY_RAW_ACTIONS) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiniActionButton(
                    modifier = Modifier.weight(1f),
                    text = "RAW",
                    enabled = !isPipelineBusy,
                    onClick = onRaw
                )

                MiniActionButton(
                    modifier = Modifier.weight(1f),
                    text = "RAW 4장",
                    enabled = !isPipelineBusy,
                    onClick = onRawBurst
                )

                MiniActionButton(
                    modifier = Modifier.weight(1f),
                    text = "삭제",
                    enabled = !isPipelineBusy,
                    onClick = onClear
                )
            }
        }
    }
}

@Composable
fun ResultPreviewCard(
    modifier: Modifier = Modifier,
    visible: Boolean,
    result: LatestKeplerResult?,
    onOpenGallery: () -> Unit,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        modifier = modifier,
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xEE11131B),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Result saved",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                val bitmap = result?.bitmap
                if (bitmap != null && !bitmap.isRecycled) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Latest fused result",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.Black),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = "Result saved, preview unavailable\n${result?.filePath?.ifBlank { result.jobName }.orEmpty()}",
                        color = Color.White.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = buildString {
                        val r = result
                        append(r?.jobType?.ifBlank { "Kepler job" } ?: "Kepler job")
                        r?.fusionEngine?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
                        if ((r?.usedFrames ?: 0) > 0 || (r?.requestedFrames ?: 0) > 0) {
                            append(" | frames=").append(r?.usedFrames ?: 0).append("/").append(r?.requestedFrames ?: 0)
                        }
                        if (r?.outputWidth != null && r.outputHeight != null) {
                            append(" | ").append(r.outputWidth).append("x").append(r.outputHeight)
                        }
                        r?.fileName?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
                    },
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = onOpenGallery,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Open Gallery / Jobs")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = onDismiss,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF232633),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Dismiss")
                    }
                }
            }
        }
    }
}

@Composable
fun PipelineBusyShutterIndicator(
    captureProgress: CaptureProgressState,
    modifier: Modifier = Modifier
) {
    val progress = captureProgress.progressPercent.coerceIn(0f, 1f)
    val percent = (progress * 100f).roundToInt()
    val label = when {
        percent in 5..99 -> "$percent%"
        captureProgress.stage == CaptureStage.CAPTURING -> "촬영 중"
        captureProgress.stage == CaptureStage.EXPORTING ||
            captureProgress.stage == CaptureStage.VERIFYING ||
            captureProgress.stage == CaptureStage.CLEANING -> "저장 중"
        captureProgress.stage == CaptureStage.PROCESSING ||
            captureProgress.stage == CaptureStage.DEMOSAICING -> "합성 중"
        else -> "처리 중"
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xCC111218)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 7.dp.toPx()
            val inset = strokeWidth / 2f + 3.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            drawCircle(
                color = Color.White.copy(alpha = 0.18f),
                radius = size.minDimension / 2f - inset,
                style = Stroke(width = strokeWidth)
            )
            drawArc(
                color = Color(0xFFFFD33D),
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceAtLeast(0.08f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.10f),
                radius = size.minDimension / 2f - 15.dp.toPx()
            )
        }
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
fun CaptureProgressRow(
    captureProgress: CaptureProgressState
) {
    val stageText = when (captureProgress.stage) {
        CaptureStage.IDLE -> "Ready"
        CaptureStage.PREPARING -> "Preparing"
        CaptureStage.CAPTURING -> "RAW 캡처 중입니다."
        CaptureStage.PROCESSING -> "RAW 합성 처리 중입니다."
        CaptureStage.DEMOSAICING -> "Native RAW ISP 렌더링 중입니다."
        CaptureStage.EXPORTING -> "결과 미리보기를 준비하는 중입니다."
        CaptureStage.VERIFYING -> "결과를 저장하는 중입니다."
        CaptureStage.CLEANING -> "결과를 저장하는 중입니다."
        CaptureStage.COMPLETE -> "처리가 완료되었습니다."
        CaptureStage.FAILED -> "처리하지 못했습니다."
        CaptureStage.CANCELLED -> "취소되었습니다."
        CaptureStage.TIMEOUT -> "처리 시간이 초과되었습니다."
    }
    val frameText = if (captureProgress.requestedFrames > 0) {
        "${captureProgress.savedFrames} / ${captureProgress.requestedFrames}"
    } else {
        ""
    }
    val detailText = buildString {
        if (captureProgress.stage == CaptureStage.CAPTURING) {
            append("촬영 중입니다. 기기를 움직이지 마세요.")
        } else if (
            captureProgress.stage == CaptureStage.PROCESSING ||
            captureProgress.stage == CaptureStage.DEMOSAICING ||
            captureProgress.stage == CaptureStage.EXPORTING ||
            captureProgress.stage == CaptureStage.VERIFYING ||
            captureProgress.stage == CaptureStage.CLEANING
        ) {
            append("처리 중입니다.")
        } else if (captureProgress.receivedImages > 0 || captureProgress.completedResults > 0) {
            append("Images ${captureProgress.receivedImages}")
            if (captureProgress.requestedFrames > 0) append(" / ${captureProgress.requestedFrames}")
            append(" · Results ${captureProgress.completedResults}")
            if (captureProgress.requestedFrames > 0) append(" / ${captureProgress.requestedFrames}")
        } else {
            append(captureProgress.message)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = listOf(stageText, frameText).filter { it.isNotBlank() }.joinToString(" "),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${(captureProgress.progressPercent * 100).toInt()}%",
                color = Color.White.copy(alpha = 0.68f),
                style = MaterialTheme.typography.labelMedium
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.18f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(captureProgress.progressPercent.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White)
            )
        }

        Text(
            text = detailText,
            color = Color.White.copy(alpha = 0.62f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ZoomSelector(
    selected: LensSlot,
    onSelect: (LensSlot) -> Unit
) {
    val values = listOf(
        LensSlot.ULTRAWIDE,
        LensSlot.MAIN_1X,
        LensSlot.MAIN_2X,
        LensSlot.THREE_X
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF202026))
            .padding(
                horizontal = ZoomSelectorHorizontalPadding,
                vertical = ZoomSelectorVerticalPadding
            ),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        values.forEach { value ->
            Box(
                modifier = Modifier
                    .size(width = ZoomSelectorItemWidth, height = ZoomSelectorItemHeight)
                    .clip(RoundedCornerShape(ZoomSelectorItemCornerRadius))
                    .background(
                        if (selected == value) Color(0xFF4A4A52)
                        else Color.Transparent
                    )
                    .clickable {
                        Log.d("KeplerSmoke", "ZoomSelector clicked lensSlot=$value")
                        onSelect(value)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = value.label,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
fun CompactZoomSlider(
    value: Float,
    onZoomRatioChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    val clampedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val range = max(valueRange.endInclusive - valueRange.start, 0.0001f)
    val activeTrackColor = Color.White
    val inactiveTrackColor = Color.White.copy(alpha = 0.24f)
    val thumbColor = Color.White

    BoxWithConstraints(
        modifier = modifier
            .height(CompactSliderHeight)
            .pointerInput(valueRange.start, valueRange.endInclusive) {
                detectTapGestures { offset ->
                    onZoomRatioChange(
                        mapSliderPositionToValue(
                            x = offset.x,
                            width = size.width.toFloat(),
                            valueRange = valueRange
                        )
                    )
                }
            }
            .pointerInput(valueRange.start, valueRange.endInclusive) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, _ ->
                        onZoomRatioChange(
                            mapSliderPositionToValue(
                                x = change.position.x,
                                width = size.width.toFloat(),
                                valueRange = valueRange
                            )
                        )
                    }
                )
            }
    ) {
        val fraction = ((clampedValue - valueRange.start) / range).coerceIn(0f, 1f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackHeightPx = CompactSliderTrackHeight.toPx()
            val thumbRadiusPx = CompactSliderThumbSize.toPx() / 2f
            val centerY = size.height / 2f
            val widthPx = size.width
            val thumbX = fraction * widthPx
            drawLine(
                color = inactiveTrackColor,
                start = Offset(0f, centerY),
                end = Offset(widthPx, centerY),
                strokeWidth = trackHeightPx
            )
            drawLine(
                color = activeTrackColor,
                start = Offset(0f, centerY),
                end = Offset(thumbX, centerY),
                strokeWidth = trackHeightPx
            )
            drawCircle(
                color = thumbColor,
                radius = thumbRadiusPx,
                center = Offset(thumbX, centerY)
            )
        }
    }
}

@Composable
fun ThreeXSourceDots(
    selected: ThreeXSourceMode,
    onSelect: (ThreeXSourceMode) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VisibleThreeXSourceModes.forEach { source ->
            ThreeXSourceDot(
                source = source,
                selected = selected == source,
                onClick = {
                    Log.d("KeplerSmoke", "ThreeXSourceDot clicked source=$source")
                    onSelect(source)
                }
            )
        }
    }
}

@Composable
fun ThreeXSourceDot(
    source: ThreeXSourceMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = source.label,
            color = if (selected) Color.Black else Color.White,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun MiniActionButton(
    modifier: Modifier = Modifier,
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        modifier = modifier.height(42.dp),
        enabled = enabled,
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1B1B22),
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF141419),
            disabledContentColor = Color.White.copy(alpha = 0.35f)
        )
    ) {
        Text(text)
    }
}

@Composable
fun RuleOfThirdsGridOverlay(
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val lineColor = Color.White.copy(alpha = 0.28f)
        val stroke = 1.dp.toPx()
        val x1 = size.width / 3f
        val x2 = size.width * 2f / 3f
        val y1 = size.height / 3f
        val y2 = size.height * 2f / 3f

        drawLine(lineColor, androidx.compose.ui.geometry.Offset(x1, 0f), androidx.compose.ui.geometry.Offset(x1, size.height), stroke)
        drawLine(lineColor, androidx.compose.ui.geometry.Offset(x2, 0f), androidx.compose.ui.geometry.Offset(x2, size.height), stroke)
        drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, y1), androidx.compose.ui.geometry.Offset(size.width, y1), stroke)
        drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, y2), androidx.compose.ui.geometry.Offset(size.width, y2), stroke)
    }
}

@Composable
fun SettingsScreen(
    frameCountMode: FrameCountMode,
    onFrameCountModeChange: (FrameCountMode) -> Unit,
    autoMinFrames: Int,
    onAutoMinFramesChange: (Int) -> Unit,
    autoMaxFrames: Int,
    onAutoMaxFramesChange: (Int) -> Unit,
    manualFrames: Int,
    onManualFramesChange: (Int) -> Unit,
    latestFramePlan: FramePlan,
    pipelineMode: PipelineMode,
    onPipelineModeChange: (PipelineMode) -> Unit,
    rawSpeedMode: RawSpeedMode,
    onRawSpeedModeChange: (RawSpeedMode) -> Unit,
    finalOutputFormat: FinalOutputFormat,
    onFinalOutputFormatChange: (FinalOutputFormat) -> Unit,
    overlaySettings: UiOverlaySettings,
    onOverlaySettingsChange: (UiOverlaySettings) -> Unit,
    onResetFocusAe: () -> Unit,
    onRaw: () -> Unit,
    onRawBurst: () -> Unit,
    onTest50MRaw: () -> Unit,
    onTest50MProcessed: () -> Unit,
    onPrintResolutionReport: () -> Unit,
    onRefreshCameraCapabilities: () -> Unit,
    onBack: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenCacheJobs: () -> Unit,
    onClearCache: () -> Unit
) {
    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onBack,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF232633),
                    contentColor = Color.White
                )
            ) {
                Text("Back")
            }

            Spacer(modifier = Modifier.size(12.dp))

            Text(
                text = "Settings",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF11131B),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "설정",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )

                Text(
                    text = "Kepler Night Lab",
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.bodyMedium
                )

                FrameCountSettingsSection(
                    frameCountMode = frameCountMode,
                    onFrameCountModeChange = onFrameCountModeChange,
                    autoMinFrames = autoMinFrames,
                    onAutoMinFramesChange = onAutoMinFramesChange,
                    autoMaxFrames = autoMaxFrames,
                    onAutoMaxFramesChange = onAutoMaxFramesChange,
                    manualFrames = manualFrames,
                    onManualFramesChange = onManualFramesChange,
                    latestFramePlan = latestFramePlan
                )

                PipelineModeSettingsSection(
                    pipelineMode = pipelineMode,
                    onPipelineModeChange = onPipelineModeChange,
                    rawSpeedMode = rawSpeedMode,
                    onRawSpeedModeChange = onRawSpeedModeChange
                )

                OutputFormatSettingsSection(
                    finalOutputFormat = finalOutputFormat,
                    onFinalOutputFormatChange = onFinalOutputFormatChange
                )

                OverlaySettingsSection(
                    overlaySettings = overlaySettings,
                    onOverlaySettingsChange = onOverlaySettingsChange
                )

                MiniSettingsButton(
                    text = "Reset AF/AE",
                    onClick = onResetFocusAe
                )

                MiniSettingsButton(
                    text = "RAW",
                    onClick = onRaw
                )

                MiniSettingsButton(
                    text = "RAW Burst",
                    onClick = onRawBurst
                )

                MiniSettingsButton(
                    text = "Test 50M RAW Capture",
                    onClick = onTest50MRaw
                )

                MiniSettingsButton(
                    text = "Test 50M YUV/JPEG Capture",
                    onClick = onTest50MProcessed
                )

                MiniSettingsButton(
                    text = "Print Resolution Capability Report",
                    onClick = onPrintResolutionReport
                )

                MiniSettingsButton(
                    text = "Refresh camera capabilities",
                    onClick = onRefreshCameraCapabilities
                )

                MiniSettingsButton(
                    text = "Cache / Jobs",
                    onClick = onOpenCacheJobs
                )

                MiniSettingsButton(
                    text = "디버그 화면",
                    onClick = onOpenDebug
                )

                MiniSettingsButton(
                    text = "캐시 삭제",
                    onClick = onClearCache
                )

                MiniSettingsButton(
                    text = "닫기",
                    onClick = onBack
                )
            }
        }
    }
}

@Composable
fun OutputFormatSettingsSection(
    finalOutputFormat: FinalOutputFormat,
    onFinalOutputFormatChange: (FinalOutputFormat) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Output",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                FinalOutputFormat.HEIF,
                FinalOutputFormat.JPEG
            ).forEach { format ->
                FrameModeChip(
                    text = format.label,
                    selected = finalOutputFormat == format,
                    onClick = { onFinalOutputFormatChange(format) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                FinalOutputFormat.HEIF_PLUS_RAW,
                FinalOutputFormat.JPEG_PLUS_RAW
            ).forEach { format ->
                FrameModeChip(
                    text = format.label,
                    selected = finalOutputFormat == format,
                    onClick = { onFinalOutputFormatChange(format) }
                )
            }
        }
        FrameModeChip(
            text = FinalOutputFormat.PNG_DEBUG.label,
            selected = finalOutputFormat == FinalOutputFormat.PNG_DEBUG,
            onClick = { onFinalOutputFormatChange(FinalOutputFormat.PNG_DEBUG) }
        )
    }
}

@Composable
fun FrameCountSettingsSection(
    frameCountMode: FrameCountMode,
    onFrameCountModeChange: (FrameCountMode) -> Unit,
    autoMinFrames: Int,
    onAutoMinFramesChange: (Int) -> Unit,
    autoMaxFrames: Int,
    onAutoMaxFramesChange: (Int) -> Unit,
    manualFrames: Int,
    onManualFramesChange: (Int) -> Unit,
    latestFramePlan: FramePlan
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Frame Count",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FrameModeChip(
                text = "Auto",
                selected = frameCountMode == FrameCountMode.AUTO,
                onClick = { onFrameCountModeChange(FrameCountMode.AUTO) }
            )
            FrameModeChip(
                text = "Manual",
                selected = frameCountMode == FrameCountMode.MANUAL,
                onClick = { onFrameCountModeChange(FrameCountMode.MANUAL) }
            )
        }

        FrameCountStepper(
            label = "Auto min",
            value = autoMinFrames,
            enabled = frameCountMode == FrameCountMode.AUTO,
            onChange = onAutoMinFramesChange
        )

        FrameCountStepper(
            label = "Auto max",
            value = autoMaxFrames,
            enabled = frameCountMode == FrameCountMode.AUTO,
            onChange = onAutoMaxFramesChange
        )

        FrameCountStepper(
            label = "Manual",
            value = manualFrames,
            enabled = frameCountMode == FrameCountMode.MANUAL,
            onChange = onManualFramesChange
        )

        Text(
            text = "Current plan: ${latestFramePlan.framesToCapture} frames / max ${latestFramePlan.maxFrames}",
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            text = "Reason: ${latestFramePlan.reason}",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun PipelineModeSettingsSection(
    pipelineMode: PipelineMode,
    onPipelineModeChange: (PipelineMode) -> Unit,
    rawSpeedMode: RawSpeedMode,
    onRawSpeedModeChange: (RawSpeedMode) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "야간 모드",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrameModeChip(
                text = "빠른 야간 모드",
                selected = pipelineMode == PipelineMode.YUV_NIGHT_FUSION,
                onClick = { onPipelineModeChange(PipelineMode.YUV_NIGHT_FUSION) }
            )
            FrameModeChip(
                text = "고품질 RAW 야간 모드",
                selected = pipelineMode == PipelineMode.RAW_NIGHT_FUSION,
                onClick = { onPipelineModeChange(PipelineMode.RAW_NIGHT_FUSION) }
            )
        }
        Text(
            text = if (pipelineMode == PipelineMode.RAW_NIGHT_FUSION) {
                "RAW 처리는 시간이 더 오래 걸릴 수 있습니다.\nRAW 원본을 합성하여 더 높은 품질을 목표로 처리합니다. 시간이 더 오래 걸릴 수 있습니다."
            } else {
                "빠르게 여러 장을 합성하여 노이즈를 줄이고 선명도를 개선합니다."
            },
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "RAW Speed",
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RawSpeedMode.entries.forEach { mode ->
                FrameModeChip(
                    text = mode.label,
                    selected = rawSpeedMode == mode,
                    onClick = { onRawSpeedModeChange(mode) }
                )
            }
        }
    }
}

@Composable
fun OverlaySettingsSection(
    overlaySettings: UiOverlaySettings,
    onOverlaySettingsChange: (UiOverlaySettings) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Overlays",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrameModeChip(
                text = "3x3 Grid",
                selected = overlaySettings.showGrid,
                onClick = {
                    onOverlaySettingsChange(
                        overlaySettings.copy(showGrid = !overlaySettings.showGrid)
                    )
                }
            )
            FrameModeChip(
                text = "Level",
                selected = overlaySettings.showLevel,
                onClick = {
                    onOverlaySettingsChange(
                        overlaySettings.copy(showLevel = !overlaySettings.showLevel)
                    )
                }
            )
        }
    }
}

@Composable
fun FrameModeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color.White else Color(0xFF232633))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) Color.Black else Color.White,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
fun FrameCountStepper(
    label: String,
    value: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit
) {
    val alpha = if (enabled) 1.0f else 0.42f

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = alpha),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )

        FrameStepButton(
            text = "-",
            enabled = enabled && value > MIN_CAPTURE_FRAMES,
            onClick = { onChange(value - 1) }
        )

        Text(
            text = value.toString(),
            color = Color.White.copy(alpha = alpha),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        FrameStepButton(
            text = "+",
            enabled = enabled && value < MAX_CAPTURE_FRAMES,
            onClick = { onChange(value + 1) }
        )
    }
}

@Composable
fun FrameStepButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (enabled) Color(0xFF303442) else Color(0xFF1B1D25))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = if (enabled) 1.0f else 0.35f),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
fun MiniSettingsButton(
    text: String,
    onClick: () -> Unit
) {
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF232633),
            contentColor = Color.White
        )
    ) {
        Text(text)
    }
}

@Composable
fun DebugScreenWrapper(
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        KeplerNightLabApp()

        Button(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
            onClick = onBack
        ) {
            Text("메인으로")
        }
    }
}

fun loadLatestKeplerResult(context: Context): LatestKeplerResult {
    return try {
        val picturesDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            ?: return LatestKeplerResult(
                bitmap = null,
                summary = "Pictures 폴더를 찾지 못함"
            )

        val colorRoot = File(picturesDir, "KeplerYuvFusion")

        if (!colorRoot.exists()) {
            return LatestKeplerResult(
                bitmap = null,
                summary = "KeplerYuvFusion 폴더가 없음"
            )
        }

        val latestJobDir = colorRoot
            .listFiles()
            ?.filter { it.isDirectory && File(it, "job.json").exists() }
            ?.maxByOrNull { it.lastModified() }
            ?: return LatestKeplerResult(
                bitmap = null,
                summary = "최근 YUV Night Fusion job 없음"
            )

        val jobFile = File(latestJobDir, "job.json")
        val job = JSONObject(jobFile.readText())

        val firstFrameName = job.optJSONArray("frames")
            ?.optJSONObject(0)
            ?.optString("file")
            .orEmpty()
        val averageFileName = listOf(
            job.optString("finalNightFusionFile", ""),
            job.optString("averageColorFile", ""),
            firstFrameName
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val status = job.optString("status", "unknown")
        val savedFrames = job.optInt("savedFrames", 0)
        val exportStatus = job.optString("exportStatus", "not_exported")
        val exportVerified = job.optBoolean("exportVerified", false)
        val exportFormat = job.optString("exportFormatUsed", "")
        val cleanupStatus = job.optString("cleanupStatus", "none")

        val bitmap = if (averageFileName.isNotBlank()) {
            val averageFile = File(latestJobDir, averageFileName)
            if (averageFile.exists()) {
                BitmapFactory.decodeFile(averageFile.absolutePath)
            } else {
                null
            }
        } else {
            null
        }

        val summary = "status=$status, frames=$savedFrames, export=$exportStatus $exportFormat verified=$exportVerified, cleanup=$cleanupStatus, job=${latestJobDir.name}"

        LatestKeplerResult(
            bitmap = bitmap,
            summary = summary
        )
    } catch (e: Exception) {
        LatestKeplerResult(
            bitmap = null,
            summary = "최근 결과 로드 실패: ${e.javaClass.simpleName}"
        )
    }
}

fun loadLatestKeplerResultV2(context: Context): LatestKeplerResult {
    return try {
        val picturesDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            ?: return LatestKeplerResult(null, "Pictures folder unavailable")

        val latestJobs = listOf(
            File(picturesDir, "KeplerYuvFusion") to "KPL_YUV_FUSION_",
            File(picturesDir, "KeplerColorBurst") to "KPL_COLOR_BURST_",
            File(picturesDir, "KeplerRawFusion") to "KPL_RAW_FUSION_",
            File(picturesDir, "KeplerSuperRes") to "KPL_SUPER_RES_"
        ).flatMap { (root, prefix) ->
            root.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith(prefix) && File(it, "job.json").exists() }
                .orEmpty()
        }.sortedByDescending { it.lastModified() }

        val latest = latestJobs.firstNotNullOfOrNull { jobDir ->
            if (isReprocessQuarantined(jobDir)) return@firstNotNullOfOrNull null
            val job = runCatching { JSONObject(File(jobDir, "job.json").readText()) }.getOrNull()
                ?: return@firstNotNullOfOrNull null
            val hasCurrentPreview = hasCurrentPreviewFile(jobDir, job)
            if (!hasCurrentPreview &&
                (job.optBoolean("galleryDisplayUnavailable", false) ||
                    (job.optBoolean("galleryExportCommitted", false) &&
                        !job.optBoolean("finalOutputAvailable", false)))
            ) return@firstNotNullOfOrNull null
            if (!job.optBoolean("galleryVisible", true) && !hasCurrentPreview) {
                return@firstNotNullOfOrNull null
            }
            if (KeplerJobMetadata.isOperationActive(jobDir) || isKeplerJobActive(job)) {
                return@firstNotNullOfOrNull null
            }
            val file = chooseLatestResultFile(jobDir, job) ?: return@firstNotNullOfOrNull null
            Triple(jobDir, job, file)
        }
            ?: return LatestKeplerResult(null, "No Kepler jobs found")

        val latestJobDir = latest.first
        val job = latest.second
        val previewFile = latest.third
        val outputWidth = job.optInt("outputWidth", 0).takeIf { it > 0 }
        val outputHeight = job.optInt("outputHeight", 0).takeIf { it > 0 }
        var decoded: Bitmap? = null
        try {
            decoded = if (
                previewFile.extension.equals("rgba", ignoreCase = true) &&
                outputWidth != null &&
                outputHeight != null
            ) {
                decodeNativeRgbaPreview(previewFile, outputWidth, outputHeight)
            } else {
                decodeLatestResultPreview(previewFile)
            }
            val bitmap = decoded
            val jobType = job.optString("jobType", latestJobDir.parentFile?.name.orEmpty())
            val fusionEngine = listOf(
                job.optString("fusionEngine", ""),
                job.optString("rawFusionEngine", ""),
                job.optString("fusionVersion", ""),
                job.optString("rawFusionVersion", "")
            ).firstOrNull { it.isNotBlank() }.orEmpty()
            val usedFrames = job.optInt("usedFrameCount", job.optInt("savedFrames", 0))
            val requestedFrames = job.optInt("requestedFrames", 0)
            val summary = buildString {
                append("status=")
                append(job.optString("status", "unknown"))
                append(", frames=")
                append(job.optInt("savedFrames", 0))
                append(", export=")
                append(job.optString("exportStatus", "not_exported"))
                append(" ")
                append(job.optString("exportFormatUsed", ""))
                append(" verified=")
                append(job.optBoolean("exportVerified", false))
                append(", output=")
                append(job.optString("finalOutputFormatSetting", ""))
                append(", public=")
                append(job.optString("exportDisplayName", "").ifBlank { "none" })
                append(", rawSidecar=")
                append(job.optString("rawSidecarExportStatus", "NOT_REQUESTED"))
                append(", cleanup=")
                append(job.optString("cleanupStatus", "none"))
                append(", job=")
                append(latestJobDir.name)
                append(", file=")
                append(previewFile.name)
                append(", source=")
                append(job.optString("finalOutputSource", "bitmap"))
            }
            val result = LatestKeplerResult(
                bitmap = bitmap,
                summary = summary,
                jobType = jobType,
                fusionEngine = fusionEngine,
                usedFrames = usedFrames,
                requestedFrames = requestedFrames,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                fileName = previewFile.name,
                jobName = latestJobDir.name,
                filePath = previewFile.absolutePath
            )
            decoded = null
            result
        } catch (e: Exception) {
            decoded?.takeIf { !it.isRecycled }?.recycle()
            LatestKeplerResult(null, "Latest result load failed: ${e.javaClass.simpleName}")
        }
    } catch (e: Exception) {
        LatestKeplerResult(null, "Latest result load failed: ${e.javaClass.simpleName}")
    }
}

private fun chooseLatestResultFile(jobDir: File, job: JSONObject): File? {
    val currentNames = listOf(
        job.optString("galleryDisplayFile", ""),
        job.optString("galleryThumbnailFile", ""),
        job.optString("previewFile", "")
    )
    val current = currentNames.asSequence()
        .filter { it.isNotBlank() && it != "null" }
        .map { File(jobDir, it) }
        .firstOrNull(::isCurrentPreviewFile)
    if (current != null) return current

    if (job.optBoolean("galleryDisplayUnavailable", false) ||
        (job.optBoolean("galleryExportCommitted", false) &&
            !job.optBoolean("finalOutputAvailable", false))
    ) return null

    val names = listOf(
        job.optString("finalFile", ""),
        job.optString("outputFile", ""),
        job.optString("finalNightFusionFile", ""),
        "sharpened_night_fusion.png",
        "raw_fusion_final.png",
        "average_color_rotated.png"
    )
    return names
        .asSequence()
        .filter { it.isNotBlank() && it != "null" }
        .map { File(jobDir, it) }
        .filter { it.extension.lowercase() in setOf("png", "jpg", "jpeg", "heic", "webp") }
        .firstOrNull {
            isCurrentPreviewFile(it) &&
                it.extension.lowercase() in setOf("jpg", "jpeg", "png", "heic", "webp") &&
                !it.name.contains("compare", ignoreCase = true) &&
                !it.name.contains("debug", ignoreCase = true)
        }
}

private fun hasCurrentPreviewFile(jobDir: File, job: JSONObject): Boolean =
    listOf(
        job.optString("galleryDisplayFile", ""),
        job.optString("galleryThumbnailFile", ""),
        job.optString("previewFile", "")
    ).asSequence()
        .filter { it.isNotBlank() && it != "null" }
        .map { File(jobDir, it) }
        .any(::isCurrentPreviewFile)

private fun isCurrentPreviewFile(file: File): Boolean =
    file.isFile && file.length() > 0L &&
        file.extension.lowercase() in setOf("rgba", "jpg", "jpeg", "png", "heic", "webp") &&
        !file.name.contains("compare", ignoreCase = true) &&
        !file.name.contains("debug", ignoreCase = true)

private fun isKeplerJobActive(job: JSONObject): Boolean {
    val activeStates = setOf(
        "CAPTURING", "PROCESSING", "YUV_ALIGNING", "YUV_MERGING",
        "YUV_DENOISE_SHARPEN", "YUV_EXPORTING", "RAW_ALIGNING", "RAW_MERGING",
        "RAW_EXPORTING", "EXPORTING", "REPROCESSING", "FINALIZING"
    )
    return listOf("status", "processStatus", "currentPipelineStage")
        .any { job.optString(it).uppercase() in activeStates }
}

private fun decodeNativeRgbaPreview(
    file: File,
    width: Int,
    height: Int,
    maxDimension: Int = 1280
): Bitmap? {
    if (width <= 0 || height <= 0) return null
    val expectedBytes = width.toLong() * height.toLong() * 4L
    if (!file.exists() || file.length() != expectedBytes) return null
    val scale = max(1, max(width, height) / maxDimension)
    val outWidth = max(1, width / scale)
    val outHeight = max(1, height / scale)
    val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
    try {
        val row = ByteArray(width * 4)
        RandomAccessFile(file, "r").use { input ->
            for (y in 0 until outHeight) {
                val sourceY = min(height - 1, y * scale)
                input.seek((sourceY.toLong() * width.toLong()) * 4L)
                input.readFully(row)
                for (x in 0 until outWidth) {
                    val sourceX = min(width - 1, x * scale)
                    val p = sourceX * 4
                    val r = row[p].toInt() and 0xFF
                    val g = row[p + 1].toInt() and 0xFF
                    val b = row[p + 2].toInt() and 0xFF
                    val a = row[p + 3].toInt() and 0xFF
                    bitmap.setPixel(x, y, android.graphics.Color.argb(a, r, g, b))
                }
            }
        }
    } catch (t: Throwable) {
        bitmap.recycle()
        throw t
    }
    return bitmap
}

private fun decodeLatestResultPreview(file: File, maxDimension: Int = 1280): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (
        bounds.outWidth / (sampleSize * 2) >= maxDimension ||
        bounds.outHeight / (sampleSize * 2) >= maxDimension
    ) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sampleSize }
    )
}
