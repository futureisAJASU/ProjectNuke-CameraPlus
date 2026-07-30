package com.projectnuke.keplernightlab

import kotlin.math.roundToInt

internal data class CfaSafeRawShift(
    val estimatedDx: Float,
    val estimatedDy: Float,
    val appliedDx: Int,
    val appliedDy: Int
)

internal fun cfaSafeRawShift(estimatedDx: Float, estimatedDy: Float): CfaSafeRawShift {
    fun even(value: Float): Int = (value / 2f).roundToInt() * 2
    return CfaSafeRawShift(estimatedDx, estimatedDy, even(estimatedDx), even(estimatedDy))
}
