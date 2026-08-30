package com.projectnuke.keplernightlab

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * DISPLAY-SPACE normalization only.
 *
 * The preview tap is expressed as a normalized point in the on-screen preview
 * container. This value is stored verbatim on [FocusAeState.point] and consumed
 * by [FocusAeOverlay] (which multiplies it against its own BoxWithConstraints
 * dimensions). It MUST NOT be rotated here: rotating display coordinates before
 * persistence would move the AF/AE marker away from the user's finger in
 * landscape.
 */
fun normalizeDisplayPoint(offset: Offset, containerSize: Size): NormalizedPoint? {
    if (containerSize.width <= 0f || containerSize.height <= 0f) return null
    return NormalizedPoint(
        x = (offset.x / containerSize.width).coerceIn(0f, 1f),
        y = (offset.y / containerSize.height).coerceIn(0f, 1f)
    )
}

/**
 * SENSOR-SPACE transform, applied ONLY when Camera2 metering coordinates are
 * built.
 *
 * This uses the same orientation basis as
 * [CameraPreviewController.configureTransform]:
 *   sensorOrientation  = CameraCharacteristics.SENSOR_ORIENTATION
 *   displayRotation   = Surface.ROTATION_0/90/180/270
 *   relativeRotation  = relativeRotationDegrees(sensorOrientation, display)
 *
 * The display-normalized point is counter-rotated by relativeRotation to reach
 * the sensor/crop-normalized space:
 *     0   -> (x, y)
 *     90  -> (1 - y, x)
 *     180 -> (1 - x, 1 - y)
 *     270 -> (y, 1 - x)
 *
 * Center is invariant under every rotation (a pure right-angle rotation maps
 * (0.5, 0.5) to (0.5, 0.5)), which is asserted by tests.
 *
 * [mirrored] exists as an explicit seam for any future front-camera path that
 * horizontally flips the preview. The current production path never mirrors,
 * so callers leave it false; the transform still documents the audit point so a
 * front-camera capture cannot silently share the un-mirrored coordinate basis.
 */
fun transformDisplayPointToSensorPoint(
    displayPoint: NormalizedPoint,
    sensorOrientationDegrees: Int,
    displayRotation: Int,
    mirrored: Boolean = false
): NormalizedPoint {
    var x = displayPoint.x.coerceIn(0f, 1f)
    var y = displayPoint.y.coerceIn(0f, 1f)
    if (mirrored) {
        x = 1f - x
    }
    val relativeRotation = relativeRotationDegrees(
        sensorOrientationDegrees = sensorOrientationDegrees,
        displayRotation = displayRotation
    )
    return when (relativeRotation) {
        90 -> NormalizedPoint(1f - y, x)
        180 -> NormalizedPoint(1f - x, 1f - y)
        270 -> NormalizedPoint(y, 1f - x)
        else -> NormalizedPoint(x, y)
    }
}
