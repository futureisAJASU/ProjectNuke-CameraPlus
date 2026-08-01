package com.projectnuke.keplernightlab

import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RawExportOrientationMetadataTest {
    private fun job(json: JSONObject): File {
        val dir = createTempDir(prefix = "raw_orientation_")
        File(dir, "job.json").writeText(json.toString())
        return dir
    }

    private fun validJob() = JSONObject()
        .put("sourceOrientationState", "UNROTATED_RAW_SENSOR_GRID")
        .put("sensorOrientation", 90)
        .put("displayRotationAtCapture", Surface.ROTATION_90)
        .put("lensFacing", CameraCharacteristics.LENS_FACING_BACK)
        .put("exportSourceWasDisplayUpright", false)
        .put("rotationAppliedAtExportStage", false)

    @Test fun validNumericOrientationResolves() {
        val result = resolveRawExportRotation(job(validJob())) as ExportOrientationResolution.Resolved
        assertEquals(0, result.degrees)
    }

    @Test fun nullOrientationFailsClosed() {
        val json = validJob().put("sensorOrientation", JSONObject.NULL)
        assertTrue(resolveRawExportRotation(job(json)) is ExportOrientationResolution.Unsupported)
    }

    @Test fun stringOrientationFailsClosed() {
        val json = validJob().put("displayRotationAtCapture", "90")
        assertTrue(resolveRawExportRotation(job(json)) is ExportOrientationResolution.Unsupported)
    }

    @Test fun missingLegacySourceStateRemainsExplicitlyUnrotated() {
        val json = JSONObject().put("sensorOrientation", 90)
        val result = resolveRawExportRotation(job(json)) as ExportOrientationResolution.Resolved
        assertEquals(0, result.degrees)
    }
}
