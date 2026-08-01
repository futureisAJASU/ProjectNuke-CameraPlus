package com.projectnuke.keplernightlab

import android.view.Surface

internal data class ExportDisplayOrientationResult(
    val estimatedRotationDegrees: Int,
    val appliedRotationDegrees: Int,
    val sourceWasDisplayUpright: Boolean,
    val source: String
) {
    init {
        require(estimatedRotationDegrees in setOf(0, 90, 180, 270)) {
            "estimatedRotationDegrees must be 0, 90, 180, or 270; got $estimatedRotationDegrees"
        }
        require(appliedRotationDegrees in setOf(0, 90, 180, 270)) {
            "appliedRotationDegrees must be 0, 90, 180, or 270; got $appliedRotationDegrees"
        }
    }
}

internal object ExportDisplayOrientationResolver {
    fun resolve(
        sensorOrientationDegrees: Int,
        displayRotation: Int,
        lensFacing: Int,
        persistedOutputOrientation: String?,
        alreadyAppliedMarker: String?,
        sourceTag: String
    ): ExportDisplayOrientationResult {
        val sourceWasUpright = when {
            alreadyAppliedMarker != null && alreadyAppliedMarker.equals("ALREADY_DISPLAY_UPRIGHT", true) -> true
            persistedOutputOrientation != null && persistedOutputOrientation.equals("ALREADY_DISPLAY_UPRIGHT", true) -> true
            else -> false
        }
        if (sourceWasUpright) {
            return ExportDisplayOrientationResult(
                estimatedRotationDegrees = 0,
                appliedRotationDegrees = 0,
                sourceWasDisplayUpright = true,
                source = sourceTag
            )
        }
        if (sensorOrientationDegrees !in setOf(0, 90, 180, 270)) {
            throw IllegalArgumentException("sensorOrientationDegrees must be 0, 90, 180, or 270; got $sensorOrientationDegrees")
        }
        val validRotations = setOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)
        if (displayRotation !in validRotations) {
            throw IllegalArgumentException("displayRotation must be a Surface rotation constant; got $displayRotation")
        }
        if (lensFacing != android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK &&
            lensFacing != android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        ) {
            throw IllegalArgumentException("lensFacing must be LENS_FACING_BACK or LENS_FACING_FRONT; got $lensFacing")
        }
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val estimatedRotation = if (lensFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientationDegrees + displayDegrees) % 360
        } else {
            (sensorOrientationDegrees - displayDegrees + 360) % 360
        }
        return ExportDisplayOrientationResult(
            estimatedRotationDegrees = estimatedRotation,
            appliedRotationDegrees = estimatedRotation,
            sourceWasDisplayUpright = false,
            source = sourceTag
        )
    }
}
