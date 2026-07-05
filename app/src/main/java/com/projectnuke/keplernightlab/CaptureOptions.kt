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
    MAIN_1X("1", 1.0f),
    MAIN_2X("2", 2.0f),
    THREE_X("3", 3.0f)
}

enum class ThreeXSourceMode(
    val label: String
) {
    OPTICAL("광학"),
    MAIN_CROP("크롭")
}

private const val LEGACY_THREE_X_SOURCE_AUTO = "AUTO"

internal fun parseThreeXSourceModeOrDefault(name: String?): ThreeXSourceMode = when (name) {
    ThreeXSourceMode.MAIN_CROP.name -> ThreeXSourceMode.MAIN_CROP
    ThreeXSourceMode.OPTICAL.name,
    LEGACY_THREE_X_SOURCE_AUTO,
    null -> ThreeXSourceMode.OPTICAL
    else -> ThreeXSourceMode.OPTICAL
}

internal val VisibleThreeXSourceModes: List<ThreeXSourceMode> = listOf(
    ThreeXSourceMode.OPTICAL,
    ThreeXSourceMode.MAIN_CROP
)

internal fun inferMetadataZoomRoute(
    requestedUiZoomRatio: Float,
    captureZoomRatio: Float,
    physicalCameraId: String?,
    cropApplied: Boolean,
    previewRoute: String?
): String {
    val normalizedPreviewRoute = previewRoute?.takeUnless { it.isBlank() }
    if (normalizedPreviewRoute != null) return normalizedPreviewRoute
    if (physicalCameraId != null) return ThreeXSourceMode.OPTICAL.name
    if (cropApplied || requestedUiZoomRatio >= 2.9f || captureZoomRatio >= 2.9f) {
        return ThreeXSourceMode.MAIN_CROP.name
    }
    if (requestedUiZoomRatio < 0.75f || captureZoomRatio < 0.75f) return LensSlot.ULTRAWIDE.name
    if (requestedUiZoomRatio >= 1.5f || captureZoomRatio >= 1.5f) return LensSlot.MAIN_2X.name
    return LensSlot.MAIN_1X.name
}

data class SelectedCaptureOptions(
    val lensSlot: LensSlot,
    val resolutionMode: CaptureResolutionMode,
    val threeXSourceMode: ThreeXSourceMode
)
