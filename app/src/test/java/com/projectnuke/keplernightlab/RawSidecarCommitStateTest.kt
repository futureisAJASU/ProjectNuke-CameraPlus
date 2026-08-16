package com.projectnuke.keplernightlab

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class RawSidecarCommitStateTest {
    private val LINKAGE = "content://media/external/images/media/77"
    private val SIDECAR_URI = "content://media/external/images/media/99"

    private class FakeAccess(
        var mainVerified: Boolean = true,
        var sidecarInconclusive: Boolean = true
    ) : MediaStoreExportRecoveryAccess {
        override fun inspect(uri: Uri, journal: MediaStoreExportJournal) =
            if (journal.role == MediaStoreExportRole.RAW_DNG_SIDECAR && sidecarInconclusive) {
                MediaStoreExportInspection(
                    exists = false,
                    pending = false,
                    verified = false,
                    message = "sidecar row inspection failed",
                    inspectionFailed = true
                )
            } else {
                MediaStoreExportInspection(
                    exists = true,
                    pending = false,
                    verified = mainVerified
                )
            }
        override fun setPending(uri: Uri, pending: Boolean) = pending
        override fun delete(uri: Uri) = true
    }

    private fun writeSidecarJob(dir: File): File {
        val frames = org.json.JSONArray()
        frames.put(JSONObject()
            .put("frameIndex", 0)
            .put("dngSidecarStatus", "LOCAL_SAVED")
            .put("dngFile", "frame_00.dng"))
        KeplerJobMetadata.write(dir, JSONObject()
            .put("currentPipelineStage", "FAILED")
            .put("processStatus", "COMMIT_UNKNOWN")
            .put("exportCommitState", GalleryExportCommitState.UNKNOWN.name)
            .put("exportStatus", "COMMIT_UNKNOWN")
            .put("exportUri", LINKAGE)
            .put("galleryPublicExportLinkage", LINKAGE)
            .put("exportVerified", false)
            .put("galleryExportCommitted", false)
            .put(TERMINAL_OPERATION_ID, "operation-terminal")
            .put("frames", frames))
        return dir
    }

    private fun writeSidecarDng(dir: File): MediaStoreExportJournal {
        val dng = File(dir, "frame_00.dng")
        dng.writeBytes(byteArrayOf(0x49, 0x49, 0x2a, 0, 0x08, 0, 0, 0) + ByteArray(48))
        val digest = NoFollowFileSystem.digestVerified(dng)
        val main = MediaStoreExportJournal.create(
            jobDir = dir,
            role = MediaStoreExportRole.MAIN_IMAGE,
            frameIndex = null,
            displayName = "Kepler_result.jpg",
            relativePath = "Pictures/Kepler",
            mimeType = "image/jpeg",
            collectionUri = Uri.parse("content://media/external/images/media")
        )
            .transition(dir, MediaStoreExportState.ROW_INSERTED, LINKAGE)
            .transition(dir, MediaStoreExportState.CONTENT_WRITTEN)
        val sidecar = MediaStoreExportJournal.create(
            jobDir = dir,
            role = MediaStoreExportRole.RAW_DNG_SIDECAR,
            frameIndex = 0,
            displayName = "Kepler_frame_00.dng",
            relativePath = "Pictures/Kepler",
            mimeType = "image/x-adobe-dng",
            collectionUri = Uri.parse("content://media/external/images/media"),
            expectedSizeBytes = digest.size,
            expectedSha256 = digest.sha256
        )
            .transition(dir, MediaStoreExportState.ROW_INSERTED, SIDECAR_URI)
            .transition(dir, MediaStoreExportState.CONTENT_WRITTEN)
        assertTrue(main.uri == LINKAGE)
        return sidecar
    }

    private fun sidecarFrame(job: JSONObject): JSONObject =
        job.optJSONArray("frames").getJSONObject(0)

    // ---- Phase 12A: per-frame classification of commit truth ----

    @Test
    fun unknownCommitStateSidecarIsCommitUnknownNotProvenCommitted() {
        assertEquals(
            "PUBLIC_COMMIT_UNKNOWN",
            sidecarFramePublicStatus(GalleryExportCommitState.UNKNOWN, SIDECAR_URI, "commit failed")
        )
    }

    @Test
    fun committedUnverifiedSidecarKeepsItsOwnEvidence() {
        assertEquals(
            "PUBLIC_COMMITTED_UNVERIFIED",
            sidecarFramePublicStatus(GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED, SIDECAR_URI, "verification failed")
        )
    }

    @Test
    fun verifiedSidecarIsPublicExported() {
        assertEquals(
            "PUBLIC_EXPORTED",
            sidecarFramePublicStatus(GalleryExportCommitState.VERIFIED, SIDECAR_URI, null)
        )
    }

    @Test
    fun notCommittedSidecarIsPublicExportFailed() {
        assertEquals(
            "PUBLIC_EXPORT_FAILED",
            sidecarFramePublicStatus(GalleryExportCommitState.NOT_COMMITTED, null, "insert failed")
        )
    }

    @Test
    fun legacyNoCommitStateDerivesStatusFromUriAndFailure() {
        assertEquals("PUBLIC_COMMITTED_UNVERIFIED", sidecarFramePublicStatus(null, SIDECAR_URI, "abandon"))
        assertEquals("PUBLIC_EXPORTED", sidecarFramePublicStatus(null, SIDECAR_URI, null))
        assertEquals("PUBLIC_EXPORT_FAILED", sidecarFramePublicStatus(null, null, "failed"))
        assertEquals("NOT_ATTEMPTED", sidecarFramePublicStatus(null, null, null))
    }

    // ---- Phase 12B: aggregate kind with committed-unverified evidence ----

    @Test
    fun committedUnverifiedOnlySidecarAggregatesToPartial() {
        val frame = RawSidecarFrameResult(
            frameIndex = 0, requested = true, localFilename = "frame_00.dng",
            localStatus = "LOCAL_SAVED", localFailure = null,
            publicStatus = "PUBLIC_COMMITTED_UNVERIFIED", publicUri = SIDECAR_URI,
            publicFailure = "verification failed"
        )
        assertEquals(
            RawSidecarOutcomeKind.PARTIAL,
            rawSidecarAggregateKind(complete = false, frameResults = listOf(frame), exported = emptyList())
        )
    }

    @Test
    fun commitUnknownOnlySidecarDoesNotClaimPartial() {
        val frame = RawSidecarFrameResult(
            frameIndex = 0, requested = true, localFilename = "frame_00.dng",
            localStatus = "LOCAL_SAVED", localFailure = null,
            publicStatus = "PUBLIC_COMMIT_UNKNOWN", publicUri = SIDECAR_URI,
            publicFailure = "commit result unknown"
        )
        assertEquals(
            RawSidecarOutcomeKind.FAILED,
            rawSidecarAggregateKind(complete = false, frameResults = listOf(frame), exported = emptyList())
        )
    }

    @Test
    fun completeSidecarSetAggregatesToComplete() {
        val frames = listOf(
            RawSidecarFrameResult(0, true, "frame_00.dng", "LOCAL_SAVED", null, "PUBLIC_EXPORTED", SIDECAR_URI, null),
            RawSidecarFrameResult(1, true, "frame_01.dng", "LOCAL_SAVED", null, "PUBLIC_EXPORTED", SIDECAR_URI, null)
        )
        assertEquals(
            RawSidecarOutcomeKind.COMPLETE,
            rawSidecarAggregateKind(complete = true, frameResults = frames, exported = listOf("a", "b"))
        )
    }

    // ---- Phase 12A: missing filenames never include committed or commit-unknown evidence ----

    @Test
    fun missingFilenamesExcludeCommitEvidence() {
        fun frame(status: String) = RawSidecarFrameResult(
            frameIndex = 0, requested = true, localFilename = "frame_00.dng",
            localStatus = "LOCAL_SAVED", localFailure = null,
            publicStatus = status, publicUri = null, publicFailure = null
        )
        val frames = listOf(
            frame("PUBLIC_EXPORT_FAILED"), frame("NOT_ATTEMPTED"),
            frame("PUBLIC_COMMIT_UNKNOWN"), frame("PUBLIC_COMMITTED_UNVERIFIED"), frame("PUBLIC_EXPORTED")
        )
        assertEquals(
            listOf("frame_00.dng", "frame_00.dng"),
            rawSidecarMissingFilenames(frames)
        )
    }

    // ---- Phase 13: classification-driven reconstruction ----

    @Test
    fun committedUnverifiedClassificationPreservesEvidenceAsCommittedUnverified() {
        val dir = Files.createTempDirectory("recon-committed-unverified-").toFile()
        try {
            writeSidecarJob(dir)
            val sidecar = writeSidecarDng(dir)
                .transition(dir, MediaStoreExportState.PUBLIC_COMMITTED, SIDECAR_URI)
            var observed: JSONObject? = null
            KeplerJobMetadata.update(dir) { job ->
                reconstructRawSidecarJournalEvidence(
                    dir, job, listOf(sidecar),
                    classifications = mapOf(sidecar.exportAttemptId to MediaStoreExportRecoveryClassification.PUBLIC_COMMITTED_UNVERIFIED)
                )
                observed = job
            }
            assertEquals("PUBLIC_COMMITTED_UNVERIFIED", sidecarFrame(observed!!).optString("dngSidecarPublicStatus"))
            assertEquals(SIDECAR_URI, sidecarFrame(observed).optString("publicDngUri"))
            assertEquals(0, KeplerJobMetadata.read(dir).optInt("rawSidecarPublicExportedCount"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ambiguousClassificationPreservesUriAsCommitUnknown() {
        val dir = Files.createTempDirectory("recon-ambiguous-").toFile()
        try {
            writeSidecarJob(dir)
            val sidecar = writeSidecarDng(dir)
            var observed: JSONObject? = null
            KeplerJobMetadata.update(dir) { job ->
                reconstructRawSidecarJournalEvidence(
                    dir, job, listOf(sidecar),
                    classifications = mapOf(sidecar.exportAttemptId to MediaStoreExportRecoveryClassification.AMBIGUOUS)
                )
                observed = job
            }
            assertEquals("PUBLIC_COMMIT_UNKNOWN", sidecarFrame(observed!!).optString("dngSidecarPublicStatus"))
            assertEquals(SIDECAR_URI, sidecarFrame(observed).optString("publicDngUri"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun commitMissingClassificationDropsClaimedUri() {
        val dir = Files.createTempDirectory("recon-commit-missing-").toFile()
        try {
            writeSidecarJob(dir)
            val sidecar = writeSidecarDng(dir)
            var observed: JSONObject? = null
            KeplerJobMetadata.update(dir) { job ->
                reconstructRawSidecarJournalEvidence(
                    dir, job, listOf(sidecar),
                    classifications = mapOf(sidecar.exportAttemptId to MediaStoreExportRecoveryClassification.PUBLIC_COMMIT_MISSING)
                )
                observed = job
            }
            assertEquals("PUBLIC_NOT_RECOVERED", sidecarFrame(observed!!).optString("dngSidecarPublicStatus"))
            assertFalse(sidecarFrame(observed).has("publicDngUri"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifiedClassificationCountsRecoveredEvidence() {
        val dir = Files.createTempDirectory("recon-verified-").toFile()
        try {
            writeSidecarJob(dir)
            val sidecar = writeSidecarDng(dir)
            var observed: JSONObject? = null
            KeplerJobMetadata.update(dir) { job ->
                reconstructRawSidecarJournalEvidence(
                    dir, job, listOf(sidecar),
                    classifications = mapOf(sidecar.exportAttemptId to MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED)
                )
                observed = job
            }
            assertEquals("PUBLIC_EXPORTED", sidecarFrame(observed!!).optString("dngSidecarPublicStatus"))
            assertEquals(SIDECAR_URI, sidecarFrame(observed).optString("publicDngUri"))
            assertEquals(1, KeplerJobMetadata.read(dir).optInt("rawSidecarPublicExportedCount"))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- Phase 14: settlement converges sidecar frames by their own evidence ----

    @Test
    fun settleConvergesMainAndRefreshesInconclusiveSidecarTruth() {
        val dir = Files.createTempDirectory("settle-sidecar-truth-").toFile()
        try {
            writeSidecarJob(dir)
            val sidecar = writeSidecarDng(dir)
            val settled = settleUnknownPublicCommitState(
                RuntimeEnvironment.getApplication() as Context,
                dir,
                FakeAccess(mainVerified = true, sidecarInconclusive = true)
            )
            assertTrue(settled)
            val job = KeplerJobMetadata.read(dir)
            assertEquals(GalleryExportCommitState.VERIFIED.name, job.optString("exportCommitState"))
            assertEquals("PUBLIC_COMMIT_UNKNOWN", sidecarFrame(job).optString("dngSidecarPublicStatus"))
            assertEquals(SIDECAR_URI, sidecarFrame(job).optString("publicDngUri"))
            assertEquals(0, job.optInt("rawSidecarPublicExportedCount"))
            assertEquals(1, job.optInt("rawSidecarPublicFailedCount"))
            assertEquals(MediaStoreExportState.CONTENT_WRITTEN, sidecar.state)
        } finally {
            dir.deleteRecursively()
        }
    }
}