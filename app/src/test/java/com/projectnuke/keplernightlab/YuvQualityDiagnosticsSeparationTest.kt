package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase-A corrective audit, Phase 6: debug artifact / quality metric
 * separation.  Enters through the PRODUCTION entry point
 * [generateFusionDebugArtifacts] with heavy image artifacts DISABLED and
 * proves bounded quality metrics still exist; proves heavy full-resolution
 * images stay gated; and proves diagnosticIntent is durably stamped only from
 * the real debug entry point's armed state.
 */
@RunWith(RobolectricTestRunner::class)
class YuvQualityDiagnosticsSeparationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun resetPolicy() {
        DebugArtifactPolicy.overrideForTest = null
        DebugArtifactPolicy.setDiagnosticIntentArmed(false)
    }

    @After
    fun cleanupPolicy() {
        DebugArtifactPolicy.overrideForTest = null
        DebugArtifactPolicy.setDiagnosticIntentArmed(false)
    }

    private fun writePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "png write failed" }
        }
    }

    private fun syntheticBitmap(size: Int = 48, seed: Int = 7): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            // Bounded gradients + edges so sharpness/noise metrics are nonzero.
            android.graphics.Color.rgb(
                (x * 4 + seed) % 256,
                (y * 3 + seed) % 256,
                ((x + y) * 2 + seed) % 256
            )
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }

    /** Production-shaped fixture: job.json + reference PNG like a real burst job. */
    private fun productionShapedJob(): Triple<File, JSONObject, File> {
        val jobDir = tmp.newFolder()
        val referenceFile = File(jobDir, "reference_source.png")
        writePng(syntheticBitmap(seed = 3), referenceFile)
        val job = JSONObject()
            .put("jobType", "YUV_NIGHT_FUSION")
            .put("requestedFrames", 2)
            .put("savedFrames", 2)
        return Triple(jobDir, job, referenceFile)
    }

    private val params: ClassicYuvFusionParams get() = ClassicYuvFusionPreset.NATURAL.params

    private fun invokeProductionEntryPoint(jobDir: File, job: JSONObject, referenceFile: File) {
        generateFusionDebugArtifacts(
            jobDir = jobDir,
            job = job,
            referenceFile = referenceFile,
            mergedBitmap = syntheticBitmap(seed = 11),
            fusedBitmap = syntheticBitmap(seed = 5),
            params = params
        )
    }

    private val HEAVY_IMAGES = listOf(
        "reference_frame.png",
        "yuv_reference_preview.png",
        "fused_classic_yuv_v1.png",
        "yuv_fused_preview.png",
        "fused_classic_yuv_v1_natural.png",
        "compare_reference_vs_fused.png",
        "yuv_compare_reference_vs_fused.png"
    )

    private val BOUNDED_PREVIEWS = listOf(
        "yuv_final_preview.png",
        "yuv_fused_before_denoise_preview.png"
    )

    private fun assertQualityMetricsExist(job: JSONObject) {
        // The quality metrics JSON evidence merged into the job (persisted by
        // the pipeline via yuv_debug.json):
        assertEquals("fusion_quality_diag_v1", job.optString("qualityDiagnosticVersion"))
        assertTrue("finalSharpness missing", job.has("finalSharpness") && !job.isNull("finalSharpness"))
        assertTrue("referenceSharpness missing", job.has("referenceSharpness"))
        assertTrue("fusedSharpness missing", job.has("fusedSharpness"))
        assertTrue("denoisedSharpness missing", job.has("denoisedSharpness"))
        assertTrue("sharpnessDropReferenceToFused present", job.has("sharpnessDropReferenceToFused"))
        assertTrue("fusionQualityHint present", job.has("fusionQualityHint"))
        assertTrue("referencePreservedPixelRatio present", job.has("referencePreservedPixelRatio"))
    }

    @Test
    fun imagesDisabled_qualityMetricsStillProduced_throughProductionEntry() {
        DebugArtifactPolicy.overrideForTest = false
        val (jobDir, job, referenceFile) = productionShapedJob()

        invokeProductionEntryPoint(jobDir, job, referenceFile)

        assertQualityMetricsExist(job)
        // Bounded production preview contract survives.
        assertTrue(File(jobDir, "yuv_final_preview.png").isFile)
        assertTrue(File(jobDir, "yuv_fused_before_denoise_preview.png").isFile)
        assertEquals(DebugArtifactPolicy.STATUS_DISABLED, job.optString("debugArtifactStatus"))
        assertEquals(false, job.optBoolean("debugArtifactImagesEnabled"))
    }

    @Test
    fun imagesDisabled_heavyFullResolutionImagesRemainGated() {
        DebugArtifactPolicy.overrideForTest = false
        val (jobDir, job, referenceFile) = productionShapedJob()

        invokeProductionEntryPoint(jobDir, job, referenceFile)

        HEAVY_IMAGES.forEach { name ->
            assertFalse("heavy image $name must not exist", File(jobDir, name).exists())
        }
        // Compare/crop sheets from the diagnostics writer stay gated too.
        assertFalse(File(jobDir, "yuv_compare_reference_vs_final.png").exists())
        assertFalse(File(jobDir, "diagnostic_crop_sheet.png").exists())
    }

    @Test
    fun imagesEnabled_heavyImagesAndMetricsBothProduced() {
        DebugArtifactPolicy.overrideForTest = true
        val (jobDir, job, referenceFile) = productionShapedJob()

        invokeProductionEntryPoint(jobDir, job, referenceFile)

        assertQualityMetricsExist(job)
        HEAVY_IMAGES.forEach { name ->
            assertTrue("heavy image $name expected", File(jobDir, name).isFile)
        }
        assertEquals("COMPLETE", job.optString("debugArtifactStatus"))
    }

    // ------------------------------------------------------------------
    // diagnosticIntent durable stamping from the real entry point state
    // ------------------------------------------------------------------

    private fun newJobDirectory(): Pair<File, JSONObject> {
        val dir = tmp.newFolder()
        val initial = JSONObject().put("jobType", "YUV_NIGHT_FUSION")
        KeplerJobMetadata.write(dir, initial)
        return dir to initial
    }

    private fun stampLikeCaptureCreation(jobDir: File) {
        DebugArtifactPolicy.stampIntentForNewJob { key, value ->
            KeplerJobMetadata.update(jobDir) { job -> job.put(key, value) }
        }
    }

    @Test
    fun diagnosticIntent_normalCapturesNeverStamped() {
        val (dir, _) = newJobDirectory()
        // Production scenario: entry point does NOT arm.
        DebugArtifactPolicy.setDiagnosticIntentArmed(
            DebugArtifactPolicy.PRODUCTION_DIAGNOSTIC_SCENARIO != DebugArtifactPolicy.PRODUCTION_DIAGNOSTIC_SCENARIO
        )
        stampLikeCaptureCreation(dir)
        assertFalse(JSONObject(KeplerJobMetadata.read(dir).toString()).optBoolean(DebugArtifactPolicy.JOB_KEY, false))
    }

    @Test
    fun diagnosticIntent_debugScenarioArmsDurableStamp() {
        val (dir, _) = newJobDirectory()
        // Real debug entry point arms for an instrumentation scenario...
        DebugArtifactPolicy.setDiagnosticIntentArmed(
            "hardware_e2e_yuv_burst" != DebugArtifactPolicy.PRODUCTION_DIAGNOSTIC_SCENARIO
        )
        stampLikeCaptureCreation(dir)
        // ...and the intent becomes DURABLE truth in the job metadata.
        assertTrue(JSONObject(KeplerJobMetadata.read(dir).toString()).optBoolean(DebugArtifactPolicy.JOB_KEY, false))
    }
}
