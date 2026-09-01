package com.projectnuke.keplernightlab

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.file.Files

/**
 * Strengthens the R3.1 metadata write-source counter regression by exercising the actual
 * [R3GalleryColdMeasurement.measureMetadataWrite] classification path (content-changing vs
 * same-content) rather than only [R3GalleryColdMeasurement.metadataContentChanged].
 *
 * Test-only: no new public production API is exposed.
 */
@RunWith(RobolectricTestRunner::class)
class KeplerR3WriteCounterClassificationTest {

    @Test
    fun measureMetadataWrite_classifiesContentChangingAndSameContent() {
        val context: Context = RuntimeEnvironment.getApplication()
        val runId = "r4hosttest12345"
        val control = R3GalleryColdMeasurement.controlFile(context)
        val job = Files.createTempDirectory("r4-counter-").toFile()
        try {
            control.parentFile?.mkdirs()
            control.writeText("""{"runId":"$runId"}""")

            R3GalleryColdMeasurement.onProcessStart(context)
            KeplerJobMetadata.write(job, JSONObject().put("k", "0"))

            R3GalleryColdMeasurement.recoveryStarted()

            KeplerJobMetadata.update(
                job,
                R3GalleryColdMeasurement.MetadataWriteSource.RECONSTRUCT_MAIN_EXPORT
            ) { it.put("k", "1") }

            KeplerJobMetadata.update(
                job,
                R3GalleryColdMeasurement.MetadataWriteSource.RECONSTRUCT_MAIN_EXPORT
            ) { /* no-op: equal content */ }

            R3GalleryColdMeasurement.recoveryFinished(KeplerRecoveryReport(emptyList()))
            R3GalleryColdMeasurement.galleryReady(context, 0)

            val result = JSONObject(R3GalleryColdMeasurement.resultFile(context, runId).readText())
            val source = result.getJSONObject("metadata").getJSONObject("bySource")
                .getJSONObject("RECONSTRUCT_MAIN_EXPORT")

            assertEquals("changed content must increment content-changing",
                1, source.getInt("contentChangingWrites"))
            assertEquals("equal content must increment same-content",
                1, source.getInt("sameContentWrites"))
            assertEquals(2, source.getInt("writeAttempts"))
        } finally {
            control.delete()
            R3GalleryColdMeasurement.onProcessStart(context)
            job.deleteRecursively()
        }
    }
}