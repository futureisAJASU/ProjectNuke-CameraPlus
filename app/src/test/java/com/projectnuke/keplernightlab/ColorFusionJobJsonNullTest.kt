package com.projectnuke.keplernightlab

import java.io.File
import kotlin.io.path.createTempDirectory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ColorFusionJobJsonNullTest {

    private fun newDir(): File = createTempDirectory("kepler-color-job-json").toFile()

    private fun seedJob(dir: File, configure: JSONObject.() -> Unit) {
        KeplerJobMetadata.write(
            dir,
            JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .apply(configure)
        )
    }

    private fun rewriteJobJson(jobFile: File): JSONObject {
        val dir = jobFile.parentFile
        writeColorJobJson(
            jobFile = jobFile,
            status = "CAPTURING",
            cameraId = "0",
            width = 8,
            height = 8,
            outputWidth = 8,
            outputHeight = 8,
            rotationDegrees = 0,
            requestedFrames = 4,
            savedFrames = 0,
            frameManifest = emptyList(),
            gyroFile = null,
            rotationVectorFile = null,
            gyroSampleCount = 0,
            rotationVectorSampleCount = 0,
            motionInfo = "not_started"
        )
        return KeplerJobMetadata.read(dir)
    }

    @Test
    fun writeColorJobJson_preservesUnknownYuvDiagnosticsAsJsonNull() {
        val dir = newDir()
        try {
            val jobFile = File(dir, JOB_JSON_FILE_NAME)
            seedJob(dir) {
                put("yuvReceivedFrames", JSONObject.NULL)
                put("yuvFirstWorkerFailureClass", JSONObject.NULL)
                put("yuvFirstWorkerFailureFrameIndex", JSONObject.NULL)
            }
            val written = rewriteJobJson(jobFile)
            assertTrue(written.isNull("yuvReceivedFrames"))
            assertSame(JSONObject.NULL, written.get("yuvFirstWorkerFailureClass"))
            assertSame(JSONObject.NULL, written.get("yuvFirstWorkerFailureFrameIndex"))
            // Not silently rewritten as an observed zero/default.
            assertEquals(-1, written.optInt("yuvFirstWorkerFailureFrameIndex", -1))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun writeColorJobJson_preservesObservedZero() {
        val dir = newTempZeroDir()
        try {
            val jobFile = File(dir, JOB_JSON_FILE_NAME)
            seedJob(dir) {
                put("yuvReceivedFrames", 0)
                put("yuvCompletedResults", 0)
            }
            val written = rewriteJobJson(jobFile)
            assertEquals(0, written.getInt("yuvReceivedFrames"))
            assertTrue(!written.isNull("yuvReceivedFrames"))
            assertEquals(0, written.getInt("yuvCompletedResults"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun newTempZeroDir(): File = createTempDirectory("kepler-color-job-json-zero").toFile()

    @Test
    fun writeColorJobJson_preservesActualFailureValues() {
        val dir = newDir()
        try {
            val jobFile = File(dir, JOB_JSON_FILE_NAME)
            seedJob(dir) {
                put("yuvFirstWorkerFailureClass", "java.lang.IllegalStateException")
                put("yuvFirstWorkerFailureMessage", "PNG decode failed")
                put("yuvFirstWorkerFailureFrameIndex", 2)
                put("yuvFirstWorkerFailureRootCauseClass", "javax.imageio.IIOException")
                put("yuvFirstWorkerFailureStage", "TEMP_VERIFY")
                put("yuvReceivedFrames", 3)
            }
            // Intermediate rewrites that do not carry the diagnostics forward
            // must not erase or corrupt the stored failure evidence.
            val preserved = rewriteJobJson(jobFile)
            assertEquals("java.lang.IllegalStateException", preserved.getString("yuvFirstWorkerFailureClass"))
            assertEquals("PNG decode failed", preserved.getString("yuvFirstWorkerFailureMessage"))
            assertEquals(2, preserved.getInt("yuvFirstWorkerFailureFrameIndex"))
            assertEquals("javax.imageio.IIOException", preserved.getString("yuvFirstWorkerFailureRootCauseClass"))
            assertEquals("TEMP_VERIFY", preserved.getString("yuvFirstWorkerFailureStage"))
            assertEquals(3, preserved.getInt("yuvReceivedFrames"))

            // A null parameter means "no new observation": the previously
            // observed values stay preserved (never reset to null/0/"null").
            writeColorJobJson(
                jobFile = jobFile,
                status = "CAPTURING",
                cameraId = "0",
                width = 8,
                height = 8,
                outputWidth = 8,
                outputHeight = 8,
                rotationDegrees = 0,
                requestedFrames = 4,
                savedFrames = 0,
                frameManifest = emptyList(),
                gyroFile = null,
                rotationVectorFile = null,
                gyroSampleCount = 0,
                rotationVectorSampleCount = 0,
                motionInfo = "not_started",
                yuvReceivedFrames = 4
            )
            val updated = KeplerJobMetadata.read(dir)
            assertEquals(4, updated.getInt("yuvReceivedFrames"))
            assertEquals("java.lang.IllegalStateException", updated.getString("yuvFirstWorkerFailureClass"))
            assertEquals("PNG decode failed", updated.getString("yuvFirstWorkerFailureMessage"))
            // Untouched optional fields keep their previously observed values.
            assertEquals(2, updated.getInt("yuvFirstWorkerFailureFrameIndex"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun optNullableHelpers_distinguishMissingNullAndValues() {
        val json = JSONObject()
            .put("absentCase", JSONObject.NULL)
            .put("zeroCase", 0)
            .put("longCase", 42L)
            .put("stringCase", "value")
            .put("emptyStringCase", "")
        assertEquals(null, json.optNullableInt("missingKey"))
        assertEquals(null, json.optNullableInt("absentCase"))
        assertEquals(0, json.optNullableInt("zeroCase"))
        assertEquals(null, json.optNullableLong("missingKey"))
        assertEquals(null, json.optNullableLong("absentCase"))
        assertEquals(42L, json.optNullableLong("longCase"))
        assertEquals(null, json.optNullableString("missingKey"))
        assertEquals(null, json.optNullableString("absentCase"))
        assertEquals("value", json.optNullableString("stringCase"))
        assertEquals("", json.optNullableString("emptyStringCase"))
    }
}
