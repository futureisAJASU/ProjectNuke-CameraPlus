package com.projectnuke.keplernightlab

import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportOrientationTest {
    private fun resolve(sensor: Int, display: Int, facing: Int): Int =
        (resolveExportOrientation(ExportOrientationInput(sensor, display, facing, false, false)) as ExportOrientationResolution.Resolved).degrees

    @Test fun portraitBackCamera() = assertEquals(90, resolve(90, Surface.ROTATION_0, CameraCharacteristics.LENS_FACING_BACK))
    @Test fun landscapeLeftBackCamera() = assertEquals(0, resolve(90, Surface.ROTATION_90, CameraCharacteristics.LENS_FACING_BACK))
    @Test fun landscapeRightBackCamera() = assertEquals(180, resolve(90, Surface.ROTATION_270, CameraCharacteristics.LENS_FACING_BACK))
    @Test fun upsideDownBackCamera() = assertEquals(270, resolve(90, Surface.ROTATION_180, CameraCharacteristics.LENS_FACING_BACK))
    @Test fun frontCameraUsesRotationOnlyAndNeverMirroring() = assertEquals(180, resolve(90, Surface.ROTATION_90, CameraCharacteristics.LENS_FACING_FRONT))
    @Test fun displayUprightSourceAndAlreadyCorrectedSourceAreNotRotatedAgain() {
        val upright = resolveExportOrientation(ExportOrientationInput(90, Surface.ROTATION_90, CameraCharacteristics.LENS_FACING_BACK, true, false))
        val corrected = resolveExportOrientation(ExportOrientationInput(90, Surface.ROTATION_90, CameraCharacteristics.LENS_FACING_BACK, false, true))
        assertEquals(0, (upright as ExportOrientationResolution.Resolved).degrees)
        assertEquals(0, (corrected as ExportOrientationResolution.Resolved).degrees)
    }
    @Test fun contradictoryMetadataFailsClosed() {
        assertTrue(resolveExportOrientation(ExportOrientationInput(90, 99, CameraCharacteristics.LENS_FACING_BACK, false, false)) is ExportOrientationResolution.Unsupported)
    }
}
