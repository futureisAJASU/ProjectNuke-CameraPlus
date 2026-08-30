package com.projectnuke.keplernightlab

import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf

class FloatingShutterController(
    initialState: FloatingShutterState = FloatingShutterState.DOCKED,
    initialPosition: Offset = Offset(300f, 700f)
) {
    val state = mutableStateOf(initialState)
    val position = mutableStateOf(initialPosition)

    fun activateFloating() {
        state.value = FloatingShutterState.FLOATING_IDLE
    }
}
