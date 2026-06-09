package com.projectnuke.keplernightlab

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import kotlin.math.roundToInt

fun CaptureRequest.Builder.applyZoomAndFocusAe(
    characteristics: CameraCharacteristics,
    zoomRatio: Float,
    focusAeState: FocusAeState,
    useMaximumResolutionActiveArray: Boolean = false
) {
    val crop = buildCenterCropRegionForPixelMode(
        characteristics = characteristics,
        zoomRatio = zoomRatio,
        useMaximumResolutionActiveArray = useMaximumResolutionActiveArray
    ).region
    crop?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }

    if (zoomRatio > 1f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            val zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zoomRange == null || zoomRange.contains(zoomRatio)) {
                set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
            }
        }
    }

    val aeRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    val exposureIndex = if (aeRange != null) {
        focusAeState.exposureCompensationIndex.coerceIn(aeRange.lower, aeRange.upper)
    } else {
        0
    }
    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureIndex)
    set(CaptureRequest.CONTROL_AE_LOCK, focusAeState.locked)

    val region = buildFocusAeMeteringRectangle(characteristics, zoomRatio, focusAeState.point)
    if (region != null) {
        set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        // TODO: future ProMode.kt for manual ISO, shutter speed, white balance, focus distance.
    }
}

fun buildFocusAeMeteringRectangle(
    characteristics: CameraCharacteristics,
    zoomRatio: Float,
    point: NormalizedPoint?
): MeteringRectangle? {
    if (point == null) return null
    val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
    val crop = buildCenterCropRegion(characteristics, zoomRatio) ?: active
    val x = (crop.left + crop.width() * point.x.coerceIn(0f, 1f)).roundToInt()
    val y = (crop.top + crop.height() * point.y.coerceIn(0f, 1f)).roundToInt()
    val box = (minOf(crop.width(), crop.height()) * 0.10f).roundToInt().coerceAtLeast(16)
    val rect = Rect(
        (x - box / 2).coerceIn(crop.left, crop.right - 1),
        (y - box / 2).coerceIn(crop.top, crop.bottom - 1),
        (x + box / 2).coerceIn(crop.left + 1, crop.right),
        (y + box / 2).coerceIn(crop.top + 1, crop.bottom)
    )
    return MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
}
