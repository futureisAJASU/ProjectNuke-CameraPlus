package com.projectnuke.keplernightlab

import android.content.Context
import android.util.Log

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
    lensSlot: LensSlot,
    selectedResolution: CaptureResolutionMode,
    selectedThreeXSource: ThreeXSourceMode,
    zoomUiState: ZoomUiState
): LensChangeResult {
    val enteringThreeXFromOtherSlot = lensSlot == LensSlot.THREE_X && zoomUiState.lensSlot != LensSlot.THREE_X
    val effectiveThreeXSource = if (enteringThreeXFromOtherSlot) {
        ThreeXSourceMode.OPTICAL
    } else {
        selectedThreeXSource
    }
    val clampedZoom =
        lensSlot.targetZoomRatio.coerceIn(zoomUiState.minZoom, zoomUiState.maxZoom)
    val updatedZoomState = zoomUiState.copy(
        zoomRatio = clampedZoom,
        lensSlot = lensSlot,
        useOpticalTeleAt3x = effectiveThreeXSource == ThreeXSourceMode.OPTICAL
    )
    val forcedResolution = if (
        lensSlot == LensSlot.ULTRAWIDE ||
        (lensSlot == LensSlot.THREE_X && effectiveThreeXSource == ThreeXSourceMode.OPTICAL)
    ) {
        CaptureResolutionMode.MP12
    } else {
        null
    }
    val selectionOptions = SelectedCaptureOptions(
        lensSlot = lensSlot,
        resolutionMode = forcedResolution ?: selectedResolution,
        threeXSourceMode = effectiveThreeXSource
    )
    val selection = selectCameraForOptions(context, selectionOptions)
    logThreeXTransition("lens", lensSlot, effectiveThreeXSource, updatedZoomState, selection)
    return LensChangeResult(
        lensSlot = lensSlot,
        threeXSource = effectiveThreeXSource,
        zoomUiState = updatedZoomState,
        forcedResolution = forcedResolution,
        cameraSelection = selection,
        status = cameraSelectionStatus(selection)
    )
}

internal fun handleThreeXSourceChange(
    context: Context,
    source: ThreeXSourceMode,
    previousThreeXSource: ThreeXSourceMode,
    selectedResolution: CaptureResolutionMode,
    zoomUiState: ZoomUiState
): LensChangeResult {
    val targetZoom = LensSlot.THREE_X.targetZoomRatio
        .coerceIn(zoomUiState.minZoom, zoomUiState.maxZoom)
    val updatedZoomState = zoomUiState.copy(
        zoomRatio = targetZoom,
        lensSlot = LensSlot.THREE_X,
        useOpticalTeleAt3x = source == ThreeXSourceMode.OPTICAL
    )
    val forcedResolution = CaptureResolutionMode.MP12
        .takeIf { source == ThreeXSourceMode.OPTICAL }
    val selectionOptions = SelectedCaptureOptions(
        lensSlot = LensSlot.THREE_X,
        resolutionMode = forcedResolution ?: selectedResolution,
        threeXSourceMode = source
    )
    val selection = selectCameraForOptions(context, selectionOptions)
    Log.d(
        "Kepler3xSelection",
        "phase=stateResult requestedSource=$source previousSource=$previousThreeXSource " +
            "newSource=$source selectedLensSlot=${LensSlot.THREE_X} " +
            "zoom=${updatedZoomState.zoomRatio} optical=${updatedZoomState.useOpticalTeleAt3x} " +
            "cameraId=${selection.cameraId} actual=${selection.actualLensSource} " +
            "physicalCameraId=${selection.physicalCameraId} " +
            "effectiveZoom=${selection.effectiveZoomRatio} useCrop=${selection.useCrop} " +
            "previewZoom=${selection.effectiveZoomRatio} captureZoom=${selection.effectiveZoomRatio}"
    )
    return LensChangeResult(
        lensSlot = LensSlot.THREE_X,
        threeXSource = source,
        zoomUiState = updatedZoomState,
        forcedResolution = forcedResolution,
        cameraSelection = selection,
        status = cameraSelectionStatus(selection)
    )
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
    val selection = input.cameraSelection
    val captureZoomRatio = input.captureZoomRatio
    if (selection.requestedLensSlot == LensSlot.THREE_X) {
        Log.d(
            "Kepler3xSelection",
            "phase=capture lens=${selection.requestedLensSlot} " +
                "source=${selection.requestedThreeXSourceMode} " +
                "cameraId=${selection.cameraId} captureZoom=$captureZoomRatio " +
                "actual=${selection.actualLensSource} " +
                "physicalCameraId=${selection.physicalCameraId} " +
                "physicalTele=${selection.isOpticalTeleActuallyUsed && !selection.useCrop} " +
                "mainCrop=${selection.actualLensSource == ActualLensSource.MAIN_CROP_3X}"
        )
    }
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

private fun logThreeXTransition(
    phase: String,
    lensSlot: LensSlot,
    source: ThreeXSourceMode,
    zoomUiState: ZoomUiState,
    selection: CameraSelection
) {
    if (lensSlot != LensSlot.THREE_X) return
    Log.d(
        "Kepler3xSelection",
        "phase=$phase lens=$lensSlot source=$source " +
            "zoom=${zoomUiState.zoomRatio} optical=${zoomUiState.useOpticalTeleAt3x} " +
            "cameraId=${selection.cameraId} actual=${selection.actualLensSource} " +
            "physicalCameraId=${selection.physicalCameraId} " +
            "effectiveZoom=${selection.effectiveZoomRatio} useCrop=${selection.useCrop} " +
            "previewZoom=${selection.effectiveZoomRatio} captureZoom=${selection.effectiveZoomRatio} " +
            "physicalTele=${selection.isOpticalTeleActuallyUsed && !selection.useCrop} " +
            "mainCrop=${selection.actualLensSource == ActualLensSource.MAIN_CROP_3X}"
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
    val loggedStatus: (String) -> Unit = { newStatus ->
        newStatus.chunked(3000).forEachIndexed { index, chunk ->
            Log.i("KeplerCaptureStatus", "part=${index + 1}: $chunk")
        }
        onStatus(newStatus)
    }
    val selection = request.prepared.selection
    val shouldDisablePhysicalStillRouting = selection.actualLensSource == ActualLensSource.OPTICAL_TELE_PHYSICAL
    val physicalCameraId: String? = null
    val captureZoomRatio = if (shouldDisablePhysicalStillRouting) {
        3.0f
    } else {
        request.prepared.captureZoomRatio
    }
    Log.i(
        "KeplerPhysicalRoute",
        "capturePipeline selectedResolution=${request.selectedResolution} " +
            "pipelineMode=${request.pipelineMode} cameraId=${selection.cameraId} " +
            "actual=${selection.actualLensSource} " +
            "requestedPhysicalCameraId=${selection.physicalCameraId} " +
            "routedPhysicalCameraId=$physicalCameraId " +
            "captureZoom=$captureZoomRatio " +
            "physicalStillRoutingDisabled=$shouldDisablePhysicalStillRouting"
    )
    if (shouldDisablePhysicalStillRouting) {
        Log.w(
            "KeplerPhysicalRoute",
            "capture physical routing disabled; using logical 3x crop fallback " +
                "cameraId=${selection.cameraId} requestedPhysicalCameraId=${selection.physicalCameraId}"
        )
    }
    if (request.selectedResolution == CaptureResolutionMode.MP24_FUSION) {
        captureProcessExportSuperResolutionFusion(
            context = request.context,
            cameraId = selection.cameraId,
            frameCount = request.prepared.framePlan.framesToCapture,
            finalOutputFormat = request.finalOutputFormat,
            zoomRatio = captureZoomRatio,
            physicalCameraId = physicalCameraId,
            focusAeState = request.focusAeState,
            frameCountMode = request.prepared.settings.mode,
            autoMinFrames = request.prepared.settings.autoMinFrames,
            autoMaxFrames = request.prepared.settings.autoMaxFrames,
            manualFrames = request.prepared.settings.manualFrames,
            framePlanReason = request.prepared.framePlan.reason,
            onStatus = loggedStatus
        )
    } else if (request.pipelineMode == PipelineMode.RAW_NIGHT_FUSION) {
        captureProcessExportRawNightFusion(
            context = request.context,
            cameraId = selection.cameraId,
            frameCount = if (request.rawSpeedMode == RawSpeedMode.BALANCED) {
                request.prepared.framePlan.framesToCapture.coerceAtMost(4)
            } else {
                request.prepared.framePlan.framesToCapture
            },
            resolutionMode = request.selectedResolution,
            resolutionPlan = request.resolutionPlan,
            finalOutputFormat = request.finalOutputFormat,
            zoomRatio = captureZoomRatio,
            physicalCameraId = physicalCameraId,
            focusAeState = request.focusAeState,
            rawSpeedMode = request.rawSpeedMode,
            onStatus = loggedStatus
        )
    } else {
        captureProcessExportNightFusion(
            context = request.context,
            cameraId = selection.cameraId,
            frameCount = request.prepared.framePlan.framesToCapture,
            resolutionMode = request.selectedResolution,
            finalOutputFormat = request.finalOutputFormat,
            zoomRatio = captureZoomRatio,
            physicalCameraId = physicalCameraId,
            focusAeState = request.focusAeState,
            cleanupPolicy = CacheCleanupPolicy.KEEP_ALL,
            frameCountMode = request.prepared.settings.mode,
            autoMinFrames = request.prepared.settings.autoMinFrames,
            autoMaxFrames = request.prepared.settings.autoMaxFrames,
            manualFrames = request.prepared.settings.manualFrames,
            framePlanReason = request.prepared.framePlan.reason,
            onStatus = loggedStatus
        )
    }
}
