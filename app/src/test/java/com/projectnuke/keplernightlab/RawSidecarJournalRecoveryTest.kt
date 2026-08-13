package com.projectnuke.keplernightlab

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RawSidecarJournalRecoveryTest {
    @Test
    fun verifiedSidecarJournalReconstructsFrameUriAfterFreshProcess() {
        val dir = Files.createTempDirectory("sidecar-reconstruct-").toFile()
        try {
            val dng = File(dir, "frame_02.dng")
            dng.writeBytes(byteArrayOf(0x49, 0x49, 0x2a, 0x00, 0x01))
            val journal = MediaStoreExportJournal.create(
                jobDir = dir,
                role = MediaStoreExportRole.RAW_DNG_SIDECAR,
                frameIndex = 2,
                displayName = "frame_02.dng",
                relativePath = "Pictures/Kepler/RAW",
                mimeType = "image/x-adobe-dng",
                collectionUri = Uri.parse("content://media/external/file"),
                expectedSizeBytes = dng.length(),
                expectedSha256 = NoFollowFileSystem.digestVerified(dng).sha256
            ).transition(dir, MediaStoreExportState.VERIFIED, "content://media/external/file/2")
            val job = JSONObject().put("frames", JSONArray().put(JSONObject()
                .put("frameIndex", 2)
                .put("dngFile", dng.name)
                .put("dngSidecarStatus", "LOCAL_SAVED")))
            KeplerJobMetadata.write(dir, job)
            assertEquals(1, loadRawSidecarManifest(dir).expected.size)
            assertEquals(dng.length(), journal.expectedSizeBytes)
            assertEquals(NoFollowFileSystem.digestVerified(dng).sha256, journal.expectedSha256)
            val count = reconstructRawSidecarJournalEvidence(dir, job, listOf(journal))
            assertEquals(1, count)
            assertEquals("PUBLIC_EXPORTED", job.getJSONArray("frames").getJSONObject(0).getString("dngSidecarPublicStatus"))
            assertEquals(journal.uri, job.getJSONArray("frames").getJSONObject(0).getString("publicDngUri"))
            assertEquals(1, job.getInt("rawSidecarPublicExportedCount"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun historicalDigestDoesNotAttachToChangedCurrentDng() {
        val dir = Files.createTempDirectory("sidecar-digest-").toFile()
        try {
            val dng = File(dir, "frame_01.dng")
            dng.writeBytes(byteArrayOf(0x49, 0x49, 0x2a, 0x00))
            val historical = MediaStoreExportJournal.create(
                dir, MediaStoreExportRole.RAW_DNG_SIDECAR, 1, dng.name,
                "Pictures/Kepler/RAW", "image/x-adobe-dng", Uri.parse("content://media/external/file"),
                expectedSizeBytes = 4L,
                expectedSha256 = "0".repeat(64)
            ).transition(dir, MediaStoreExportState.VERIFIED, "content://media/external/file/1")
            val job = JSONObject().put("frames", JSONArray().put(JSONObject()
                .put("frameIndex", 1)
                .put("dngFile", dng.name)
                .put("dngSidecarStatus", "LOCAL_SAVED")))
            KeplerJobMetadata.write(dir, job)
            assertEquals(0, reconstructRawSidecarJournalEvidence(dir, job, listOf(historical)))
            assertEquals("PUBLIC_NOT_RECOVERED", job.getJSONArray("frames").getJSONObject(0).getString("dngSidecarPublicStatus"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifiedHistoricalSidecarCanBeReconstructedForCurrentOperationByExactSourceIdentity() {
        val dir = Files.createTempDirectory("sidecar-historical-reuse-").toFile()
        try {
            val dng = File(dir, "frame_04.dng").apply { writeBytes(byteArrayOf(0x49, 0x49, 0x2a, 0x00, 5)) }
            val journal = MediaStoreExportJournal.create(
                dir, MediaStoreExportRole.RAW_DNG_SIDECAR, 4, dng.name,
                "Pictures/Kepler/RAW", "image/x-adobe-dng", Uri.parse("content://media/external/file"),
                expectedSizeBytes = dng.length(),
                expectedSha256 = NoFollowFileSystem.digestVerified(dng).sha256,
                ownerOperationId = "historical-operation"
            ).transition(dir, MediaStoreExportState.VERIFIED, "content://media/external/file/4")
            val job = JSONObject().put("frames", JSONArray().put(JSONObject()
                .put("frameIndex", 4)
                .put("dngFile", dng.name)
                .put("dngSidecarStatus", "LOCAL_SAVED")))
            KeplerJobMetadata.write(dir, job)
            assertEquals(1, reconstructRawSidecarJournalEvidence(dir, job, listOf(journal)))
            assertEquals("content://media/external/file/4", job.getJSONArray("frames").getJSONObject(0).getString("publicDngUri"))
        } finally { dir.deleteRecursively() }
    }
}
