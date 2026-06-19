package com.projectnuke.keplernightlab

enum class CaptureResolutionMode(
    val label: String
) {
    MP12("12M"),
    MP50("50M"),
    MP24_FUSION("24M")
}

enum class LensSlot(
    val label: String,
    val targetZoomRatio: Float
) {
    ULTRAWIDE(".6", 0.6f),
    MAIN_1X("1×", 1.0f),
    MAIN_2X("2", 2.0f),
    THREE_X("3", 3.0f)
}

enum class ThreeXSourceMode(
    val label: String
) {
    AUTO("자동"),
    OPTICAL("3x 광학"),
    MAIN_CROP("3x 크롭")
}

data class SelectedCaptureOptions(
    val lensSlot: LensSlot,
    val resolutionMode: CaptureResolutionMode,
    val threeXSourceMode: ThreeXSourceMode
)
