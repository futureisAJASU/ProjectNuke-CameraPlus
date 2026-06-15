package com.projectnuke.keplernightlab

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

data class ZoomUiState(
    val zoomRatio: Float = 1.0f,
    val minZoom: Float = 0.6f,
    val maxZoom: Float = 3.0f,
    val lensSlot: LensSlot = LensSlot.MAIN_1X,
    val useOpticalTeleAt3x: Boolean = true
)

internal data class ResolutionClickResult(
    val selectedResolution: CaptureResolutionMode,
    val status: String
)

internal data class LensChangeResult(
    val lensSlot: LensSlot,
    val threeXSource: ThreeXSourceMode,
    val zoomUiState: ZoomUiState,
    val forcedResolution: CaptureResolutionMode?,
    val cameraSelection: CameraSelection,
    val status: String
)

internal data class CapturePreparationInput(
    val cameraSelection: CameraSelection,
    val captureZoomRatio: Float,
    val resolutionPlan: ResolutionCapturePlan,
    val selectedResolution: CaptureResolutionMode,
    val frameCountMode: FrameCountMode,
    val autoMinFrames: Int,
    val autoMaxFrames: Int,
    val manualFrames: Int,
    val selectedMode: String,
    val latestSceneLuma: Double?,
    val latestMotionScore: Double?
)

internal data class PreparedCaptureAttempt(
    val settings: FrameCountSettings,
    val framePlan: FramePlan,
    val selection: CameraSelection,
    val captureZoomRatio: Float,
    val startMessage: String
)

internal data class CameraSelectionUiState(
    val options: SelectedCaptureOptions,
    val selection: CameraSelection,
    val previewZoomRatio: Float,
    val captureZoomRatio: Float
)

internal data class ResolutionUiState(
    val capability: CameraResolutionCapability?,
    val plans: List<ResolutionCapturePlan>,
    val allowedModes: List<CaptureResolutionMode>,
    val selectedPlan: ResolutionCapturePlan?
)

internal data class CaptureClickInput(
    val context: Context,
    val selectedResolution: CaptureResolutionMode,
    val resolutionPlans: List<ResolutionCapturePlan>,
    val selectedResolutionPlan: ResolutionCapturePlan?,
    val buildPreparationInput: (ResolutionCapturePlan) -> CapturePreparationInput
)

internal sealed interface CaptureClickResult {
    data class InvalidResolution(val status: String) : CaptureClickResult

    data class Ready(
        val resolutionPlan: ResolutionCapturePlan,
        val prepared: PreparedCaptureAttempt
    ) : CaptureClickResult
}

internal data class CapturePipelineRequest(
    val context: Context,
    val pipelineMode: PipelineMode,
    val prepared: PreparedCaptureAttempt,
    val selectedResolution: CaptureResolutionMode,
    val resolutionPlan: ResolutionCapturePlan,
    val finalOutputFormat: FinalOutputFormat,
    val focusAeState: FocusAeState
)

internal data class CameraPreviewPaneState(
    val cameraSelection: CameraSelection,
    val previewZoomRatio: Float,
    val focusAeState: FocusAeState,
    val previewEnabled: Boolean,
    val isCapturing: Boolean,
    val showFocusAeControls: Boolean,
    val overlaySettings: UiOverlaySettings,
    val levelState: DeviceLevelState
)

internal data class CameraPreviewPaneCallbacks(
    val onFocusPoint: (NormalizedPoint) -> Unit,
    val onAeCapabilitiesChanged: (Int, Int, Float) -> Unit,
    val onToggleFocusLock: () -> Unit,
    val onExposureStep: (Int) -> Unit
)

@Composable
internal fun rememberCameraSelectionState(
    context: Context,
    selectedResolution: CaptureResolutionMode,
    selectedLensSlot: LensSlot,
    selectedThreeXSource: ThreeXSourceMode,
    zoomUiState: ZoomUiState,
    capabilityRefreshNonce: Int
): CameraSelectionUiState {
    val options = SelectedCaptureOptions(
        lensSlot = selectedLensSlot,
        resolutionMode = selectedResolution,
        threeXSourceMode = selectedThreeXSource
    )
    val selection = remember(
        selectedResolution,
        selectedLensSlot,
        selectedThreeXSource,
        zoomUiState,
        capabilityRefreshNonce
    ) {
        selectCameraForOptions(context, options)
    }
    val requestedZoomRatio = zoomUiState.zoomRatio
        .coerceIn(zoomUiState.minZoom, zoomUiState.maxZoom)
    val resolvedZoomRatio = when (selection.actualLensSource) {
        ActualLensSource.MAIN_1X,
        ActualLensSource.MAIN_CROP_2X,
        ActualLensSource.MAIN_CROP_3X,
        ActualLensSource.OPTICAL_TELE_UNAVAILABLE_FALLBACK_CROP -> requestedZoomRatio

        ActualLensSource.ULTRAWIDE,
        ActualLensSource.OPTICAL_TELE_LOGICAL,
        ActualLensSource.OPTICAL_TELE_PHYSICAL -> selection.effectiveZoomRatio
    }

    LaunchedEffect(
        selectedResolution,
        selectedLensSlot,
        selectedThreeXSource,
        zoomUiState,
        selection
    ) {
        if (selectedLensSlot == LensSlot.THREE_X) {
            Log.d(
                "Kepler3xSelection",
                "phase=finalSelection selectedLensSlot=$selectedLensSlot " +
                    "previousSource=unknown newSource=$selectedThreeXSource " +
                    "zoom=${zoomUiState.zoomRatio} " +
                    "optical=${zoomUiState.useOpticalTeleAt3x} " +
                    "cameraId=${selection.cameraId} actual=${selection.actualLensSource} " +
                    "physicalCameraId=${selection.physicalCameraId} " +
                    "effectiveZoom=${selection.effectiveZoomRatio} useCrop=${selection.useCrop} " +
                    "previewZoom=$resolvedZoomRatio captureZoom=$resolvedZoomRatio " +
                    "physicalTele=${selection.isOpticalTeleActuallyUsed && !selection.useCrop} " +
                    "mainCrop=${selection.actualLensSource == ActualLensSource.MAIN_CROP_3X}"
            )
        }
    }

    return CameraSelectionUiState(
        options = options,
        selection = selection,
        previewZoomRatio = resolvedZoomRatio,
        captureZoomRatio = resolvedZoomRatio
    )
}

@Composable
internal fun rememberResolutionState(
    context: Context,
    cameraSelection: CameraSelection,
    selectedResolution: CaptureResolutionMode,
    selectedLensSlot: LensSlot,
    selectedThreeXSource: ThreeXSourceMode,
    pipelineMode: PipelineMode,
    capabilityRefreshNonce: Int
): ResolutionUiState {
    val capability = remember(
        cameraSelection.cameraId,
        selectedLensSlot,
        selectedThreeXSource,
        capabilityRefreshNonce
    ) {
        runCatching {
            queryCameraResolutionCapability(context, cameraSelection.cameraId, selectedLensSlot)
        }.getOrNull()
    }
    val plans = remember(selectedLensSlot, selectedThreeXSource, pipelineMode, capability) {
        capability
            ?.let {
                buildResolutionCapturePlans(
                    selectedLensSlot,
                    selectedThreeXSource,
                    pipelineMode,
                    it
                )
            }
            .orEmpty()
    }
    val allowedModes = remember(plans) {
        plans
            .filter { it.isAvailable }
            .map { it.requestedMode }
            .ifEmpty { listOf(CaptureResolutionMode.MP12) }
    }
    val selectedPlan = remember(selectedResolution, plans) {
        plans.firstOrNull { it.requestedMode == selectedResolution && it.isAvailable }
    }
    return ResolutionUiState(capability, plans, allowedModes, selectedPlan)
}
