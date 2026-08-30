package com.projectnuke.keplernightlab

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val FloatingShutterButtonSize: Dp = 72.dp

@Composable
fun FloatingShutterButton(
    state: FloatingShutterState,
    position: androidx.compose.ui.geometry.Offset,
    onPositionChange: (androidx.compose.ui.geometry.Offset) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isIdle = state == FloatingShutterState.FLOATING_IDLE
    val isDragging = state == FloatingShutterState.FLOATING_DRAGGING

    var dragOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Box(
        modifier = modifier
            .offset(
                x = (position.x + dragOffset.x).dp,
                y = (position.y + dragOffset.y).dp
            )
            .size(FloatingShutterButtonSize)
            .testTag("kepler.camera.floatingShutter")
            .pointerInput(state, position) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = {
                        dragOffset = androidx.compose.ui.geometry.Offset.Zero
                        onLongPress()
                    }
                )
                if (isDragging) {
                    detectDragGestures(
                        onDragStart = { dragOffset = androidx.compose.ui.geometry.Offset.Zero },
                        onDrag = { _, dragAmount -> dragOffset += dragAmount },
                        onDragEnd = {
                            val newPos = androidx.compose.ui.geometry.Offset(
                                x = position.x + dragOffset.x,
                                y = position.y + dragOffset.y
                            )
                            onPositionChange(newPos)
                            dragOffset = androidx.compose.ui.geometry.Offset.Zero
                        }
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Visual shutter appearance matching main shutter style
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
