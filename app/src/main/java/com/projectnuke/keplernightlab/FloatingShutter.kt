package com.projectnuke.keplernightlab

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal val FloatingShutterButtonSize: Dp = 72.dp

/**
 * Floating shutter split into:
 *  A — full-screen positioning container (non-interactive, does NOT intercept)
 *  B — the actual 72dp interactive node (the only hit surface).
 *
 * This ensures the floating shutter cannot intercept taps intended for focus,
 * result thumbnail, zoom, settings, camera switch or mode controls.
 *
 * Position is in layout-local PIXELS; placement uses
 * [Modifier.offset] with [IntOffset] so no dp/px mix occurs.
 *
 * The tap + long-press + drag are provided by two coordinated recognizers on
 * the same 72dp node: [detectTapGestures] handles the tap → capture, and
 * [detectDragGesturesAfterLongPress] handles the long-press → enter drag
 * followed by a continuous drag in the SAME gesture (no lift required).
 */
@Composable
fun FloatingShutterButton(
    isDragging: Boolean,
    center: Offset,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Geometry is center-based; convert to top-left for Modifier.offset.
    // Hardened for tests: IntOffset is integer, but position stays float.
    val radiusPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        (FloatingShutterButtonSize / 2f).toPx()
    }
    val topLeft = floatingShutterTopLeft(center, radiusPx)
    // A: full-screen positioning layer — no pointer handling, does not intercept.
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopStart
    ) {
        // B: interactive 72dp hit area, placed at center→topLeft in pixels.
        Box(
            modifier = Modifier
                .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                .size(FloatingShutterButtonSize)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() })
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDragCancel = { onDragEnd() },
                        onDragEnd = { onDragEnd() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount)
                        }
                    )
                }
                .testTag("kepler.camera.floatingShutter"),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(ShutterOuterSize)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(
                        if (isDragging) Color(0xFFFFD33D).copy(alpha = 0.9f)
                        else Color.White.copy(alpha = 0.9f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(ShutterInnerSize)
                        .clip(CircleShape)
                        .background(
                            if (isDragging) Color(0xFFFFD33D) else Color.White
                        )
                )
            }
        }
    }
}