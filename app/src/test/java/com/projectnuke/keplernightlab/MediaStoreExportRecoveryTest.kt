package com.projectnuke.keplernightlab

import android.net.Uri
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class MediaStoreExportRecoveryTest {
    private class FakeAccess(
        var pending: Boolean,
        var verified: Boolean,
        var exists: Boolean = true,
        var commitSucceeds: Boolean = true
    ) : MediaStoreExportRecoveryAccess {
        var deleted = false
        var deleteResult = true
        var inspectionFailed = false
        var returnNullCursor = false
        var committed = false
        var deleteFailure: Throwable? = null
        var setPendingFailure: Throwable? = null
        var setPendingSideEffectThenFailure = false
        override fun inspect(uri: Uri, journal: MediaStoreExportJournal) =
            MediaStoreExportInspection(exists, pending, verified, inspectionFailed = inspectionFailed)
        override fun setPending(uri: Uri, pending: Boolean): Boolean {
            if (setPendingSideEffectThenFailure) {
                this.pending = pending
                committed = true
                throw IOException("commit applied before provider failure")
            }
            setPendingFailure?.let { throw it }
            if (!commitSucceeds) return false
            this.pending = pending
            committed = true
            return true
        }
        override fun delete(uri: Uri): Boolean {
            deleteFailure?.let { throw it }
            deleted = true
            if (!deleteResult) return false
            exists = false
            return true
        }
    }

    private fun journal(dir: java.io.File, state: MediaStoreExportState = MediaStoreExportState.ROW_INSERTED): MediaStoreExportJournal {
        val created = MediaStoreExportJournal.create(
            jobDir = dir,
            role = MediaStoreExportRole.MAIN_IMAGE,
            frameIndex = null,
            displayName = "result.jpg",
            relativePath = "Pictures/Kepler",
            mimeType = "image/jpeg",
            collectionUri = Uri.parse("content://media/external/images/media")
        )
        return created.transition(dir, state, "content://media/external/images/media/7")
    }

    @Test
    fun verifiedPendingRowIsCommittedByExactJournalUri() {
        val dir = Files.createTempDirectory("media-recovery-pending-").toFile()
        try {
            journal(dir)
            val access = FakeAccess(pending = true, verified = true)
            val result = recoverMediaStoreExportJournals(dir, access).single()
            assertEquals(MediaStoreExportRecoveryClassification.PENDING_VERIFIED_AND_COMMITTED, result.classification)
            assertTrue(access.committed)
            assertFalse(access.deleted)
            assertEquals(MediaStoreExportState.VERIFIED, MediaStoreExportJournal.list(dir).single().state)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun incompletePendingRowIsDeletedOnlyByExactJournalUri() {
        val dir = Files.createTempDirectory("media-recovery-delete-").toFile()
        try {
            journal(dir)
            val access = FakeAccess(pending = true, verified = false)
            val result = recoverMediaStoreExportJournals(dir, access).single()
            assertEquals(MediaStoreExportRecoveryClassification.PENDING_DELETED, result.classification)
            assertTrue(access.deleted)
            assertEquals(MediaStoreExportState.CLEANED, MediaStoreExportJournal.list(dir).single().state)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cleanupRequiredVerifiedPendingRowIsDeletedNeverCommitted() {
        val dir = Files.createTempDirectory("media-recovery-abandoned-pending-").toFile()
        try {
            journal(dir, MediaStoreExportState.CLEANUP_REQUIRED)
            val access = FakeAccess(pending = true, verified = true)
            val result = recoverMediaStoreExportJournals(dir, access).single()
            assertEquals(MediaStoreExportRecoveryClassification.CLEANED, result.classification)
            assertTrue(access.deleted)
            assertFalse(access.committed)
            assertEquals(MediaStoreExportState.CLEANED, MediaStoreExportJournal.list(dir).single().state)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun cleanupRequiredCommittedRowIsStillDeletedNeverResurrected() {
        val dir = Files.createTempDirectory("media-recovery-abandoned-committed-").toFile()
        try {
            journal(dir, MediaStoreExportState.CLEANUP_REQUIRED)
            val access = FakeAccess(pending = false, verified = true)
            val result = recoverMediaStoreExportJournals(dir, access).single()
            assertEquals(MediaStoreExportRecoveryClassification.CLEANED, result.classification)
            assertTrue(access.deleted)
            assertFalse(access.committed)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun cleanupRequiredMissingRowIsCleanedNotMissingCommit() {
        val dir = Files.createTempDirectory("media-recovery-abandoned-missing-").toFile()
        try {
            journal(dir, MediaStoreExportState.CLEANUP_REQUIRED)
            val result = recoverMediaStoreExportJournals(dir, FakeAccess(false, false, exists = false)).single()
            assertEquals(MediaStoreExportRecoveryClassification.CLEANED, result.classification)
            assertEquals(MediaStoreExportState.CLEANED, MediaStoreExportJournal.list(dir).single().state)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun cleanupRequiredDeleteFailureRemainsAbandonedDebt() {
        val dir = Files.createTempDirectory("media-recovery-abandoned-failure-").toFile()
        try {
            journal(dir, MediaStoreExportState.CLEANUP_REQUIRED)
            val access = FakeAccess(true, true).apply { deleteResult = false }
            val result = recoverMediaStoreExportJournals(dir, access).single()
            assertEquals(MediaStoreExportRecoveryClassification.DELETE_FAILED, result.classification)
            assertEquals(MediaStoreExportState.CLEANUP_REQUIRED, MediaStoreExportJournal.list(dir).single().state)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun ordinaryAbandonedRowDeleteExceptionRemainsRetryableDebt() {
        val dir = Files.createTempDirectory("media-recovery-abandoned-exception-").toFile()
        try {
            journal(dir, MediaStoreExportState.CLEANUP_REQUIRED)
            val access = FakeAccess(true, true).apply { deleteFailure = IOException("delete failed") }
            val result = recoverMediaStoreExportJournals(dir, access).single()
            assertEquals(MediaStoreExportRecoveryClassification.DELETE_FAILED, result.classification)
            assertEquals(MediaStoreExportState.CLEANUP_REQUIRED, MediaStoreExportJournal.list(dir).single().state)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun fatalAbandonedRowDeleteErrorPropagatesWithoutChangingJournal() {
        val dir = Files.createTempDirectory("media-recovery-abandoned-fatal-").toFile()
        try {
            journal(dir, MediaStoreExportState.CLEANUP_REQUIRED)
            val access = FakeAccess(true, true).apply { deleteFailure = AssertionError("fatal delete") }
            assertThrows(AssertionError::class.java) {
                recoverMediaStoreExportJournals(dir, access)
            }
            assertEquals(MediaStoreExportState.CLEANUP_REQUIRED, MediaStoreExportJournal.list(dir).single().state)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun cleanupDebtDoesNotBecomePublicAfterRestart() {
        val dir = Files.createTempDirectory("media-recovery-abandoned-restart-").toFile()
        try {
            journal(dir, MediaStoreExportState.CLEANUP_REQUIRED)
            val access = FakeAccess(pending = true, verified = true).apply { deleteResult = false }
            val result = recoverMediaStoreExportJournals(dir, access).single()
            assertEquals(MediaStoreExportRecoveryClassification.DELETE_FAILED, result.classification)
            assertFalse(access.committed)
            assertEquals(MediaStoreExportState.CLEANUP_REQUIRED, MediaStoreExportJournal.list(dir).single().state)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun missingCommittedUriIsPreservedAsRecoveryDebt() {
        val dir = Files.createTempDirectory("media-recovery-missing-").toFile()
        try {
            journal(dir, MediaStoreExportState.PUBLIC_COMMITTED)
            val result = recoverMediaStoreExportJournals(dir, FakeAccess(pending = false, verified = false, exists = false)).single()
            assertEquals(MediaStoreExportRecoveryClassification.PUBLIC_COMMIT_MISSING, result.classification)
            assertTrue(MediaStoreExportJournal.list(dir).single().uri!!.contains("/7"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verificationFailureDowngradesLegacyVerifiedJournalWithoutDeletingUri() {
        val dir = Files.createTempDirectory("media-recovery-downgrade-").toFile()
        try {
            journal(dir, MediaStoreExportState.VERIFIED)
            val result = recoverMediaStoreExportJournals(dir, FakeAccess(pending = false, verified = false)).single()
            assertEquals(MediaStoreExportRecoveryClassification.PUBLIC_COMMITTED_UNVERIFIED, result.classification)
            assertEquals(MediaStoreExportState.PUBLIC_COMMITTED, MediaStoreExportJournal.list(dir).single().state)
            assertTrue(MediaStoreExportJournal.list(dir).single().uri!!.contains("/7"))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun nonPendingContentWrittenRowIsPreservedAsCommittedUnverifiedEvidence() {
        val dir = Files.createTempDirectory("media-recovery-content-written-public-").toFile()
        try {
            journal(dir, MediaStoreExportState.CONTENT_WRITTEN)
            val access = FakeAccess(pending = false, verified = false)

            val result = recoverMediaStoreExportJournals(dir, access).single()

            assertEquals(MediaStoreExportRecoveryClassification.PUBLIC_COMMITTED_UNVERIFIED, result.classification)
            assertFalse(access.deleted)
            assertEquals(MediaStoreExportState.PUBLIC_COMMITTED, MediaStoreExportJournal.list(dir).single().state)
            assertTrue(MediaStoreExportJournal.list(dir).single().uri!!.contains("/7"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun insertWithoutUriIsAmbiguousAndJournalIsPreserved() {
        val dir = Files.createTempDirectory("media-recovery-unknown-").toFile()
        try {
            MediaStoreExportJournal.create(
                jobDir = dir,
                role = MediaStoreExportRole.MAIN_IMAGE,
                frameIndex = null,
                displayName = "result.jpg",
                relativePath = "Pictures/Kepler",
                mimeType = "image/jpeg",
                collectionUri = Uri.parse("content://media/external/images/media")
            )
            val result = recoverMediaStoreExportJournals(dir, FakeAccess(false, false)).single()
            assertEquals(MediaStoreExportRecoveryClassification.INSERT_RESULT_UNKNOWN, result.classification)
            assertTrue(MediaStoreExportJournal.list(dir).isNotEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test
    fun alreadyVerifiedJournalReinspectionDoesNotRewriteIdenticalState() {
        val dir = Files.createTempDirectory("media-recovery-verified-idem-").toFile()
        try {
            journal(dir, MediaStoreExportState.VERIFIED)
            val updatedBefore = MediaStoreExportJournal.list(dir).single().updatedAt
            val writesBefore = KeplerJobMetadata.atomicWriteCount
            val result = recoverMediaStoreExportJournals(dir, FakeAccess(pending = false, verified = true)).single()
            assertEquals(MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED, result.classification)
            val reconstructed = MediaStoreExportJournal.list(dir).single()
            assertEquals(MediaStoreExportState.VERIFIED, reconstructed.state)
            assertEquals(updatedBefore, reconstructed.updatedAt)
            assertEquals(writesBefore, KeplerJobMetadata.atomicWriteCount)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun verifiedReinspectionStillTransitionsPublicCommittedToVerified() {
        val dir = Files.createTempDirectory("media-recovery-committed-upgrade-").toFile()
        try {
            journal(dir, MediaStoreExportState.PUBLIC_COMMITTED)
            val writesBefore = KeplerJobMetadata.atomicWriteCount
            val result = recoverMediaStoreExportJournals(dir, FakeAccess(pending = false, verified = true)).single()
            assertEquals(MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED, result.classification)
            assertEquals(MediaStoreExportState.VERIFIED, MediaStoreExportJournal.list(dir).single().state)
            assertEquals(writesBefore + 1, KeplerJobMetadata.atomicWriteCount)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun inspectionFailureIsAmbiguous() {
        val dir = Files.createTempDirectory("media-recovery-inspect-failure-").toFile()
        try {
            journal(dir)
            val access = FakeAccess(false, false).apply { inspectionFailed = true }
            assertEquals(MediaStoreExportRecoveryClassification.AMBIGUOUS, recoverMediaStoreExportJournals(dir, access).single().classification)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun pendingDeleteFailureIsRetainedAsRecoveryDebt() {
        val dir = Files.createTempDirectory("media-recovery-delete-failure-").toFile()
        try {
            journal(dir)
            val access = FakeAccess(true, false).apply { deleteResult = false }
            assertEquals(MediaStoreExportRecoveryClassification.DELETE_FAILED, recoverMediaStoreExportJournals(dir, access).single().classification)
            assertEquals(MediaStoreExportState.ROW_INSERTED, MediaStoreExportJournal.list(dir).single().state)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun ordinaryPendingDeleteExceptionRemainsExistingRecoveryDebt() {
        val dir = Files.createTempDirectory("media-recovery-delete-exception-").toFile()
        try {
            journal(dir)
            val access = FakeAccess(true, false).apply { deleteFailure = IOException("delete failed") }
            assertEquals(
                MediaStoreExportRecoveryClassification.DELETE_FAILED,
                recoverMediaStoreExportJournals(dir, access).single().classification
            )
            assertEquals(MediaStoreExportState.ROW_INSERTED, MediaStoreExportJournal.list(dir).single().state)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun fatalPendingDeleteErrorPropagatesWithoutClassifyingRecovery() {
        val dir = Files.createTempDirectory("media-recovery-delete-fatal-").toFile()
        try {
            journal(dir)
            val access = FakeAccess(true, false).apply { deleteFailure = AssertionError("fatal pending delete") }
            assertThrows(AssertionError::class.java) {
                recoverMediaStoreExportJournals(dir, access)
            }
            assertEquals(MediaStoreExportState.ROW_INSERTED, MediaStoreExportJournal.list(dir).single().state)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun ordinaryPendingCommitExceptionRemainsAmbiguous() {
        val dir = Files.createTempDirectory("media-recovery-commit-exception-").toFile()
        try {
            journal(dir)
            val access = FakeAccess(true, true).apply { setPendingFailure = IOException("commit failed") }
            assertEquals(
                MediaStoreExportRecoveryClassification.AMBIGUOUS,
                recoverMediaStoreExportJournals(dir, access).single().classification
            )
            assertEquals(MediaStoreExportState.ROW_INSERTED, MediaStoreExportJournal.list(dir).single().state)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun fatalPendingCommitErrorPropagatesWithoutClassifyingRecovery() {
        val dir = Files.createTempDirectory("media-recovery-commit-fatal-").toFile()
        try {
            journal(dir)
            val access = FakeAccess(true, true).apply { setPendingFailure = AssertionError("fatal commit") }
            assertThrows(AssertionError::class.java) {
                recoverMediaStoreExportJournals(dir, access)
            }
            assertEquals(MediaStoreExportState.ROW_INSERTED, MediaStoreExportJournal.list(dir).single().state)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun pendingCommitSideEffectThenExceptionIsReconciledAsPublicEvidence() {
        val dir = Files.createTempDirectory("media-recovery-commit-side-effect-").toFile()
        try {
            journal(dir)
            val access = FakeAccess(pending = true, verified = true).apply {
                setPendingSideEffectThenFailure = true
            }
            val result = recoverMediaStoreExportJournals(dir, access).single()
            assertEquals(MediaStoreExportRecoveryClassification.PUBLIC_VERIFIED, result.classification)
            assertTrue(access.committed)
            assertFalse(access.deleted)
            assertEquals(MediaStoreExportState.VERIFIED, MediaStoreExportJournal.list(dir).single().state)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun abandonedRowDeleteHelperReturnsOrdinaryFailureButPropagatesFatalError() {
        val app = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://media/external/images/media/99")
        mediaStoreAbandonDeleteFailureForTest = IOException("ordinary abandoned delete")
        assertFalse(deleteMediaStoreRowForAbandon(app, uri))

        mediaStoreAbandonDeleteFailureForTest = AssertionError("fatal abandoned delete")
        try {
            assertThrows(AssertionError::class.java) {
                deleteMediaStoreRowForAbandon(app, uri)
            }
        } finally {
            mediaStoreAbandonDeleteFailureForTest = null
        }
    }

    @Test
    fun nullCursorIsInspectionFailureNotMissingRow() {
        val dir = Files.createTempDirectory("media-recovery-null-cursor-").toFile()
        try {
            journal(dir)
            val access = object : MediaStoreExportRecoveryAccess {
                override fun inspect(uri: Uri, journal: MediaStoreExportJournal) =
                    MediaStoreExportInspection(false, false, false, "null cursor", inspectionFailed = true)
                override fun setPending(uri: Uri, pending: Boolean) = true
                override fun delete(uri: Uri) = true
            }
            assertEquals(MediaStoreExportRecoveryClassification.AMBIGUOUS, recoverMediaStoreExportJournals(dir, access).single().classification)
        } finally { dir.deleteRecursively() }
    }
}
