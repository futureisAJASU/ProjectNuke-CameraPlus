package com.projectnuke.keplernightlab

import org.json.JSONArray
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

    @Test
    fun gallerySummary_persistedCanReprocessTrue_butCanonicalMissing_reportsFalse() {
        val dir = tmp.newFolder()
        KeplerJobMetadata.write(
            dir,
            JSONObject()
                .put("jobType", "RAW_NIGHT_FUSION")
                .put("canReprocess", true)
        )
        try {
            val summary = read(dir)
            assertFalse(summary.sourceFramesAvailable)
            assertFalse(summary.canReprocess)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun gallerySummary_persistedCanReprocessFalse_sourcesExist_remainsFalse() {
        val dir = tmp.newFolder()
        val frames = JSONArray().put(
            JSONObject().put("raw16File", "source_001.raw16")
        )
        KeplerJobMetadata.write(
            dir,
            JSONObject()
                .put("jobType", "RAW_NIGHT_FUSION")
                .put("canReprocess", false)
                .put("frames", frames)
        )
        File(dir, "source_001.raw16").writeBytes(byteArrayOf(1))
        try {
            val summary = read(dir)
            assertTrue(summary.sourceFramesAvailable)
            assertFalse(summary.canReprocess)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun gallerySummary_nonFrameMetadataCanonicalSource_reportsAvailable() {
        val dir = tmp.newFolder()
        val frames = JSONArray()
        frames.put(JSONObject().put("raw16File", "source_001.raw16"))
        frames.put(JSONObject().put("raw16File", "source_002.raw16"))
        KeplerJobMetadata.write(
            dir,
            JSONObject()
                .put("jobType", "RAW_NIGHT_FUSION")
                .put("frames", frames)
        )
        File(dir, "source_001.raw16").writeBytes(byteArrayOf(1))
        File(dir, "source_002.raw16").writeBytes(byteArrayOf(2))
        try {
            val summary = read(dir)
            assertTrue(summary.sourceFramesAvailable)
            assertTrue(summary.canReprocess)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun packedGallerySummary_yuvpackExistsDerivedPngMissing_reportsReprocessable() {
        val dir = tmp.newFolder()
        val frames = JSONArray()
        frames.put(JSONObject()
            .put("file", "frame_00_color.png")
            .put("packedSourceFilename", "legacy_source_a.yuvpack"))
        frames.put(JSONObject()
            .put("file", "frame_01_color.png")
            .put("packedSourceFilename", "legacy_source_b.yuvpack"))
        KeplerJobMetadata.write(
            dir,
            JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("yuvPersistenceStrategy", "PACKED_YUV_V1")
                .put("frames", frames)
        )
        File(dir, "legacy_source_a.yuvpack").writeBytes(byteArrayOf(1, 2, 3))
        File(dir, "legacy_source_b.yuvpack").writeBytes(byteArrayOf(4, 5, 6))
        try {
            val summary = read(dir)
            assertTrue(summary.sourceFramesAvailable)
            assertTrue(summary.canReprocess)
        } finally {
            dir.deleteRecursively()
        }
    }
}
