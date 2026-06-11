package com.projectnuke.keplernightlab

import android.content.Context
import androidx.compose.runtime.Composable
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
    val zoomUiState: ZoomUiState,
    val forcedResolution: CaptureResolutionMode?,
    val status: String
)

internal data class CapturePreparationInput(
    val options: SelectedCaptureOptions,
    val resolutionPlan: ResolutionCapturePlan,
    val selectedResolution: CaptureResolutionMode,
    val selectedLensSlot: LensSlot,
    val selectedThreeXSource: ThreeXSourceMode,
    val zoomUiState: ZoomUiState,
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
    val previewZoomRatio: Float
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
        selectedLensSlot,
        selectedThreeXSource,
        capabilityRefreshNonce
    ) {
        selectCameraForOptions(
            context,
            options.copy(resolutionMode = CaptureResolutionMode.MP12)
        )
    }
    val previewZoomRatio = when {
        selection.useCrop -> zoomUiState.zoomRatio.coerceIn(1.0f, zoomUiState.maxZoom)
        selectedLensSlot == LensSlot.THREE_X &&
            selectedThreeXSource == ThreeXSourceMode.OPTICAL -> 1.0f
        else -> zoomUiState.zoomRatio
    }
    return CameraSelectionUiState(options, selection, previewZoomRatio)
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
