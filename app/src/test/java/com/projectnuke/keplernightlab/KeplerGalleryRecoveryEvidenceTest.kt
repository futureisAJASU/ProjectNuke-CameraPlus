package com.projectnuke.keplernightlab

import android.net.Uri
import org.json.JSONObject
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeplerGalleryRecoveryEvidenceTest {
    @Test
    fun verifiedExportJournalMakesGallerySummaryExportPresentWithoutSuccessString() {
        val root = Files.createTempDirectory("gallery-recovery-evidence-").toFile()
        val job = java.io.File(root, "KPL_YUV_FUSION_evidence").apply { mkdirs() }
        try {
            KeplerJobMetadata.write(job, JSONObject().put("jobType", "YUV_NIGHT_FUSION").put("status", "PROCESSING"))
            MediaStoreExportJournal.create(
                jobDir = job,
                role = MediaStoreExportRole.MAIN_IMAGE,
                frameIndex = null,
                displayName = "result.jpg",
                relativePath = "Pictures/Kepler",
                mimeType = "image/jpeg",
                collectionUri = Uri.parse("content://media/external/images/media")
            ).transition(job, MediaStoreExportState.VERIFIED, "content://media/external/images/media/99")
            val summary = readKeplerGalleryJob(job)
            assertTrue(summary.finalExportExists)
            assertEquals("STABLE", summary.recoveryState)
        } finally {
            root.deleteRecursively()
        }
    }
}
