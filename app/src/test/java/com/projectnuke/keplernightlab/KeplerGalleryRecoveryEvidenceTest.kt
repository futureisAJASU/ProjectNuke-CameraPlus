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

    @Test
    fun verifiedRawSidecarAloneDoesNotMakeMainExportPresent() {
        val root = Files.createTempDirectory("gallery-sidecar-only-").toFile()
        val job = java.io.File(root, "KPL_RAW_FUSION_sidecar").apply { mkdirs() }
        try {
            KeplerJobMetadata.write(job, JSONObject().put("jobType", "RAW_NIGHT_FUSION").put("status", "COMPLETE"))
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.RAW_DNG_SIDECAR, 0, "frame_00.dng",
                "Pictures/Kepler/RAW", "image/x-adobe-dng", Uri.parse("content://media/external/file"),
                expectedSizeBytes = 4L, expectedSha256 = "0".repeat(64)
            ).transition(job, MediaStoreExportState.VERIFIED, "content://media/external/file/1")
            assertEquals(false, readKeplerGalleryJob(job).finalExportExists)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun failedMainJournalDoesNotMakeGalleryClaimFinalExport() {
        val root = Files.createTempDirectory("gallery-failed-main-").toFile()
        val job = java.io.File(root, "KPL_YUV_FUSION_failed").apply { mkdirs() }
        try {
            KeplerJobMetadata.write(job, JSONObject().put("jobType", "YUV_NIGHT_FUSION").put("status", "FAILED"))
            MediaStoreExportJournal.create(
                job, MediaStoreExportRole.MAIN_IMAGE, null, "result.jpg",
                "Pictures/Kepler", "image/jpeg", Uri.parse("content://media/external/images/media")
            ).transition(job, MediaStoreExportState.CLEANUP_REQUIRED, "content://media/external/images/media/2")
                .markTerminalPersisted(job)
            assertEquals(false, readKeplerGalleryJob(job).finalExportExists)
        } finally { root.deleteRecursively() }
    }
}
