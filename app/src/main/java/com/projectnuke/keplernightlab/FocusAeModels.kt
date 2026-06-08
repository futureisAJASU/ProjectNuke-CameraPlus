package com.projectnuke.keplernightlab

data class NormalizedPoint(
    val x: Float,
    val y: Float
)

data class FocusAeState(
    val point: NormalizedPoint? = null,
    val locked: Boolean = false,
    val exposureCompensationIndex: Int = 0,
    val exposureCompensationEv: Float = 0f,
    val supportedMinIndex: Int = 0,
    val supportedMaxIndex: Int = 0,
    val exposureStepEv: Float = 0f
)
