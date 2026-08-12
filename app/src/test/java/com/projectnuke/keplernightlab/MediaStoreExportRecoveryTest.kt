package com.projectnuke.keplernightlab

import android.net.Uri
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
        override fun inspect(uri: Uri, journal: MediaStoreExportJournal) =
            MediaStoreExportInspection(exists, pending, verified, inspectionFailed = inspectionFailed)
        override fun setPending(uri: Uri, pending: Boolean): Boolean {
            if (!commitSucceeds) return false
            this.pending = pending
            committed = true
            return true
        }
        override fun delete(uri: Uri): Boolean {
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
            assertEquals(MediaStoreExportState.CLEANUP_REQUIRED, MediaStoreExportJournal.list(dir).single().state)
        } finally {
            dir.deleteRecursively()
        }
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
