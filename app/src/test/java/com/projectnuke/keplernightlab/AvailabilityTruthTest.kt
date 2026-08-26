package com.projectnuke.keplernightlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Phase 13 — local/public availability fields are independent truths.
 * A historical VERIFIED journal is evidence a result EXISTED — never that it still exists today;
 * current public availability follows recovery-reconciled durable claims only.
 */
@RunWith(RobolectricTestRunner::class)
class AvailabilityTruthTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun jobWith(metadata: JSONObject, files: Map<String, ByteArray> = emptyMap()): File {
        val dir = tmp.newFolder()
        KeplerJobMetadata.write(dir, metadata.put("jobType", "YUV_NIGHT_FUSION"))
        files.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
        return dir
    }

    private fun read(dir: File): KeplerGalleryJobSummary = readKeplerGalleryJob(dir)

    @Test
    fun verifiedClaim_presentLocalFinal_allAvailabilityFieldsTrue() {
        val dir = jobWith(
            JSONObject()
                .put("recoveryState", "STABLE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/external/images/media/5")
                .put("galleryDisplayFile", "final.png"),
            files = mapOf("final.png" to byteArrayOf(1))
        )
        try {
            val summary = read(dir)
            assertTrue(summary.localFinalAvailable)
            assertTrue(summary.publicResultAvailable)
            assertEquals("있음", summary.publicResultStateText)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun externallyRemoved_publicUnavailable_localAndSourcesRemainIndependent() {
        val dir = jobWith(
            JSONObject()
                .put("recoveryState", "STABLE")
                .put("exportStatus", "REMOVED_EXTERNALLY")
                .put("publicResultAvailable", false)
                .put("lastVerifiedExportUri", "content://media/external/images/media/5")
                .put("galleryDisplayFile", "final.png"),
            files = mapOf(
                "final.png" to byteArrayOf(1),
                "frame_01_color.png" to byteArrayOf(2),
                "frame_02_color.png" to byteArrayOf(3)
            )
        )
        try {
            val summary = read(dir)
            // Local truth is untouched by the public removal.
            assertTrue(summary.localFinalAvailable)
            assertEquals(2, summary.frames.count { it.file != null })
            assertTrue(summary.sourceFramesAvailable)
            // Public result truthfully reports removal, never historical inference.
            assertFalse(summary.publicResultAvailable)
            assertEquals("삭제됨", summary.publicResultStateText)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun historicalJournalAlone_neverCountsAsCurrentPublicAvailability() {
        // Claims are false; only a stale VERIFIED journal exists. That is history, not presence.
        val dir = tmp.newFolder()
        KeplerJobMetadata.write(
            dir,
            JSONObject().put("jobType", "YUV_NIGHT_FUSION").put("recoveryState", "STABLE")
        )
        try {
            android.net.Uri.parse("content://media/external/images/media").let {
                MediaStoreExportJournal.create(
                    jobDir = dir,
                    role = MediaStoreExportRole.MAIN_IMAGE,
                    frameIndex = null,
                    displayName = "old.jpg",
                    relativePath = "Pictures/Kepler",
                    mimeType = "image/jpeg",
                    collectionUri = it
                ).transition(dir, MediaStoreExportState.VERIFIED, "content://media/external/images/media/77")
            }
            val summary = read(dir)
            assertFalse(summary.publicResultAvailable)
            assertEquals("확인 필요", summary.publicResultStateText)
            assertFalse(summary.localFinalAvailable)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sourcesOnly_metadataCanReprocessFalse_disablesReprocessFlag() {
        val dir = jobWith(
            JSONObject()
                .put("recoveryState", "STABLE")
                .put("canReprocess", false),
            files = mapOf("frame_01_color.png" to byteArrayOf(1))
        )
        try {
            val summary = read(dir)
            assertTrue(summary.sourceFramesAvailable)
            assertFalse(summary.canReprocess)
        } finally {
            dir.deleteRecursively()
        }
    }
}
