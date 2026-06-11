package com.projectnuke.keplernightlab

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

@Composable
internal fun ColumnScope.PreviewStage(
    state: CameraPreviewPaneState,
    callbacks: CameraPreviewPaneCallbacks
) {
    BoxWithConstraints(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .background(Color.Black),
        contentAlignment = Alignment.Center
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
            modifier = previewModifier
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
            if (state.focusAeState.point != null && state.showFocusAeControls) {
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
