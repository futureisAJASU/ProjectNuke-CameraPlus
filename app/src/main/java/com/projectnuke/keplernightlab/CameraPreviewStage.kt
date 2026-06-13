package com.projectnuke.keplernightlab

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .fillMaxWidth()
            .background(Color.Black),
        contentAlignment = Alignment.TopCenter
    ) {
        val portraitPhotoAspectRatio = 3f / 4f
        val previewModifier = if (maxWidth / portraitPhotoAspectRatio <= maxHeight) {
            Modifier
                .fillMaxWidth()
                .aspectRatio(portraitPhotoAspectRatio)
        } else {
            Modifier
                .fillMaxHeight()
                .aspectRatio(portraitPhotoAspectRatio)
        }

        Box(
            modifier = Modifier
                .padding(top = PreviewTopInset)
                .then(previewModifier)
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        callbacks.onFocusPoint(
                            NormalizedPoint(
                                (offset.x / size.width).coerceIn(0f, 1f),
                                (offset.y / size.height).coerceIn(0f, 1f)
                            )
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Camera2Preview(
                modifier = Modifier.fillMaxSize(),
                cameraId = state.cameraSelection.cameraId,
                zoomRatio = state.previewZoomRatio,
                focusAeState = state.focusAeState,
                meteringMode = meteringMode,
                enabled = state.previewEnabled,
                onAeCapabilitiesChanged = callbacks.onAeCapabilitiesChanged
            )

            if (state.overlaySettings.showGrid) {
                RuleOfThirdsGridOverlay(Modifier.fillMaxSize())
            }
            if (state.overlaySettings.showLevel) {
                LevelIndicatorOverlay(
                    levelState = state.levelState,
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
