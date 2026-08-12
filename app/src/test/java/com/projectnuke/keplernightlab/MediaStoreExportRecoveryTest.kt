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
        var committed = false
        override fun inspect(uri: Uri, journal: MediaStoreExportJournal) =
            MediaStoreExportInspection(exists, pending, verified)
        override fun setPending(uri: Uri, pending: Boolean): Boolean {
            if (!commitSucceeds) return false
            this.pending = pending
            committed = true
            return true
        }
        override fun delete(uri: Uri): Boolean {
            deleted = true
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
}
