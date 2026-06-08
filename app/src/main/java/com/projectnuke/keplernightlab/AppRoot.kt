package com.projectnuke.keplernightlab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

data class LatestKeplerResult(
    val bitmap: Bitmap?,
    val summary: String
)

private val KeplerDarkScheme = darkColorScheme(
    background = Color.Black,
    surface = Color(0xFF0B0C10),
    primary = Color.White,
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun KeplerAppRoot() {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var showDebug by remember { mutableStateOf(false) }
    var showCacheJobs by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    MaterialTheme(colorScheme = KeplerDarkScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            when {
                !hasCameraPermission -> {
                    PermissionScreen(
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    )
                }

                showDebug -> {
                    DebugScreenWrapper(
                        onBack = { showDebug = false }
                    )
                }

                showCacheJobs -> {
                    CacheJobsScreen(
                        onBack = { showCacheJobs = false }
                    )
                }

                else -> {
                    MainCameraScreen(
                        onOpenDebug = { showDebug = true },
                        onOpenCacheJobs = { showCacheJobs = true }
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionScreen(
    onRequestPermission: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Kepler Camera",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium
            )

            Text(
                text = "카메라 기능을 사용하려면 권한이 필요합니다.",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyLarge
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRequestPermission
            ) {
                Text("카메라 권한 허용")
            }
        }
    }
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
    var showSettings by remember { mutableStateOf(false) }

    var selectedMode by remember { mutableStateOf("사진") }
    var selectedResolution by remember { mutableStateOf(CaptureResolutionMode.MP12) }
    var selectedLensSlot by remember { mutableStateOf(LensSlot.MAIN_1X) }
    var selectedThreeXSource by remember { mutableStateOf(ThreeXSourceMode.OPTICAL) }
    var pipelineMode by remember { mutableStateOf(PipelineMode.RAW_NIGHT_FUSION) }
    var focusAeState by remember { mutableStateOf(FocusAeState()) }
    var overlaySettings by remember {
        mutableStateOf(UiOverlaySettingsStore.load(context))
    }
    var frameCountMode by remember { mutableStateOf(FrameCountMode.AUTO) }
    var autoMinFrames by remember { mutableStateOf(4) }
    var autoMaxFrames by remember { mutableStateOf(8) }
    var manualFrames by remember { mutableStateOf(4) }
    var latestSceneLuma by remember { mutableStateOf<Double?>(null) }
    var latestMotionScore by remember { mutableStateOf<Double?>(null) }
    var latestFramePlan by remember {
        mutableStateOf(
            FramePlan(
                framesToCapture = 4,
                maxFrames = 8,
                reason = "Default"
            )
        )
    }

    val options = SelectedCaptureOptions(
        lensSlot = selectedLensSlot,
        resolutionMode = selectedResolution,
        threeXSourceMode = selectedThreeXSource
    )
    val cameraSelection = remember(selectedLensSlot, selectedResolution, selectedThreeXSource) {
        selectCameraForOptions(context, options)
    }
    val previewZoomRatio = if (cameraSelection.useCrop) {
        cameraSelection.effectiveZoomRatio
    } else {
        1.0f
    }
    val levelState = rememberDeviceLevelState(enabled = overlaySettings.showLevel)

    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var latestSummary by remember { mutableStateOf("최근 결과 없음") }

    fun refreshLatestResult() {
        val result = loadLatestKeplerResult(context)
        latestBitmap = result.bitmap
        latestSummary = result.summary
        val estimate = estimateLatestColorBurstScene(context)
        latestSceneLuma = estimate.meanLuma
        latestMotionScore = estimate.motionScore
    }

    LaunchedEffect(Unit) {
        refreshLatestResult()
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

    fun isTerminalStatus(text: String): Boolean {
        return text.contains("완료") ||
                text.contains("실패") ||
                text.contains("오류") ||
                text.contains("연결 해제")
    }

    fun shortStatus(text: String): String {
        return text.lineSequence().firstOrNull()?.trim()?.take(42) ?: "대기 중"
    }

    fun runCameraJob(
        startMessage: String,
        job: ((String) -> Unit) -> Unit
    ) {
        status = startMessage
        isCapturing = true
        previewEnabled = false

        mainHandler.postDelayed(
            {
                job { newStatus ->
                    status = newStatus

                    if (isTerminalStatus(newStatus)) {
                        isCapturing = false
                        refreshLatestResult()

                        mainHandler.postDelayed(
                            { previewEnabled = true },
                            250L
                        )
                    }
                    if (newStatus.contains("Saved to Gallery", ignoreCase = true)) {
                        isCapturing = false
                        refreshLatestResult()
                        mainHandler.postDelayed(
                            { previewEnabled = true },
                            250L
                        )
                    }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            CameraTopBar(
                status = shortStatus(status),
                selectedResolution = selectedResolution,
                onResolutionClick = {
                    val next = when (selectedResolution) {
                        CaptureResolutionMode.MP12 -> CaptureResolutionMode.MP24_FUSION
                        CaptureResolutionMode.MP24_FUSION -> CaptureResolutionMode.MP50
                        CaptureResolutionMode.MP50 -> CaptureResolutionMode.MP12
                    }
                    selectedResolution = next
                    status = "Resolution: ${next.label}"
                },
                onSettings = {
                    showSettings = true
                }
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val x = (offset.x / size.width).coerceIn(0f, 1f)
                            val y = (offset.y / size.height).coerceIn(0f, 1f)
                            focusAeState = focusAeState.copy(
                                point = NormalizedPoint(x, y),
                                locked = false
                            )
                            status = "AF/AE point set"
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Camera2Preview(
                    modifier = Modifier.fillMaxSize(),
                    cameraId = cameraSelection.cameraId,
                    zoomRatio = previewZoomRatio,
                    focusAeState = focusAeState,
                    enabled = previewEnabled,
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
                    }
                )

                if (overlaySettings.showGrid) {
                    RuleOfThirdsGridOverlay(Modifier.fillMaxSize())
                }

                if (overlaySettings.showLevel) {
                    LevelIndicatorOverlay(
                        levelState = levelState,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                FocusAeOverlay(
                    focusAeState = focusAeState,
                    onToggleLock = {
                        val locked = !focusAeState.locked
                        focusAeState = focusAeState.copy(locked = locked)
                        status = if (locked) "AF/AE locked" else "AF/AE unlocked"
                    },
                    onExposureStep = { delta ->
                        val index = (focusAeState.exposureCompensationIndex + delta)
                            .coerceIn(focusAeState.supportedMinIndex, focusAeState.supportedMaxIndex)
                        focusAeState = focusAeState.copy(
                            exposureCompensationIndex = index,
                            exposureCompensationEv = index * focusAeState.exposureStepEv
                        )
                        status = "EV ${"%.1f".format(index * focusAeState.exposureStepEv)}"
                    }
                )

                if (!previewEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x66000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isCapturing) "Capturing..." else "Preview paused",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }

            CameraBottomPanel(
                latestBitmap = latestBitmap,
                selectedLensSlot = selectedLensSlot,
                onLensSlotChange = { lensSlot ->
                    selectedLensSlot = lensSlot
                    val updatedSelection = selectCameraForOptions(
                        context,
                        options.copy(lensSlot = lensSlot)
                    )
                    status = "${lensSlot.label} selected: cameraId=${updatedSelection.cameraId}, zoom=${updatedSelection.effectiveZoomRatio}, crop=${updatedSelection.useCrop}. ${updatedSelection.note}\n${buildCameraSelectionDebugReport(context)}"
                },
                selectedThreeXSource = selectedThreeXSource,
                onThreeXSourceChange = { source ->
                    selectedThreeXSource = source
                    val updatedOptions = options.copy(
                        lensSlot = LensSlot.THREE_X,
                        threeXSourceMode = source
                    )
                    val updatedSelection = selectCameraForOptions(context, updatedOptions)
                    status = "3x ${source.label}: cameraId=${updatedSelection.cameraId}, zoom=${updatedSelection.effectiveZoomRatio}, crop=${updatedSelection.useCrop}, ${updatedSelection.note}\n${buildCameraSelectionDebugReport(context)}"
                },
                frameCountMode = frameCountMode,
                latestFramePlan = latestFramePlan,
                pipelineMode = pipelineMode,
                selectedMode = selectedMode,
                onModeChange = { selectedMode = it },
                isCapturing = isCapturing,
                onCapture = {
                    val settings = currentFrameCountSettings(
                        mode = frameCountMode,
                        autoMinFrames = autoMinFrames,
                        autoMaxFrames = autoMaxFrames,
                        manualFrames = manualFrames
                    )
                    val plan = estimateFramePlan(
                        settings = settings,
                        selectedModeLabel = selectedMode,
                        latestSceneLuma = latestSceneLuma,
                        latestMotionScore = latestMotionScore
                    )
                    latestFramePlan = plan
                    val selection = selectCameraForOptions(context, options)
                    val captureZoomRatio = if (selection.useCrop) {
                        selection.effectiveZoomRatio
                    } else {
                        1.0f
                    }
                    runCameraJob(
                        "Night Fusion ${selectedLensSlot.label} ${selectedResolution.label} cameraId=${selection.cameraId}, zoom=${captureZoomRatio}x. Frame mode: ${settings.mode.label}, capture frames: ${plan.framesToCapture}, auto max: ${plan.maxFrames}. ${plan.reason}. ${selection.note}"
                    ) { callback ->
                        if (pipelineMode == PipelineMode.RAW_NIGHT_FUSION) {
                            captureProcessExportRawNightFusion(
                                context = context,
                                cameraId = selection.cameraId,
                                frameCount = plan.framesToCapture,
                                resolutionMode = selectedResolution,
                                zoomRatio = captureZoomRatio,
                                focusAeState = focusAeState,
                                cleanupPolicy = CacheCleanupPolicy.KEEP_ALL,
                                onStatus = callback
                            )
                        } else {
                            captureProcessExportNightFusion(
                                context = context,
                                cameraId = selection.cameraId,
                                frameCount = plan.framesToCapture,
                                resolutionMode = selectedResolution,
                                zoomRatio = captureZoomRatio,
                                focusAeState = focusAeState,
                                cleanupPolicy = CacheCleanupPolicy.DELETE_SOURCE_FRAMES_AFTER_VERIFIED_EXPORT,
                                frameCountMode = settings.mode,
                                autoMinFrames = settings.autoMinFrames,
                                autoMaxFrames = settings.autoMaxFrames,
                                manualFrames = settings.manualFrames,
                                framePlanReason = plan.reason,
                                onStatus = callback
                            )
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
                    runCameraJob("RAW DNG 촬영 준비 중...") { callback ->
                        captureSingleRawDng(
                            context = context,
                            cameraId = "0",
                            onStatus = callback
                        )
                    }
                },
                onRawBurst = {
                    runCameraJob("RAW Burst 촬영 준비 중...") { callback ->
                        captureRawBurstDng(
                            context = context,
                            cameraId = "0",
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
        }

        if (showSettings) {
            SettingsOverlay(
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
                    status = "AF/AE reset"
                    showSettings = false
                },
                onRaw = {
                    showSettings = false
                    runCameraJob("RAW DNG capture preparing...") { callback ->
                        captureSingleRawDng(
                            context = context,
                            cameraId = "0",
                            onStatus = callback
                        )
                    }
                },
                onRawBurst = {
                    showSettings = false
                    runCameraJob("RAW Burst capture preparing...") { callback ->
                        captureRawBurstDng(
                            context = context,
                            cameraId = "0",
                            frameCount = 4,
                            onStatus = callback
                        )
                    }
                },
                onDismiss = {
                    showSettings = false
                },
                onOpenDebug = {
                    showSettings = false
                    onOpenDebug()
                },
                onOpenCacheJobs = {
                    showSettings = false
                    onOpenCacheJobs()
                },
                onClearCache = {
                    val deleted = deleteKeplerCache(context)
                    latestBitmap = null
                    latestSummary = "최근 결과 없음"
                    status = "캐시 삭제 완료 ($deleted)"
                    showSettings = false
                }
            )
        }
    }
}

@Composable
fun CameraTopBar(
    status: String,
    selectedResolution: CaptureResolutionMode,
    onResolutionClick: () -> Unit,
    onSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(86.dp)
            .background(Color.Black)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleMiniButton(
                label = "⚙",
                onClick = onSettings
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopIcon("×")
                TopText(
                    text = selectedResolution.label,
                    onClick = onResolutionClick
                )
                TopIcon("◷")
                TopIcon("☾")
                TopIcon("☺")
            }
        }

        Text(
            text = status,
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun CircleMiniButton(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Color(0xFF1C1D24))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.14f),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
fun TopIcon(text: String) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.titleLarge
    )
}

@Composable
fun TopText(
    text: String,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.clickable(onClick = onClick)
    )
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
    latestBitmap: Bitmap?,
    selectedLensSlot: LensSlot,
    onLensSlotChange: (LensSlot) -> Unit,
    selectedThreeXSource: ThreeXSourceMode,
    onThreeXSourceChange: (ThreeXSourceMode) -> Unit,
    frameCountMode: FrameCountMode,
    latestFramePlan: FramePlan,
    pipelineMode: PipelineMode,
    selectedMode: String,
    onModeChange: (String) -> Unit,
    isCapturing: Boolean,
    onCapture: () -> Unit,
    onAverage: () -> Unit,
    onRaw: () -> Unit,
    onRawBurst: () -> Unit,
    onClear: () -> Unit,
    onThumbnail: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ResultThumbnail(
                bitmap = latestBitmap,
                onClick = onThumbnail
            )

            Spacer(modifier = Modifier.weight(1f))

            ShutterButton(
                enabled = !isCapturing,
                onClick = onCapture
            )

            Spacer(modifier = Modifier.weight(1f))

            CameraSwitchButton(
                enabled = !isCapturing,
                onClick = onAverage
            )
        }

        ModeTabs(
            selected = selectedMode,
            onSelect = onModeChange
        )

        if (false) {
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
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        values.forEach { value ->
            Box(
                modifier = Modifier
                    .size(width = 46.dp, height = 34.dp)
                    .clip(RoundedCornerShape(18.dp))
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
                    style = MaterialTheme.typography.titleMedium
                )
            }
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
fun ModeTabs(
    selected: String,
    onSelect: (String) -> Unit
) {
    val modes = listOf("인물 사진", "야간", "사진", "동영상", "더보기")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        modes.forEach { mode ->
            Text(
                text = mode,
                color = if (selected == mode) Color.White else Color.White.copy(alpha = 0.38f),
                style = if (selected == mode) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                modifier = Modifier.clickable { onSelect(mode) }
            )
        }
    }
}

@Composable
fun ShutterButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(92.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.16f else 0.07f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(74.dp)
                .clip(CircleShape)
                .background(if (enabled) Color.White else Color(0xFF74747A))
        )
    }
}

@Composable
fun CameraSwitchButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color(0xFF222229))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "↻",
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium
        )
    }
}

@Composable
fun ResultThumbnail(
    bitmap: Bitmap?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color(0xFF1A1A20))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.12f),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "최근 결과",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = "결과",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium
            )
        }
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
fun SettingsOverlay(
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
    overlaySettings: UiOverlaySettings,
    onOverlaySettingsChange: (UiOverlaySettings) -> Unit,
    onResetFocusAe: () -> Unit,
    onRaw: () -> Unit,
    onRawBurst: () -> Unit,
    onDismiss: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenCacheJobs: () -> Unit,
    onClearCache: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.TopStart
    ) {
        Surface(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 14.dp, top = 72.dp)
                .fillMaxWidth(0.72f)
                .clickable(onClick = {}),
            color = Color(0xEE11131B),
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
                    onClick = onDismiss
                )
            }
        }
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
