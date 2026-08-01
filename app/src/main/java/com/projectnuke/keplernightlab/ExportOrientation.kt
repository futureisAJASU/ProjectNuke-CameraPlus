package com.projectnuke.keplernightlab

import android.hardware.camera2.CameraCharacteristics
import android.view.Surface

internal data class ExportOrientationInput(
    val sensorOrientationDegrees: Int?,
    val displayRotation: Int?,
    val lensFacing: Int?,
    val sourceWasDisplayUpright: Boolean,
    val rotationAlreadyApplied: Boolean
)

internal sealed interface ExportOrientationResolution {
    data class Resolved(val degrees: Int) : ExportOrientationResolution
    data class Unsupported(val reason: String) : ExportOrientationResolution
}

/** Shared by normal export and reprocess; front-camera rotation never implies mirroring. */
internal fun resolveExportOrientation(input: ExportOrientationInput): ExportOrientationResolution {
    if (input.sourceWasDisplayUpright || input.rotationAlreadyApplied) return ExportOrientationResolution.Resolved(0)
    val sensor = input.sensorOrientationDegrees ?: return ExportOrientationResolution.Unsupported("Missing sensor orientation")
    val display = input.displayRotation ?: return ExportOrientationResolution.Unsupported("Missing capture display rotation")
    val sensorDegrees = normalizeRightAngle(sensor) ?: return ExportOrientationResolution.Unsupported("Invalid sensor orientation: $sensor")
    val displayDegrees = displayRotationDegrees(display) ?: return ExportOrientationResolution.Unsupported("Invalid display rotation: $display")
    return when (input.lensFacing) {
        CameraCharacteristics.LENS_FACING_BACK -> ExportOrientationResolution.Resolved(
            normalizeRightAngle(sensorDegrees - displayDegrees)!!
        )
        CameraCharacteristics.LENS_FACING_FRONT -> ExportOrientationResolution.Resolved(
            normalizeRightAngle(sensorDegrees + displayDegrees)!!
        )
        else -> ExportOrientationResolution.Unsupported("Missing or unsupported lens facing")
    }
}

internal fun normalizeRightAngle(degrees: Int): Int? {
    val normalized = ((degrees % 360) + 360) % 360
    return normalized.takeIf { it == 0 || it == 90 || it == 180 || it == 270 }
}

internal fun displayRotationDegrees(rotation: Int): Int? = when (rotation) {
    Surface.ROTATION_0 -> 0
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> null
}
