package com.projectnuke.keplernightlab

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class UnknownCommitStateSettlementTest {
    private val LINKAGE = "content://media/external/images/media/77"

    private class FakeAccess(
        var pending: Boolean,
        var verified: Boolean,
        var exists: Boolean = true,
        var commitSucceeds: Boolean = true
    ) : MediaStoreExportRecoveryAccess {
        var deleted = false
        var deleteResult = true
        var committed = false
        var inspectionFailed = false
        override fun inspect(uri: Uri, journal: MediaStoreExportJournal) =
            MediaStoreExportInspection(exists, pending, verified, inspectionFailed = inspectionFailed)
        override fun setPending(uri: Uri, pending: Boolean): Boolean {
            if (!commitSucceeds) return false
            this.pending = pending
            committed = true
            return true
        }
        override fun delete(uri: Uri): Boolean {
            if (!deleteResult) return false
            deleted = true
            exists = false
            return true
        }
    }

    private fun writeUnknownJob(dir: File, linkage: String = LINKAGE): File {
        val jobDir = dir
        KeplerJobMetadata.write(jobDir, JSONObject()
            .put("currentPipelineStage", "FAILED")
            .put("processStatus", "COMMIT_UNKNOWN")
            .put("exportCommitState", GalleryExportCommitState.UNKNOWN.name)
            .put("exportStatus", "COMMIT_UNKNOWN")
            .put("exportUri", linkage)
            .put("galleryPublicExportLinkage", linkage)
            .put("exportVerified", false)
            .put("galleryExportCommitted", false)
            .put(TERMINAL_OPERATION_ID, "operation-terminal"))
        MediaStoreExportJournal.create(
            jobDir = jobDir,
            role = MediaStoreExportRole.MAIN_IMAGE,
            frameIndex = null,
            displayName = "Kepler_result.jpg",
            relativePath = "Pictures/Kepler",
            mimeType = "image/jpeg",
            collectionUri = Uri.parse("content://media/external/images/media")
        )
            .transition(jobDir, MediaStoreExportState.ROW_INSERTED, LINKAGE)
            .transition(jobDir, MediaStoreExportState.CONTENT_WRITTEN)
        return jobDir
    }

    @Test
    fun nonPendingVerifiedRowConvergesToVerifiedSuccessAndUnblocksGate() {
        val dir = Files.createTempDirectory("settle-up-verified-").toFile()
        try {
            writeUnknownJob(dir)
            val access = FakeAccess(pending = false, verified = true)
            assertTrue(settleUnknownPublicCommitState(org.robolectric.RuntimeEnvironment.getApplication(), dir, access))

            val job = KeplerJobMetadata.read(dir)
            assertEquals(GalleryExportCommitState.VERIFIED.name, job.getString("exportCommitState"))
            assertEquals("EXPORTED", job.getString("exportStatus"))
            assertTrue(job.getBoolean("galleryExportCommitted"))
            assertTrue(job.getBoolean("exportVerified"))
            assertEquals("STABLE", job.getString("recoveryState"))
            assertEquals(LINKAGE, job.getString("galleryPublicExportLinkage"))
            assertEquals("Kepler_result.jpg", job.getString("exportDisplayName"))
            assertEquals("image/jpeg", job.getString("exportMimeType"))
            assertEquals(MediaStoreExportState.VERIFIED, MediaStoreExportJournal.list(dir).single().state)
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(dir, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pendingVerifiedRowIsCommittedAndConvergesToVerifiedSuccess() {
        val dir = Files.createTempDirectory("settle-pending-verified-").toFile()
        try {
            writeUnknownJob(dir)
            val access = FakeAccess(pending = true, verified = true)
            assertTrue(settleUnknownPublicCommitState(org.robolectric.RuntimeEnvironment.getApplication(), dir, access))

            assertTrue(access.committed)
            assertFalse(access.deleted)
            val job = KeplerJobMetadata.read(dir)
            assertEquals(GalleryExportCommitState.VERIFIED.name, job.getString("exportCommitState"))
            assertTrue(job.getBoolean("galleryExportCommitted"))
            assertEquals(JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(dir, JobRecoveryMutationIntent.REPROCESS))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun nonPendingUnverifiedRowConvergesToCommittedUnverifiedVerificationDebt() {
        val dir = Files.createTempDirectory("settle-up-unverified-").toFile()
        try {
            writeUnknownJob(dir)
            val access = FakeAccess(pending = false, verified = false)
            assertTrue(settleUnknownPublicCommitState(org.robolectric.RuntimeEnvironment.getApplication(), dir, access))

            val job = KeplerJobMetadata.read(dir)
            assertEquals(GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED.name, job.getString("exportCommitState"))
            assertEquals("COMMITTED_UNVERIFIED", job.getString("exportStatus"))
            assertTrue(job.getBoolean("galleryExportCommitted"))
            assertFalse(job.getBoolean("exportVerified"))
            assertTrue(job.getBoolean("exportVerificationFailed"))
            assertEquals("PUBLIC_EXPORT_COMMITTED_PENDING_VERIFICATION", job.getString("recoveryState"))
            assertEquals(LINKAGE, job.getString("galleryPublicExportLinkage"))
            assertEquals(MediaStoreExportState.PUBLIC_COMMITTED, MediaStoreExportJournal.list(dir).single().state)
            // Committed-but-unverified evidence keeps the mutation gate blocked exactly as
            // restart recovery leaves it, until the committed result is verified. The gate
            // reports the REAL reason: verification debt, not a dead operation.
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_EXPORT_VERIFICATION,
                KeplerJobMetadata.inspectRecoveryMutationGate(dir, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pendingUnverifiedRowIsDeletedAndConvergesToNotCommitted() {
        val dir = Files.createTempDirectory("settle-down-pending-").toFile()
        try {
            writeUnknownJob(dir)
            val access = FakeAccess(pending = true, verified = false)
            assertTrue(settleUnknownPublicCommitState(org.robolectric.RuntimeEnvironment.getApplication(), dir, access))

            assertTrue(access.deleted)
            val job = KeplerJobMetadata.read(dir)
            assertEquals(GalleryExportCommitState.NOT_COMMITTED.name, job.getString("exportCommitState"))
            assertEquals("FAILED", job.getString("exportStatus"))
            assertFalse(job.getBoolean("galleryExportCommitted"))
            assertEquals("STABLE", job.getString("recoveryState"))
            assertFalse(job.has("galleryPublicExportLinkage"))
            assertEquals(MediaStoreExportState.CLEANED, MediaStoreExportJournal.list(dir).single().state)
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(dir, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun absentRowConvergesToNotCommittedAndUnblocksGate() {
        val dir = Files.createTempDirectory("settle-down-absent-").toFile()
        try {
            writeUnknownJob(dir)
            val access = FakeAccess(pending = false, verified = false, exists = false)
            assertTrue(settleUnknownPublicCommitState(org.robolectric.RuntimeEnvironment.getApplication(), dir, access))

            val job = KeplerJobMetadata.read(dir)
            assertEquals(GalleryExportCommitState.NOT_COMMITTED.name, job.getString("exportCommitState"))
            assertEquals("FAILED", job.getString("exportStatus"))
            assertFalse(job.getBoolean("galleryExportCommitted"))
            assertEquals("STABLE", job.getString("recoveryState"))
            assertFalse(job.has("galleryPublicExportLinkage"))
            assertEquals(MediaStoreExportState.CLEANED, MediaStoreExportJournal.list(dir).single().state)
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(dir, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deleteFailureRecordsAmbiguousRecoveryDebtAndRetainsBlockingJournal() {
        val dir = Files.createTempDirectory("settle-down-delete-failure-").toFile()
        try {
            writeUnknownJob(dir)
            val access = FakeAccess(pending = true, verified = false).apply { deleteResult = false }
            assertTrue(settleUnknownPublicCommitState(org.robolectric.RuntimeEnvironment.getApplication(), dir, access))

            val job = KeplerJobMetadata.read(dir)
            assertEquals(GalleryExportCommitState.NOT_COMMITTED.name, job.getString("exportCommitState"))
            assertEquals("FAILED", job.getString("exportStatus"))
            assertFalse(job.getBoolean("galleryExportCommitted"))
            assertEquals("AMBIGUOUS_RECOVERY_REQUIRED", job.getString("recoveryState"))
            assertFalse(job.has("galleryPublicExportLinkage"))
            // The failed delete is retained as blocking evidence, exactly as restart recovery
            // retains DELETE_FAILED for a pending row whose cleanup could not complete. The gate
            // reports the real durable record reason (ambiguous recovery), not a dead operation.
            assertEquals(MediaStoreExportState.CONTENT_WRITTEN, MediaStoreExportJournal.list(dir).single().state)
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_AMBIGUOUS_RECOVERY,
                KeplerJobMetadata.inspectRecoveryMutationGate(dir, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun inconclusiveInspectionLeavesUnknownUntouched() {
        val dir = Files.createTempDirectory("settle-inconclusive-").toFile()
        try {
            writeUnknownJob(dir)
            val access = FakeAccess(pending = true, verified = false).apply { inspectionFailed = true }
            assertFalse(settleUnknownPublicCommitState(org.robolectric.RuntimeEnvironment.getApplication(), dir, access))

            val job = KeplerJobMetadata.read(dir)
            assertEquals(GalleryExportCommitState.UNKNOWN.name, job.getString("exportCommitState"))
            assertEquals(LINKAGE, job.getString("galleryPublicExportLinkage"))
            assertEquals(MediaStoreExportState.CONTENT_WRITTEN, MediaStoreExportJournal.list(dir).single().state)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun noMatchingMainJournalLeavesUnknownUntouched() {
        val dir = Files.createTempDirectory("settle-no-main-").toFile()
        try {
            writeUnknownJob(dir, linkage = "content://media/external/images/media/other")
            assertFalse(
                settleUnknownPublicCommitState(
                    org.robolectric.RuntimeEnvironment.getApplication(),
                    dir,
                    FakeAccess(pending = false, verified = false)
                )
            )
            val job = KeplerJobMetadata.read(dir)
            assertEquals(GalleryExportCommitState.UNKNOWN.name, job.getString("exportCommitState"))
            assertEquals("content://media/external/images/media/other", job.getString("galleryPublicExportLinkage"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun preservesExistingCommittedClaimNeverRollsBack() {
        val dir = Files.createTempDirectory("settle-preserve-committed-").toFile()
        try {
            writeUnknownJob(dir)
            // A real committed claim must never be rolled back, even when the record still
            // carries a stale UNKNOWN commit-state marker.
            KeplerJobMetadata.update(dir) {
                it.put("galleryExportCommitted", true)
                    .put("exportVerified", true)
            }
            assertFalse(
                settleUnknownPublicCommitState(
                    org.robolectric.RuntimeEnvironment.getApplication(),
                    dir,
                    FakeAccess(pending = false, verified = false, exists = false)
                )
            )
            val job = KeplerJobMetadata.read(dir)
            assertTrue(job.getBoolean("galleryExportCommitted"))
            assertTrue(job.getBoolean("exportVerified"))
            assertEquals(LINKAGE, job.getString("galleryPublicExportLinkage"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun noOpWhenCommitStateIsNotUnknown() {
        val dir = Files.createTempDirectory("settle-not-unknown-").toFile()
        try {
            writeUnknownJob(dir)
            KeplerJobMetadata.update(dir) {
                it.put("exportCommitState", GalleryExportCommitState.VERIFIED.name)
            }
            assertFalse(
                settleUnknownPublicCommitState(
                    org.robolectric.RuntimeEnvironment.getApplication(),
                    dir,
                    FakeAccess(pending = false, verified = true)
                )
            )
            val job = KeplerJobMetadata.read(dir)
            assertEquals(GalleryExportCommitState.VERIFIED.name, job.getString("exportCommitState"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingLinkageLeavesUnknownUntouched() {
        val dir = Files.createTempDirectory("settle-no-linkage-").toFile()
        try {
            writeUnknownJob(dir)
            KeplerJobMetadata.update(dir) { it.remove("galleryPublicExportLinkage") }
            assertFalse(
                settleUnknownPublicCommitState(
                    org.robolectric.RuntimeEnvironment.getApplication(),
                    dir,
                    FakeAccess(pending = false, verified = true)
                )
            )
            assertEquals(
                GalleryExportCommitState.UNKNOWN.name,
                KeplerJobMetadata.read(dir).getString("exportCommitState")
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fatalRecoveryErrorPropagatesWithoutMetadataWrite() {
        val dir = Files.createTempDirectory("settle-fatal-").toFile()
        try {
            writeUnknownJob(dir)
            val access = object : MediaStoreExportRecoveryAccess {
                override fun inspect(uri: Uri, journal: MediaStoreExportJournal): MediaStoreExportInspection {
                    throw AssertionError("fatal provider inspection")
                }
                override fun setPending(uri: Uri, pending: Boolean): Boolean = true
                override fun delete(uri: Uri): Boolean = true
            }
            assertThrows(AssertionError::class.java) {
                settleUnknownPublicCommitState(org.robolectric.RuntimeEnvironment.getApplication(), dir, access)
            }
            val job = KeplerJobMetadata.read(dir)
            assertEquals(GalleryExportCommitState.UNKNOWN.name, job.getString("exportCommitState"))
            assertEquals(MediaStoreExportState.CONTENT_WRITTEN, MediaStoreExportJournal.list(dir).single().state)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sidecarAndMainAbsentRowsAreBothSettledOutOfBlockingStates() {
        val dir = Files.createTempDirectory("settle-sidecar-absent-").toFile()
        try {
            writeUnknownJob(dir)
            MediaStoreExportJournal.create(
                jobDir = dir,
                role = MediaStoreExportRole.RAW_DNG_SIDECAR,
                frameIndex = 0,
                displayName = "Kepler_RAW_00.dng",
                relativePath = "Download/Kepler/RAW",
                mimeType = "image/x-adobe-dng",
                collectionUri = Uri.parse("content://media/external/file"),
                expectedSizeBytes = 8L
            )
                .transition(dir, MediaStoreExportState.ROW_INSERTED, "content://media/external/file/99")
                .transition(dir, MediaStoreExportState.CONTENT_WRITTEN)
            val access = FakeAccess(pending = false, verified = false, exists = false)
            assertTrue(settleUnknownPublicCommitState(org.robolectric.RuntimeEnvironment.getApplication(), dir, access))

            val states = MediaStoreExportJournal.list(dir).map { it.role to it.state }.toMap()
            assertEquals(MediaStoreExportState.CLEANED, states[MediaStoreExportRole.MAIN_IMAGE])
            assertEquals(MediaStoreExportState.CLEANED, states[MediaStoreExportRole.RAW_DNG_SIDECAR])
            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(dir, JobRecoveryMutationIntent.REPROCESS)
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}