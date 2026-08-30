package com.projectnuke.keplernightlab

import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val PreviewTopInset: Dp = 88.dp

@Composable
internal fun PreviewStage(
    state: CameraPreviewPaneState,
    callbacks: CameraPreviewPaneCallbacks,
    modifier: Modifier = Modifier,
    meteringMode: MeteringMode = MeteringModeState.mode
) {
    val displayRotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
    val layoutMode = deriveCameraUiLayoutMode(displayRotation)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .fillMaxWidth()
            .background(Color.Black),
        contentAlignment = Alignment.TopCenter
    ) {
        val isLandscape = layoutMode.isLandscape()
        val previewModifier = if (isLandscape) {
            Modifier
                .fillMaxSize()
                .padding(top = PreviewTopInset)
        } else {
            val portraitPhotoAspectRatio = 3f / 4f
            val previewModifierPortrait = if (maxWidth / portraitPhotoAspectRatio <= maxHeight) {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(portraitPhotoAspectRatio)
            } else {
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(portraitPhotoAspectRatio)
            }
            Modifier
                .padding(top = PreviewTopInset)
                .then(previewModifierPortrait)
        }

        Box(
            modifier = Modifier
                .then(previewModifier)
                .background(Color.Black)
                .pointerInput(layoutMode) {
                    detectTapGestures { offset ->
                        val containerSize = androidx.compose.ui.geometry.Size(
                            width = size.width.toFloat(),
                            height = size.height.toFloat()
                        )
                        val point = normalizeDisplayPoint(
                            offset = androidx.compose.ui.geometry.Offset(offset.x, offset.y),
                            containerSize = containerSize
                        )
                        if (point != null) {
                            callbacks.onFocusPoint(point)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Camera2Preview(
                modifier = Modifier.fillMaxSize(),
                cameraId = state.cameraSelection.cameraId,
                physicalCameraId = state.cameraSelection.physicalCameraId.takeIf {
                    state.cameraSelection.actualLensSource == ActualLensSource.OPTICAL_TELE_PHYSICAL
                },
                zoomRatio = state.previewZoomRatio,
                selectedLensSlot = state.selectedLensSlot,
                selectedThreeXSource = state.selectedThreeXSource,
                actualLensSource = state.cameraSelection.actualLensSource,
                focusAeState = state.focusAeState,
                meteringMode = meteringMode,
                enabled = state.previewEnabled,
                onAeCapabilitiesChanged = callbacks.onAeCapabilitiesChanged,
                onPreviewAvailabilityChanged = callbacks.onPreviewAvailabilityChanged
            )

            if (state.overlaySettings.showGrid) {
                RuleOfThirdsGridOverlay(Modifier.fillMaxSize())
            }
            if (state.overlaySettings.showLevel) {
                LevelIndicatorOverlay(
                    levelState = state.levelState,
                    layoutMode = layoutMode,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (
                state.focusAeState.point != null &&
                (state.showFocusAeControls || state.focusAeState.locked)
            ) {
                FocusAeOverlay(
                    focusAeState = state.focusAeState,
                    onToggleLock = callbacks.onToggleFocusLock,
                    onExposureStep = callbacks.onExposureStep
                )
            }
            if (!state.previewEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (state.isCapturing) "Capturing..." else "Preview paused",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }
    }
}
