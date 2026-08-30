package com.projectnuke.keplernightlab

import androidx.compose.ui.geometry.Offset

fun normalizePointFromPreviewContainer(
    offset: Offset,
    containerSize: androidx.compose.ui.geometry.Size,
    layoutMode: CameraUiLayoutMode
): NormalizedPoint? {
    if (containerSize.width <= 0f || containerSize.height <= 0f) return null
    val rawX = (offset.x / containerSize.width).coerceIn(0f, 1f)
    val rawY = (offset.y / containerSize.height).coerceIn(0f, 1f)
    return when (layoutMode) {
        CameraUiLayoutMode.PORTRAIT -> NormalizedPoint(rawX, rawY)
        CameraUiLayoutMode.LANDSCAPE_LEFT -> {
            // In landscape left (rotation 90), the sensor is rotated 90 clockwise relative to display.
            // To keep tap-to-focus intuitive: the user's tap at top-left of screen (in landscape left)
            // should correspond to the top-left of the preview area in sensor coordinates.
            // We swap and invert accordingly.
            NormalizedPoint(1f - rawY, rawX)
        }
        CameraUiLayoutMode.LANDSCAPE_RIGHT -> {
            NormalizedPoint(rawY, 1f - rawX)
        }
    }
}
