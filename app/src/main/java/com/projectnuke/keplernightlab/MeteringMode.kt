package com.projectnuke.keplernightlab

enum class MeteringMode(
    val label: String,
    val shortLabel: String
) {
    MATRIX("Matrix", "MAT"),
    CENTER("Center", "CTR"),
    SPOT("Spot", "SPT");

    fun next(): MeteringMode = when (this) {
        MATRIX -> CENTER
        CENTER -> SPOT
        SPOT -> MATRIX
    }
}
