package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Phase 7: heavy full-resolution diagnostic PNG generation is gated behind
 * explicit debug/diagnostic intent; required production output and the JSON
 * quality metrics (HardwareE2E evidence) are ALWAYS produced.
 */
@RunWith(RobolectricTestRunner::class)
class DebugArtifactPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun resetOverride() {
        DebugArtifactPolicy.overrideForTest = null
    }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)

    private fun referencePng(jobDir: File): File {
        val file = File(jobDir, "reference.png")
        val bmp = bitmap()
        try {
            file.outputStream().use { check(bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bmp.recycle()
        }
        return file
    }

    @Test
    fun debug_images_disabled_without_diagnostic_intent() {
        // Default job (no diagnosticIntent): images must be disabled even in a
        // debug unit-test build - normal user captures never pay for them.
        assertFalse(DebugArtifactPolicy.imageArtifactsEnabled(JSONObject()))
        assertFalse(
            DebugArtifactPolicy.imageArtifactsEnabled(JSONObject().put("diagnosticIntent", false))
        )
        // Explicit intent on a debug build enables them.
        assertTrue(
            DebugArtifactPolicy.imageArtifactsEnabled(JSONObject().put("diagnosticIntent", true))
        )
    }

    @Test
    fun quality_metrics_survive_image_gating() {
        val jobDir = tmp.newFolder()
        val job = JSONObject() // no diagnosticIntent -> sheets gated
        val reference = bitmap()
        val fused = bitmap()
        try {
            writeFusionQualityDiagnostics(
                job = job,
                jobDir = jobDir,
                prefix = "yuv",
                reference = reference,
                fused = fused,
                denoised = null,
                finalImage = fused,
                compareFileName = "yuv_compare_reference_vs_final.png"
            )
        } finally {
            reference.recycle()
            fused.recycle()
        }
        // HardwareE2E evidence (metrics) is merged into the job regardless.
        assertTrue(job.has("fusedSharpness"))
        assertTrue(job.has("fusionQualityHint"))
        assertTrue(job.has("qualityDiagnosticCompareFile"))
        // The heavy sheet IMAGES are not generated when gated.
        assertFalse(File(jobDir, "yuv_compare_reference_vs_final.png").exists())
        assertFalse(File(jobDir, "diagnostic_crop_sheet.png").exists())
    }

    @Test
    fun quality_sheets_generated_only_with_intent() {
        DebugArtifactPolicy.overrideForTest = true
        val jobDir = tmp.newFolder()
        val job = JSONObject()
        val reference = bitmap()
        val finalImage = bitmap()
        try {
            writeFusionQualityDiagnostics(
                job = job,
                jobDir = jobDir,
                prefix = "raw",
                reference = reference,
                fused = null,
                denoised = null,
                finalImage = finalImage,
                compareFileName = "compare_reference_vs_final.png"
            )
        } finally {
            reference.recycle()
            finalImage.recycle()
        }
        assertTrue(File(jobDir, "compare_reference_vs_final.png").isFile)
        assertTrue(File(jobDir, "diagnostic_crop_sheet.png").isFile)
    }

    @Test
    fun policy_override_supports_deterministic_tests() {
        DebugArtifactPolicy.overrideForTest = false
        assertFalse(
            DebugArtifactPolicy.imageArtifactsEnabled(JSONObject().put("diagnosticIntent", true))
        )
        DebugArtifactPolicy.overrideForTest = true
        assertTrue(DebugArtifactPolicy.imageArtifactsEnabled(JSONObject()))
        assertNull(DebugArtifactPolicy.overrideForTest.let { null }) // documentation no-op
    }
}
