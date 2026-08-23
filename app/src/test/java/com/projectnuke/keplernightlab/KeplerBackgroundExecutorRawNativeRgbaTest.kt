package com.projectnuke.keplernightlab

import android.graphics.Bitmap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory

/**
 * Phase 3 regression: the standard successful RAW path intentionally returns
 * success = true, nativeRgbaFile = raw_fusion_final.rgba, finalPngFile = null,
 * previewPngFile = null.  The background executor previously classified that
 * shape as "RAW fusion failed" because it selected sources with
 * `finalPngFile ?: previewPngFile`.  The executor must use the established RAW
 * export-source abstraction (hasExportableBitmapSource / loadExportBitmap) with
 * the same production semantics as the mature RAW reprocess/export path.
 */
@RunWith(RobolectricTestRunner::class)
class KeplerBackgroundExecutorRawNativeRgbaTest {

    private val root = createTempDirectory("raw-bg-rgba").toFile()

    private fun newJobDir(prefix: String, jobJson: JSONObject? = null): File =
        root.resolve("${prefix}_${System.nanoTime()}").apply {
            mkdirs()
            jobJson?.let { File(this, JOB_JSON_FILE_NAME).writeText(it.toString()) }
        }

    private fun writeEncodedPng(jobDir: File, name: String, width: Int, height: Int): File {
        val file = File(jobDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    /**
     * The physical S24 evidence shape: merged raw16 + raw_fusion_final.rgba and
     * NO PNG artifacts on the standard success path.
     */
    private fun physicalShapeResult(
        jobDir: File,
        width: Int = 12,
        height: Int = 8,
        success: Boolean = true,
        outputCommitted: Boolean = true
    ): RawFusionProcessResult {
        val rgba = File(jobDir, "raw_fusion_final.rgba")
        rgba.writeBytes(ByteArray(width * height * 4) { index -> (index % 251).toByte() })
        val merged = File(jobDir, "merged_raw_classic_v1.raw16")
        merged.writeBytes(ByteArray(64))
        return RawFusionProcessResult(
            success = success,
            mergedRawFile = merged,
            mergedDngFile = null,
            previewPngFile = null,
            finalPngFile = null,
            errorMessage = null,
            nativeRgbaFile = rgba,
            nativeRgbaWidth = width,
            nativeRgbaHeight = height,
            outputCommitted = outputCommitted
        )
    }

    // ------------------------------------------------------------------
    // Exportability truth
    // ------------------------------------------------------------------

    @Test
    fun backgroundRaw_nativeRgbaOnlyResult_isExportable() {
        val jobDir = newJobDir("exportable")
        val result = physicalShapeResult(jobDir)
        assertTrue(result.hasExportableBitmapSource())
        assertFalse(rawBackgroundFusionFailed(result))
    }

    @Test
    fun backgroundRaw_nativeRgbaOnlyResult_doesNotReportFusionFailed() {
        val jobDir = newJobDir("notFailed")
        val result = physicalShapeResult(jobDir)
        assertFalse(
            "native-RGBA-only success must never be reported as RAW fusion failed",
            rawBackgroundFusionFailed(result)
        )
        // A genuine failure stays a failure.
        assertTrue(rawBackgroundFusionFailed(result.copy(success = false)))
        // Established abstraction semantics: a preview PNG is NOT an exportable
        // source on its own — the mature reprocess/export path requires native
        // RGBA or a final PNG.
        val previewOnly = physicalShapeResult(newJobDir("previewOnly"))
            .copy(
                nativeRgbaFile = null,
                nativeRgbaWidth = 0,
                nativeRgbaHeight = 0,
                previewPngFile = writeEncodedPng(newJobDir("previewDir"), "raw_fused_classic_v1_preview.png", 8, 8)
            )
        assertTrue(rawBackgroundFusionFailed(previewOnly))
    }

    // ------------------------------------------------------------------
    // Established loader policy
    // ------------------------------------------------------------------

    @Test
    fun backgroundRaw_usesEstablishedLoadExportBitmapPolicy() {
        // Legacy orientation metadata: loader resolves 0 degrees and loads the
        // native RGBA directly — no PNG decode, no invented source selection.
        val legacyJob = newJobDir("legacy", JSONObject())
        val loaded = physicalShapeResult(legacyJob).loadExportBitmap(legacyJob)
        assertEquals("native_rgba_direct", loaded.source)
        assertTrue(loaded.nativeRgbaDirect)
        assertEquals(0, loaded.appliedRotationDegrees)
        assertEquals(12, loaded.bitmap.width)
        assertEquals(8, loaded.bitmap.height)
        loaded.bitmap.recycle()

        // Rotation metadata is honored exactly like the mature export path:
        // sensor=90, back camera, display rotation 0 -> 90 degrees applied and
        // dimensions swapped; the pre-rotation bitmap is recycled internally.
        val rotatedJob = newJobDir(
            "rotated",
            JSONObject()
                .put("sourceOrientationState", "SENSOR_GRID")
                .put("sensorOrientation", 90)
                .put("displayRotationAtCapture", 0)
                .put("lensFacing", 1)
                .put("exportSourceWasDisplayUpright", false)
                .put("rotationAppliedAtExportStage", false)
        )
        val rotated = physicalShapeResult(rotatedJob).loadExportBitmap(rotatedJob)
        assertEquals("native_rgba_direct", rotated.source)
        assertEquals(90, rotated.appliedRotationDegrees)
        assertEquals(8, rotated.bitmap.width)
        assertEquals(12, rotated.bitmap.height)
        rotated.bitmap.recycle()

        // Final-PNG fallback remains available when no native RGBA exists.
        val pngJob = newJobDir("pngFallback", JSONObject())
        val pngResult = physicalShapeResult(pngJob).copy(
            nativeRgbaFile = null,
            nativeRgbaWidth = 0,
            nativeRgbaHeight = 0,
            finalPngFile = writeEncodedPng(pngJob, "raw_fusion_final.png", 12, 8)
        )
        val fallback = pngResult.loadExportBitmap(pngJob)
        assertEquals("final_png_decode", fallback.source)
        assertFalse(fallback.nativeRgbaDirect)
        fallback.bitmap.recycle()
    }

    @Test
    fun backgroundRaw_nativeRgbaPhysicalShapeLoaderRegression() {
        // raw_fusion_final.rgba decodes through the established loader to a real
        // ARGB_8888 bitmap of the persisted dimensions without any PNG present.
        val jobDir = newJobDir("physical", JSONObject().put("sourceOrientationState", "DISPLAY_UPRIGHT"))
        val result = physicalShapeResult(jobDir)
        assertTrue(result.hasExportableBitmapSource())
        val loaded = result.loadExportBitmap(jobDir)
        assertNotNull(loaded.bitmap)
        assertFalse(loaded.bitmap.isRecycled)
        assertEquals(Bitmap.Config.ARGB_8888, loaded.bitmap.config)
        assertEquals(12, loaded.bitmap.width)
        assertEquals(8, loaded.bitmap.height)
        loaded.bitmap.recycle()
    }

    // ------------------------------------------------------------------
    // Export stage emission for the native-RGBA success shape
    // ------------------------------------------------------------------

    @Test
    fun backgroundRaw_exportStageEmittedForNativeRgba() {
        val collector = CopyOnWriteArrayList<CameraPipelineEvent>()
        val jobDir = newJobDir("stageEmit", JSONObject())
        val result = physicalShapeResult(jobDir, outputCommitted = true)
        val spec = KeplerBackgroundExecutor.runRawBackgroundExportStage(
            appContext = RuntimeEnvironment.getApplication(),
            jobDir = jobDir,
            lease = null,
            finalOutputFormat = FinalOutputFormat.HEIF,
            result = result,
            counts = CameraPipelineProgressCounts(requestedFrames = 4, savedFrames = 4),
            emit = { event -> collector.add(event) },
            post = { _ -> }
        )
        // ExportStage IS emitted for the native-RGBA success shape — the old
        // source selection short-circuited to "RAW fusion failed" instead.
        val exportIndex = collector.indexOfFirst { it is CameraPipelineEvent.ExportStage }
        assertTrue("ExportStage must be emitted", exportIndex >= 0)
        // Terminal publication belongs to the caller, not the stage helper.
        assertEquals(-1, collector.indexOfFirst { it is CameraPipelineEvent.Terminal })
        // Exact terminal truth keeps the required-output claim.
        assertTrue(spec.requiredOutputCommitted)
    }
}
