package com.projectnuke.keplernightlab

import android.net.Uri
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaStoreExportTerminalAckTest {
    private fun terminalMetadata(
        commitState: GalleryExportCommitState,
        committed: Boolean,
        verified: Boolean,
        operationId: String = "op-1"
    ): JSONObject = JSONObject()
        .put("currentPipelineStage", "COMPLETE")
        .put(TERMINAL_OPERATION_ID, operationId)
        .put("exportCommitState", commitState.name)
        .put("galleryExportCommitted", committed)
        .put("exportVerified", verified)

    private fun journal(directory: File, operationId: String = "op-1"): MediaStoreExportJournal =
        MediaStoreExportJournal.create(
            directory,
            MediaStoreExportRole.MAIN_IMAGE,
            null,
            "result.jpg",
            "Pictures/Kepler",
            "image/jpeg",
            Uri.parse("content://media/external/images/media"),
            ownerOperationId = operationId
        )

    private fun settled(directory: File, attemptId: String): Boolean =
        MediaStoreExportJournal.read(
            directory,
            MediaStoreExportJournal.fileFor(directory, attemptId)
        ).terminalMetadataPersisted

    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("media-export-ack-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun verifiedTerminalRecordAcknowledgesVerifiedJournal() = withDirectory { directory ->
        KeplerJobMetadata.write(
            directory,
            terminalMetadata(GalleryExportCommitState.VERIFIED, committed = true, verified = true)
        )
        val attempt = journal(directory)
            .transition(directory, MediaStoreExportState.ROW_INSERTED, "content://media/42")
            .transition(directory, MediaStoreExportState.CONTENT_WRITTEN)
            .transition(directory, MediaStoreExportState.PUBLIC_COMMITTED)
            .transition(directory, MediaStoreExportState.VERIFIED)
            .exportAttemptId

        markMediaStoreExportJournalsTerminalPersisted(directory)

        assertTrue(settled(directory, attempt))
    }

    @Test
    fun verifiedTerminalRecordDefersLaggingCommittedJournal() = withDirectory { directory ->
        KeplerJobMetadata.write(
            directory,
            terminalMetadata(GalleryExportCommitState.VERIFIED, committed = true, verified = true)
        )
        val attempt = journal(directory)
            .transition(directory, MediaStoreExportState.ROW_INSERTED, "content://media/42")
            .transition(directory, MediaStoreExportState.CONTENT_WRITTEN)
            .transition(directory, MediaStoreExportState.PUBLIC_COMMITTED)
            .exportAttemptId

        markMediaStoreExportJournalsTerminalPersisted(directory)

        assertFalse(settled(directory, attempt))
    }

    @Test
    fun committedUnverifiedTerminalRecordAcknowledgesCommittedJournal() = withDirectory { directory ->
        KeplerJobMetadata.write(
            directory,
            terminalMetadata(
                GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED,
                committed = true,
                verified = false
            )
        )
        val attempt = journal(directory)
            .transition(directory, MediaStoreExportState.ROW_INSERTED, "content://media/42")
            .transition(directory, MediaStoreExportState.CONTENT_WRITTEN)
            .transition(directory, MediaStoreExportState.PUBLIC_COMMITTED)
            .exportAttemptId

        markMediaStoreExportJournalsTerminalPersisted(directory)

        assertTrue(settled(directory, attempt))
    }

    @Test
    fun unknownCommitRecordDefersEveryJournalAcknowledgment() = withDirectory { directory ->
        KeplerJobMetadata.write(
            directory,
            terminalMetadata(GalleryExportCommitState.UNKNOWN, committed = false, verified = false)
        )
        val verifiedAttempt = journal(directory)
            .transition(directory, MediaStoreExportState.ROW_INSERTED, "content://media/42")
            .transition(directory, MediaStoreExportState.CONTENT_WRITTEN)
            .transition(directory, MediaStoreExportState.PUBLIC_COMMITTED)
            .transition(directory, MediaStoreExportState.VERIFIED)
            .exportAttemptId
        val committedAttempt = journal(directory)
            .transition(directory, MediaStoreExportState.ROW_INSERTED, "content://media/43")
            .transition(directory, MediaStoreExportState.CONTENT_WRITTEN)
            .transition(directory, MediaStoreExportState.PUBLIC_COMMITTED)
            .exportAttemptId
        val cleanedAttempt = journal(directory)
            .transition(directory, MediaStoreExportState.ROW_INSERTED)
            .transition(directory, MediaStoreExportState.CONTENT_WRITTEN)
            .transition(directory, MediaStoreExportState.CLEANED)
            .exportAttemptId

        markMediaStoreExportJournalsTerminalPersisted(directory)

        assertFalse(settled(directory, verifiedAttempt))
        assertFalse(settled(directory, committedAttempt))
        assertFalse(settled(directory, cleanedAttempt))
    }

    @Test
    fun notCommittedTerminalRecordDefersLaggingPreCommitJournal() = withDirectory { directory ->
        KeplerJobMetadata.write(
            directory,
            terminalMetadata(GalleryExportCommitState.NOT_COMMITTED, committed = false, verified = false)
        )
        val attempt = journal(directory)
            .transition(directory, MediaStoreExportState.ROW_INSERTED, "content://media/42")
            .transition(directory, MediaStoreExportState.CONTENT_WRITTEN)
            .exportAttemptId

        markMediaStoreExportJournalsTerminalPersisted(directory)

        assertFalse(settled(directory, attempt))
    }

    @Test
    fun notCommittedTerminalRecordAcknowledgesCleanedJournal() = withDirectory { directory ->
        KeplerJobMetadata.write(
            directory,
            terminalMetadata(GalleryExportCommitState.NOT_COMMITTED, committed = false, verified = false)
        )
        val attempt = journal(directory)
            .transition(directory, MediaStoreExportState.ROW_INSERTED)
            .transition(directory, MediaStoreExportState.CONTENT_WRITTEN)
            .transition(directory, MediaStoreExportState.CLEANED)
            .exportAttemptId

        markMediaStoreExportJournalsTerminalPersisted(directory)

        assertTrue(settled(directory, attempt))
    }

    @Test
    fun notCommittedTerminalRecordAcknowledgesInsertFailedJournal() = withDirectory { directory ->
        KeplerJobMetadata.write(
            directory,
            terminalMetadata(GalleryExportCommitState.NOT_COMMITTED, committed = false, verified = false)
        )
        val attempt = journal(directory)
            .transition(directory, MediaStoreExportState.INSERT_FAILED_NO_ROW)
            .exportAttemptId

        markMediaStoreExportJournalsTerminalPersisted(directory)

        assertTrue(settled(directory, attempt))
    }

    @Test
    fun foreignOwnerJournalIsNotAcknowledged() = withDirectory { directory ->
        KeplerJobMetadata.write(
            directory,
            terminalMetadata(GalleryExportCommitState.VERIFIED, committed = true, verified = true)
        )
        val attempt = journal(directory, operationId = "foreign-op")
            .transition(directory, MediaStoreExportState.ROW_INSERTED, "content://media/42")
            .transition(directory, MediaStoreExportState.CONTENT_WRITTEN)
            .transition(directory, MediaStoreExportState.PUBLIC_COMMITTED)
            .transition(directory, MediaStoreExportState.VERIFIED)
            .exportAttemptId

        markMediaStoreExportJournalsTerminalPersisted(directory)

        assertFalse(settled(directory, attempt))
    }
}