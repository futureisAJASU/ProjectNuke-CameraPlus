package com.projectnuke.keplernightlab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class MeteringMode(
    val label: String,
    val shortLabel: String
) {
    AVERAGE("Average", "AVG"),
    CENTER_WEIGHTED("Center-weighted", "CW"),
    CENTER("Center", "CTR"),
    SPOT("Spot", "SPT");

    fun next(): MeteringMode = when (this) {
        AVERAGE -> CENTER_WEIGHTED
        CENTER_WEIGHTED -> CENTER
        CENTER -> SPOT
        SPOT -> AVERAGE
    }
}

object MeteringModeState {
    var mode by mutableStateOf(MeteringMode.CENTER)
        private set

    fun cycle() {
        mode = mode.next()
    }
}
