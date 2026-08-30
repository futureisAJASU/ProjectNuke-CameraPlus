package com.projectnuke.keplernightlab

import androidx.compose.ui.geometry.Offset

enum class FloatingShutterState {
    DOCKED,
    FLOATING_IDLE,
    FLOATING_DRAGGING
}

data class FloatingShutterPosition(
    val position: Offset = Offset(0f, 0f)
)
