package com.projectnuke.keplernightlab

import android.view.Surface

enum class CameraUiLayoutMode {
    PORTRAIT,
    LANDSCAPE_LEFT,
    LANDSCAPE_RIGHT
}

fun deriveCameraUiLayoutMode(displayRotation: Int): CameraUiLayoutMode {
    return when (displayRotation) {
        Surface.ROTATION_90 -> CameraUiLayoutMode.LANDSCAPE_LEFT
        Surface.ROTATION_270 -> CameraUiLayoutMode.LANDSCAPE_RIGHT
        else -> CameraUiLayoutMode.PORTRAIT
    }
}

fun CameraUiLayoutMode.isLandscape(): Boolean = this != CameraUiLayoutMode.PORTRAIT

fun CameraUiLayoutMode.isLandscapeLeft(): Boolean = this == CameraUiLayoutMode.LANDSCAPE_LEFT

fun CameraUiLayoutMode.isLandscapeRight(): Boolean = this == CameraUiLayoutMode.LANDSCAPE_RIGHT

enum class CameraChromeOrientation {
    BOTTOM,
    SIDE
}

fun CameraUiLayoutMode.chromeOrientation(): CameraChromeOrientation =
    if (this == CameraUiLayoutMode.PORTRAIT) CameraChromeOrientation.BOTTOM else CameraChromeOrientation.SIDE

/**
 * Per-label rotation for landscape chrome. Only individual mode labels are
 * rotated (±90°), never the whole layout tree: rotating a full-width tree with
 * graphicsLayer would not change its measured footprint.
 */
fun CameraUiLayoutMode.modeLabelRotationDegrees(): Float = when (this) {
    CameraUiLayoutMode.PORTRAIT -> 0f
    CameraUiLayoutMode.LANDSCAPE_LEFT -> -90f
    CameraUiLayoutMode.LANDSCAPE_RIGHT -> 90f
}
