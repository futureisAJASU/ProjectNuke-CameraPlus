package com.projectnuke.keplernightlab

import android.content.Context

internal fun lensSlotForZoomRatioHysteresis(
    zoomRatio: Float,
    current: LensSlot,
    useOpticalTeleAt3x: Boolean
): LensSlot {
    return when (current) {
        LensSlot.ULTRAWIDE ->
            if (zoomRatio > 0.85f) LensSlot.MAIN_1X else LensSlot.ULTRAWIDE
        LensSlot.MAIN_1X -> when {
            zoomRatio < 0.75f -> LensSlot.ULTRAWIDE
            zoomRatio >= 1.5f -> LensSlot.MAIN_2X
            else -> LensSlot.MAIN_1X
        }
        LensSlot.MAIN_2X -> when {
            zoomRatio < 1.35f -> LensSlot.MAIN_1X
            zoomRatio >= 2.9f -> LensSlot.THREE_X
            else -> LensSlot.MAIN_2X
        }
        LensSlot.THREE_X ->
            if (zoomRatio < 2.7f) LensSlot.MAIN_2X else LensSlot.THREE_X
    }
}

internal fun cameraSelectionStatus(selection: CameraSelection): String {
    val source = when (selection.actualLensSource) {
        ActualLensSource.OPTICAL_TELE_LOGICAL -> "3x optical tele"
        ActualLensSource.OPTICAL_TELE_PHYSICAL -> "3x physical optical tele"
        ActualLensSource.OPTICAL_TELE_UNAVAILABLE_FALLBACK_CROP -> "3x crop fallback"
        ActualLensSource.MAIN_CROP_3X -> "3x main crop"
        ActualLensSource.MAIN_CROP_2X -> "2x main crop"
        ActualLensSource.ULTRAWIDE -> "ultrawide"
        ActualLensSource.MAIN_1X -> "main 1x"
    }
    return "$source: cameraId=${selection.cameraId}" +
        (selection.physicalCameraId?.let { ", physicalCameraId=$it" } ?: "") +
        ", zoom=${selection.effectiveZoomRatio}, crop=${selection.useCrop}. " +
        selection.diagnosticReason
}

internal fun handleResolutionClick(
    selectedResolution: CaptureResolutionMode,
    allowedResolutionModes: List<CaptureResolutionMode>,
    resolutionCapability: CameraResolutionCapability?,
    resolutionPlans: List<ResolutionCapturePlan>,
    selectedLensSlot: LensSlot
): ResolutionClickResult {
    val modes = allowedResolutionModes.ifEmpty { listOf(CaptureResolutionMode.MP12) }
    val capabilityText = resolutionCapability?.let {
        "${selectedLensSlot.label} cameraId=${it.cameraId} raw50=${it.raw50Available} " +
            "yuv50=${it.yuv50Available} jpeg50=${it.jpeg50Available} " +
            "maxRAW=${"%.1f".format(it.maxAvailableRawMp)}MP " +
            "modes=${modes.joinToString { mode -> mode.label }}"
    } ?: "Resolution capability unavailable."
    if (modes.size <= 1) {
        val reason = resolutionPlans
            .firstOrNull { it.requestedMode == CaptureResolutionMode.MP50 && !it.isAvailable }
            ?.reason
            ?: resolutionPlans
                .firstOrNull {
                    it.requestedMode != CaptureResolutionMode.MP12 && !it.isAvailable
                }
                ?.reason
            ?: "no 50MP RAW/YUV/JPEG stream exposed for selected main camera."
        return ResolutionClickResult(
            CaptureResolutionMode.MP12,
            "Only 12M: $reason $capabilityText"
        )
    }
    val index = modes.indexOf(selectedResolution).coerceAtLeast(0)
    val next = modes[(index + 1) % modes.size]
    return ResolutionClickResult(next, "Resolution: ${next.label}. $capabilityText")
}

internal fun handleLensSlotChange(
    context: Context,
    options: SelectedCaptureOptions,
    lensSlot: LensSlot,
    selectedThreeXSource: ThreeXSourceMode,
    zoomUiState: ZoomUiState
): LensChangeResult {
    val clampedZoom =
        lensSlot.targetZoomRatio.coerceIn(zoomUiState.minZoom, zoomUiState.maxZoom)
    val updatedZoomState = zoomUiState.copy(
        zoomRatio = clampedZoom,
        lensSlot = lensSlot,
        useOpticalTeleAt3x = selectedThreeXSource == ThreeXSourceMode.OPTICAL
    )
    val forcedResolution = if (
        lensSlot == LensSlot.ULTRAWIDE ||
        (lensSlot == LensSlot.THREE_X && selectedThreeXSource == ThreeXSourceMode.OPTICAL)
    ) {
        CaptureResolutionMode.MP12
    } else {
        null
    }
    val selection = selectCameraForOptions(
        context,
        options.copy(
            lensSlot = lensSlot,
            threeXSourceMode = selectedThreeXSource
        )
    )
    return LensChangeResult(
        updatedZoomState,
        forcedResolution,
        cameraSelectionStatus(selection)
    )
}

internal fun handleThreeXSourceChange(
    context: Context,
    options: SelectedCaptureOptions,
    source: ThreeXSourceMode,
    zoomUiState: ZoomUiState
): LensChangeResult {
    val targetZoom = LensSlot.THREE_X.targetZoomRatio
        .coerceIn(zoomUiState.minZoom, zoomUiState.maxZoom)
    val updatedZoomState = zoomUiState.copy(
        zoomRatio = targetZoom,
        lensSlot = LensSlot.THREE_X,
        useOpticalTeleAt3x = source == ThreeXSourceMode.OPTICAL
    )
    val selection = selectCameraForOptions(
        context,
        options.copy(
            lensSlot = LensSlot.THREE_X,
            threeXSourceMode = source
        )
    )
    return LensChangeResult(
        updatedZoomState,
        CaptureResolutionMode.MP12.takeIf { source == ThreeXSourceMode.OPTICAL },
        cameraSelectionStatus(selection)
    )
}

internal fun calculateCaptureZoomRatio(
    selection: CameraSelection,
    selectedLensSlot: LensSlot,
    selectedThreeXSource: ThreeXSourceMode,
    zoomUiState: ZoomUiState
): Float {
    return if (
        selectedLensSlot == LensSlot.THREE_X &&
        selectedThreeXSource == ThreeXSourceMode.OPTICAL &&
        !selection.useCrop
    ) {
        1.0f
    } else {
        zoomUiState.zoomRatio.coerceIn(zoomUiState.minZoom, zoomUiState.maxZoom)
    }
}

internal fun buildCaptureStartMessage(
    selectedResolution: CaptureResolutionMode,
    selection: CameraSelection,
    resolutionPlan: ResolutionCapturePlan,
    settings: FrameCountSettings,
    framePlan: FramePlan
): String {
    return "Night Fusion ${selectedResolution.label}. ${cameraSelectionStatus(selection)} " +
        "${resolutionPlan.reason} Frame mode: ${settings.mode.label}, " +
        "capture frames: ${framePlan.framesToCapture}, auto max: ${framePlan.maxFrames}. " +
        "${framePlan.reason}."
}

internal fun prepareCaptureAttempt(
    context: Context,
    input: CapturePreparationInput
): PreparedCaptureAttempt {
    val settings = currentFrameCountSettings(
        mode = input.frameCountMode,
        autoMinFrames = input.autoMinFrames,
        autoMaxFrames = input.autoMaxFrames,
        manualFrames = input.manualFrames
    )
    val framePlan = estimateFramePlan(
        settings = settings,
        selectedModeLabel = input.selectedMode,
        latestSceneLuma = input.latestSceneLuma,
        latestMotionScore = input.latestMotionScore
    )
    val selection = selectCameraForOptions(context, input.options)
    val captureZoomRatio = calculateCaptureZoomRatio(
        selection,
        input.selectedLensSlot,
        input.selectedThreeXSource,
        input.zoomUiState
    )
    return PreparedCaptureAttempt(
        settings = settings,
        framePlan = framePlan,
        selection = selection,
        captureZoomRatio = captureZoomRatio,
        startMessage = buildCaptureStartMessage(
            input.selectedResolution,
            selection,
            input.resolutionPlan,
            settings,
            framePlan
        )
    )
}

internal fun handleCaptureClick(input: CaptureClickInput): CaptureClickResult {
    val activePlan = input.selectedResolutionPlan
    if (activePlan == null) {
        val reason = input.resolutionPlans
            .firstOrNull { it.requestedMode == input.selectedResolution }
            ?.reason
            ?: "Selected resolution has no valid capture plan."
        return CaptureClickResult.InvalidResolution("Resolution changed to 12M: $reason")
    }
    return CaptureClickResult.Ready(
        resolutionPlan = activePlan,
        prepared = prepareCaptureAttempt(input.context, input.buildPreparationInput(activePlan))
    )
}

internal fun startCapturePipeline(
    request: CapturePipelineRequest,
    onStatus: (String) -> Unit
) {
    if (request.selectedResolution == CaptureResolutionMode.MP24_FUSION) {
        captureProcessExportSuperResolutionFusion(
            context = request.context,
            cameraId = request.prepared.selection.cameraId,
            frameCount = request.prepared.framePlan.framesToCapture,
            finalOutputFormat = request.finalOutputFormat,
            zoomRatio = request.prepared.captureZoomRatio,
            focusAeState = request.focusAeState,
            frameCountMode = request.prepared.settings.mode,
            autoMinFrames = request.prepared.settings.autoMinFrames,
            autoMaxFrames = request.prepared.settings.autoMaxFrames,
            manualFrames = request.prepared.settings.manualFrames,
            framePlanReason = request.prepared.framePlan.reason,
            onStatus = onStatus
        )
    } else if (request.pipelineMode == PipelineMode.RAW_NIGHT_FUSION) {
        captureProcessExportRawNightFusion(
            context = request.context,
            cameraId = request.prepared.selection.cameraId,
            frameCount = request.prepared.framePlan.framesToCapture,
            resolutionMode = request.selectedResolution,
            resolutionPlan = request.resolutionPlan,
            finalOutputFormat = request.finalOutputFormat,
            zoomRatio = request.prepared.captureZoomRatio,
            focusAeState = request.focusAeState,
            onStatus = onStatus
        )
    } else {
        captureProcessExportNightFusion(
            context = request.context,
            cameraId = request.prepared.selection.cameraId,
            frameCount = request.prepared.framePlan.framesToCapture,
            resolutionMode = request.selectedResolution,
            finalOutputFormat = request.finalOutputFormat,
            zoomRatio = request.prepared.captureZoomRatio,
            focusAeState = request.focusAeState,
            cleanupPolicy = CacheCleanupPolicy.DELETE_SOURCE_FRAMES_AFTER_VERIFIED_EXPORT,
            frameCountMode = request.prepared.settings.mode,
            autoMinFrames = request.prepared.settings.autoMinFrames,
            autoMaxFrames = request.prepared.settings.autoMaxFrames,
            manualFrames = request.prepared.settings.manualFrames,
            framePlanReason = request.prepared.framePlan.reason,
            onStatus = onStatus
        )
    }
}
