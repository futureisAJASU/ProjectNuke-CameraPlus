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
 * Inverts the exact visual viewfinder transform. The point remains in display
 * space until this function is called while building Camera2 metering regions.
 * The returned point is normalized in the unrotated preview-buffer coordinate
 * system, before it is placed inside the current active-array/zoom crop.
 */
internal fun mapDisplayPointToPreviewBuffer(
    displayPoint: NormalizedPoint,
    geometry: PreviewTransformGeometry
): NormalizedPoint = geometry.mapDisplayNormalizedPointToBuffer(displayPoint)

/**
 * Compatibility seam for callers that only need a right-angle orientation
 * conversion. Production metering must use [mapDisplayPointToPreviewBuffer]
 * with the actual preview geometry so center-crop offsets cannot be lost.
 */
@Deprecated("Use mapDisplayPointToPreviewBuffer with canonical preview geometry")
fun transformDisplayPointToSensorPoint(
    displayPoint: NormalizedPoint,
    sensorOrientationDegrees: Int,
    displayRotation: Int,
    mirrored: Boolean = false,
    lensFacing: Int = android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
): NormalizedPoint {
    var x = displayPoint.x.coerceIn(0f, 1f)
    var y = displayPoint.y.coerceIn(0f, 1f)
    if (mirrored) {
        x = 1f - x
    }
    val relativeRotation = relativeRotationDegrees(
        sensorOrientationDegrees = sensorOrientationDegrees,
        displayRotation = displayRotation,
        lensFacing = lensFacing
    )
    return when (relativeRotation) {
        90 -> NormalizedPoint(1f - y, x)
        180 -> NormalizedPoint(1f - x, 1f - y)
        270 -> NormalizedPoint(y, 1f - x)
        else -> NormalizedPoint(x, y)
    }
}
