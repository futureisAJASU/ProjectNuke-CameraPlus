package com.projectnuke.keplernightlab

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import android.util.Log
import kotlin.math.roundToInt

data class Camera2ZoomApplication(
    val requestedZoomRatio: Float,
    val appliedZoomRatio: Float,
    val usedControlZoomRatio: Boolean,
    val cropRegion: Rect?,
    val zoomRatioRange: String
)

fun CaptureRequest.Builder.applyCamera2Zoom(
    characteristics: CameraCharacteristics,
    zoomRatio: Float,
    useMaximumResolutionActiveArray: Boolean = false
): Camera2ZoomApplication {
    val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
    } else {
        null
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && zoomRange != null) {
        val appliedZoom = zoomRatio.coerceIn(zoomRange.lower, zoomRange.upper)
        set(CaptureRequest.CONTROL_ZOOM_RATIO, appliedZoom)
        return Camera2ZoomApplication(
            requestedZoomRatio = zoomRatio,
            appliedZoomRatio = appliedZoom,
            usedControlZoomRatio = true,
            cropRegion = null,
            zoomRatioRange = "${zoomRange.lower}..${zoomRange.upper}"
        )
    }

    val crop = buildCenterCropRegionForPixelMode(
        characteristics = characteristics,
        zoomRatio = zoomRatio,
        useMaximumResolutionActiveArray = useMaximumResolutionActiveArray
    ).region
    crop?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
    return Camera2ZoomApplication(
        requestedZoomRatio = zoomRatio,
        appliedZoomRatio = zoomRatio,
        usedControlZoomRatio = false,
        cropRegion = crop,
        zoomRatioRange = "unavailable"
    )
}

fun CaptureRequest.Builder.applyZoomAndFocusAe(
    characteristics: CameraCharacteristics,
    zoomRatio: Float,
    focusAeState: FocusAeState,
    useMaximumResolutionActiveArray: Boolean = false,
    cameraId: String? = null
) {
    val zoomApplication = applyCamera2Zoom(
        characteristics = characteristics,
        zoomRatio = zoomRatio,
        useMaximumResolutionActiveArray = useMaximumResolutionActiveArray
    )
    if (zoomRatio >= 2.9f) {
        Log.d(
            "Kepler3xSelection",
            "captureZoom cameraId=${cameraId ?: "unknown"} requested=$zoomRatio " +
                "mode=${if (zoomApplication.usedControlZoomRatio) "CONTROL_ZOOM_RATIO" else "SCALER_CROP_REGION"} " +
                "applied=${zoomApplication.appliedZoomRatio} " +
                "range=${zoomApplication.zoomRatioRange} crop=${zoomApplication.cropRegion}"
        )
    }

    val aeRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    val exposureIndex = if (aeRange != null) {
        focusAeState.exposureCompensationIndex.coerceIn(aeRange.lower, aeRange.upper)
    } else {
        0
    }
    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureIndex)
    set(CaptureRequest.CONTROL_AE_LOCK, focusAeState.locked)

    val region = buildFocusAeMeteringRectangle(
        characteristics = characteristics,
        zoomApplication = zoomApplication,
        point = focusAeState.point,
        useMaximumResolutionActiveArray = useMaximumResolutionActiveArray
    )
    if (region != null) {
        set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        // TODO: future ProMode.kt for manual ISO, shutter speed, white balance, focus distance.
    }
}

fun buildFocusAeMeteringRectangle(
    characteristics: CameraCharacteristics,
    zoomApplication: Camera2ZoomApplication,
    point: NormalizedPoint?,
    useMaximumResolutionActiveArray: Boolean = false
): MeteringRectangle? {
    if (point == null) return null
    val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
    val crop = if (zoomApplication.usedControlZoomRatio) {
        active
    } else {
        buildCenterCropRegionForPixelMode(
            characteristics = characteristics,
            zoomRatio = zoomApplication.appliedZoomRatio,
            useMaximumResolutionActiveArray = useMaximumResolutionActiveArray
        ).region ?: active
    }
    val x = (crop.left + crop.width() * point.x.coerceIn(0f, 1f)).roundToInt()
    val y = (crop.top + crop.height() * point.y.coerceIn(0f, 1f)).roundToInt()
    val box = (minOf(crop.width(), crop.height()) * 0.10f).roundToInt().coerceAtLeast(16)
    val rect = Rect(
        (x - box / 2).coerceIn(crop.left, crop.right - 1),
        (y - box / 2).coerceIn(crop.top, crop.bottom - 1),
        (x + box / 2).coerceIn(crop.left + 1, crop.right),
        (y + box / 2).coerceIn(crop.top + 1, crop.bottom)
    )
    Log.d(
        "KeplerMetering",
        "metering mode=${if (zoomApplication.usedControlZoomRatio) "CONTROL_ZOOM_RATIO" else "SCALER_CROP_REGION"} " +
            "zoom=${zoomApplication.appliedZoomRatio} point=$point region=$rect"
    )
    return MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
}
