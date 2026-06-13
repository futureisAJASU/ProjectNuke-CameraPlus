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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

data class LatestKeplerResult(
    val bitmap: Bitmap?,
    val summary: String
)

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

fun parseCaptureProgress(
    text: String,
    fallback: CaptureProgressState
): CaptureProgressState {
    val lower = text.lowercase()
    val stage = when {
        text.contains("CAPTURE_TIMEOUT", ignoreCase = true) ||
                text.contains("PROCESS_TIMEOUT", ignoreCase = true) ||
                text.contains("EXPORT_TIMEOUT", ignoreCase = true) ||
                lower.contains("timeout") -> CaptureStage.TIMEOUT
        text.contains("PIPELINE_FAILED", ignoreCase = true) ||
                lower.contains("failed") ||
                text.contains("실패") ||
                text.contains("오류") -> CaptureStage.FAILED
        text.contains("PIPELINE_COMPLETE", ignoreCase = true) ||
                text.contains("완료") ||
                lower.contains("saved to gallery") -> CaptureStage.COMPLETE
        lower.contains("cleanup") || lower.contains("cleaning") -> CaptureStage.CLEANING
        lower.contains("verifying") || lower.contains("verification") -> CaptureStage.VERIFYING
        lower.contains("exporting") || lower.contains("export ") -> CaptureStage.EXPORTING
        lower.contains("demosaic") -> CaptureStage.DEMOSAICING
        text.contains("CAPTURE_COMPLETE_PARTIAL", ignoreCase = true) ||
                lower.contains("processing") || lower.contains("merging") || lower.contains("loading frames") -> CaptureStage.PROCESSING
        lower.contains("capture") || lower.contains("capturing") || lower.contains("frame saved") -> CaptureStage.CAPTURING
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
    onOpenCacheJobs: () -> Unit
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var status by remember { mutableStateOf("대기 중") }
    var previewEnabled by remember { mutableStateOf(true) }
    var isCapturing by remember { mutableStateOf(false) }
    var currentScreen by remember { mutableStateOf(MainScreen.CAMERA) }
    var captureProgress by remember { mutableStateOf(CaptureProgressState()) }

    val selectedMode = "사진"
    var selectedResolution by remember { mutableStateOf(CaptureResolutionMode.MP12) }
    var selectedLensSlot by remember { mutableStateOf(LensSlot.MAIN_1X) }
    var selectedThreeXSource by remember { mutableStateOf(ThreeXSourceMode.OPTICAL) }
    var zoomUiState by remember { mutableStateOf(ZoomUiState()) }
    var pipelineMode by remember { mutableStateOf(PipelineMode.RAW_NIGHT_FUSION) }
    var finalOutputFormat by remember { mutableStateOf(OutputSettingsStore.load(context)) }
    var focusAeState by remember { mutableStateOf(FocusAeState()) }
    var showFocusAeControls by remember { mutableStateOf(false) }
    var showZoomSlider by remember { mutableStateOf(false) }
    var focusAeUiNonce by remember { mutableStateOf(0) }
    var overlaySettings by remember {
        mutableStateOf(UiOverlaySettingsStore.load(context))
    }
    var frameCountMode by remember { mutableStateOf(FrameCountMode.AUTO) }
    var autoMinFrames by remember { mutableStateOf(4) }
    var autoMaxFrames by remember { mutableStateOf(8) }
    var manualFrames by remember { mutableStateOf(4) }
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

    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var latestSummary by remember { mutableStateOf("최근 결과 없음") }

    fun refreshLatestResult() {
        val result = loadLatestKeplerResultV2(context)
        latestBitmap = result.bitmap
        latestSummary = result.summary
        val estimate = estimateLatestColorBurstScene(context)
        latestSceneLuma = estimate.meanLuma
        latestMotionScore = estimate.motionScore
    }

    LaunchedEffect(Unit) {
        refreshLatestResult()
    }

    LaunchedEffect(focusAeUiNonce, focusAeState.locked) {
        if (showFocusAeControls) {
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
        val displayZoom = (clamped * 10f).roundToInt() / 10f
        zoomUiState = zoomUiState.copy(
            zoomRatio = clamped,
            lensSlot = nextSlot,
            useOpticalTeleAt3x = selectedThreeXSource == ThreeXSourceMode.OPTICAL
        )
        if (nextSlot != selectedLensSlot) {
            selectedLensSlot = nextSlot
        }
        status = "Zoom ${"%.1f".format(displayZoom)}x"
    }

    fun runCameraJob(
        startMessage: String,
        requestedFrames: Int = 0,
        timeoutMillis: Long = 120_000L,
        job: ((String) -> Unit) -> Unit
    ) {
        status = startMessage
        isCapturing = true
        previewEnabled = false
        captureProgress = CaptureProgressState(
            stage = CaptureStage.PREPARING,
            message = "Preparing capture...",
            requestedFrames = requestedFrames,
            progressPercent = 0.05f
        )
        val watchdog = Runnable {
            val timeoutStatus = "CAPTURE_TIMEOUT: Capture timeout. Preview recovered."
            status = timeoutStatus
            captureProgress = parseCaptureProgress(timeoutStatus, captureProgress)
            isCapturing = false
            previewEnabled = true
        }
        mainHandler.postDelayed(watchdog, timeoutMillis)

        fun finishIfTerminal(newStatus: String) {
            captureProgress = parseCaptureProgress(newStatus, captureProgress)
            if (isTerminalStatus(newStatus)) {
                mainHandler.removeCallbacks(watchdog)
                isCapturing = false
                refreshLatestResult()

                mainHandler.postDelayed(
                    { previewEnabled = true },
                    250L
                )
            }
        }

        mainHandler.postDelayed(
            {
                job { newStatus ->
                    status = newStatus
                    finishIfTerminal(newStatus)
                }
            },
            250L
        )
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
                        options = cameraState.options,
                        lensSlot = lensSlot,
                        selectedThreeXSource = selectedThreeXSource,
                        zoomUiState = zoomUiState
                    )
                    selectedLensSlot = lensSlot
                    zoomUiState = result.zoomUiState
                    showZoomSlider = false
                    result.forcedResolution?.let { selectedResolution = it }
                    status = result.status
                },
                selectedThreeXSource = selectedThreeXSource,
                onThreeXSourceChange = { source ->
                    val result = handleThreeXSourceChange(
                        context = context,
                        options = cameraState.options,
                        source = source,
                        zoomUiState = zoomUiState
                    )
                    selectedThreeXSource = source
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
                captureProgress = captureProgress,
                onHideFocusAeControls = {
                    showFocusAeControls = false
                    showZoomSlider = false
                },
                onCapture = captureClick@{
                    val clickResult = handleCaptureClick(
                        CaptureClickInput(
                            context = context,
                            selectedResolution = selectedResolution,
                            resolutionPlans = resolutionState.plans,
                            selectedResolutionPlan = resolutionState.selectedPlan,
                            buildPreparationInput = { activePlan ->
                                CapturePreparationInput(
                                    options = cameraState.options,
                                    resolutionPlan = activePlan,
                                    selectedResolution = selectedResolution,
                                    selectedLensSlot = selectedLensSlot,
                                    selectedThreeXSource = selectedThreeXSource,
                                    zoomUiState = zoomUiState,
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
                            ) { callback ->
                                startCapturePipeline(
                                    CapturePipelineRequest(
                                        context = context,
                                        pipelineMode = pipelineMode,
                                        prepared = clickResult.prepared,
                                        selectedResolution = selectedResolution,
                                        resolutionPlan = clickResult.resolutionPlan,
                                        finalOutputFormat = finalOutputFormat,
                                        focusAeState = focusAeState
                                    ),
                                    onStatus = callback
                                )
                            }
                        }
                    }
                },
                onAverage = {
                    status = "최근 촬영 컬러 합성 시작..."
                    processLatestNightFusionV02(context) { newStatus ->
                        status = newStatus
                        if (
                            isTerminalStatus(newStatus) ||
                            newStatus.contains("complete", ignoreCase = true) ||
                            newStatus.contains("failed", ignoreCase = true)
                        ) {
                            refreshLatestResult()
                        }
                    }
                },
                onRaw = {
                    runCameraJob("RAW DNG 촬영 준비 중...", requestedFrames = 1) { callback ->
                        captureSingleRawDng(
                            context = context,
                            cameraId = LEGACY_DEBUG_CAMERA_ID,
                            onStatus = callback
                        )
                    }
                },
                onRawBurst = {
                    runCameraJob("RAW Burst 촬영 준비 중...", requestedFrames = 4) { callback ->
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
                    refreshLatestResult()
                    status = shortStatus(latestSummary)
                }
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
                    runCameraJob("RAW DNG capture preparing...") { callback ->
                        captureSingleRawDng(
                            context = context,
                            cameraId = LEGACY_DEBUG_CAMERA_ID,
                            onStatus = callback
                        )
                    }
                },
                onRawBurst = {
                    currentScreen = MainScreen.CAMERA
                    runCameraJob("RAW Burst capture preparing...") { callback ->
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
                    runCameraJob("Test 50M RAW Capture: cameraId=${mainSelection.cameraId}", requestedFrames = 1) { callback ->
                        captureRawBurstForFusion(
                            context = context,
                            cameraId = mainSelection.cameraId,
                            frameCount = 1,
                            resolutionMode = CaptureResolutionMode.MP50,
                            zoomRatio = 1.0f,
                            focusAeState = focusAeState,
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
                    ) { callback ->
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
                    Log.i("KeplerCameraCapabilities", report)
                    status = report
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

                ShutterButton(
                    enabled = !isCapturing,
                    isCapturing = isCapturing,
                    onClick = onCapture
                )

                Spacer(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onHideFocusAeControls)
                )

                CameraSwitchButton(
                    enabled = !isCapturing,
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

            if (isCapturing) {
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
                    enabled = !isCapturing,
                    onClick = onRaw
                )

                MiniActionButton(
                    modifier = Modifier.weight(1f),
                    text = "RAW 4장",
                    enabled = !isCapturing,
                    onClick = onRawBurst
                )

                MiniActionButton(
                    modifier = Modifier.weight(1f),
                    text = "삭제",
                    enabled = !isCapturing,
                    onClick = onClear
                )
            }
        }
    }
}

@Composable
fun CaptureProgressRow(
    captureProgress: CaptureProgressState
) {
    val stageText = when (captureProgress.stage) {
        CaptureStage.IDLE -> "Ready"
        CaptureStage.PREPARING -> "Preparing"
        CaptureStage.CAPTURING -> "Capturing"
        CaptureStage.PROCESSING -> "Processing"
        CaptureStage.DEMOSAICING -> "Demosaicing"
        CaptureStage.EXPORTING -> "Exporting"
        CaptureStage.VERIFYING -> "Verifying"
        CaptureStage.CLEANING -> "Cleaning"
        CaptureStage.COMPLETE -> "Complete"
        CaptureStage.FAILED -> "Failed"
        CaptureStage.TIMEOUT -> "Timeout"
    }
    val frameText = if (captureProgress.requestedFrames > 0) {
        "${captureProgress.savedFrames} / ${captureProgress.requestedFrames}"
    } else {
        ""
    }
    val detailText = buildString {
        if (captureProgress.receivedImages > 0 || captureProgress.completedResults > 0) {
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
                    .clickable { onSelect(value) },
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
        ThreeXSourceDot(
            selected = selected == ThreeXSourceMode.OPTICAL,
            onClick = { onSelect(ThreeXSourceMode.OPTICAL) }
        )

        ThreeXSourceDot(
            selected = selected == ThreeXSourceMode.MAIN_CROP,
            onClick = { onSelect(ThreeXSourceMode.MAIN_CROP) }
        )
    }
}

@Composable
fun ThreeXSourceDot(
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.28f))
            .clickable(onClick = onClick)
    )
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
                    onPipelineModeChange = onPipelineModeChange
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
    onPipelineModeChange: (PipelineMode) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Pipeline",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrameModeChip(
                text = "RAW Night",
                selected = pipelineMode == PipelineMode.RAW_NIGHT_FUSION,
                onClick = { onPipelineModeChange(PipelineMode.RAW_NIGHT_FUSION) }
            )
            FrameModeChip(
                text = "YUV Night",
                selected = pipelineMode == PipelineMode.YUV_NIGHT_FUSION,
                onClick = { onPipelineModeChange(PipelineMode.YUV_NIGHT_FUSION) }
            )
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

        val colorRoot = File(picturesDir, "KeplerColorBurst")

        if (!colorRoot.exists()) {
            return LatestKeplerResult(
                bitmap = null,
                summary = "KeplerColorBurst 폴더가 없음"
            )
        }

        val latestJobDir = colorRoot
            .listFiles()
            ?.filter { it.isDirectory && File(it, "job.json").exists() }
            ?.maxByOrNull { it.lastModified() }
            ?: return LatestKeplerResult(
                bitmap = null,
                summary = "최근 Color Fusion job 없음"
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

        val latestJobDir = listOf(
            File(picturesDir, "KeplerColorBurst"),
            File(picturesDir, "KeplerRawFusion"),
            File(picturesDir, "KeplerSuperRes")
        )
            .flatMap { root ->
                root.listFiles()
                    ?.filter { it.isDirectory && File(it, "job.json").exists() }
                    .orEmpty()
            }
            .maxByOrNull { it.lastModified() }
            ?: return LatestKeplerResult(null, "No Kepler jobs found")

        val job = JSONObject(File(latestJobDir, "job.json").readText())
        val firstFrameName = job.optJSONArray("frames")
            ?.optJSONObject(0)
            ?.optString("file")
            .orEmpty()
        val previewName = listOf(
            job.optString("finalNightFusionFile", ""),
            job.optString("finalFile", ""),
            job.optString("averageColorFile", ""),
            firstFrameName
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val bitmap = previewName.takeIf { it.isNotBlank() }
            ?.let { File(latestJobDir, it) }
            ?.takeIf { it.exists() }
            ?.let { decodeLatestResultPreview(it) }

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
        }
        LatestKeplerResult(bitmap, summary)
    } catch (e: Exception) {
        LatestKeplerResult(null, "Latest result load failed: ${e.javaClass.simpleName}")
    }
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
