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
        operationId: String = "op-1",
        exportUri: String = "content://media/42",
        linkage: String? = null
    ): JSONObject = JSONObject()
        .put("currentPipelineStage", "COMPLETE")
        .put(TERMINAL_OPERATION_ID, operationId)
        .put("exportCommitState", commitState.name)
        .put("galleryExportCommitted", committed)
        .put("exportVerified", verified)
        .put("exportUri", exportUri)
        .apply { linkage?.let { put("galleryPublicExportLinkage", it) } }

    private fun journal(
        directory: File,
        operationId: String = "op-1",
        role: MediaStoreExportRole = MediaStoreExportRole.MAIN_IMAGE
    ): MediaStoreExportJournal = MediaStoreExportJournal.create(
        directory,
        role,
        if (role == MediaStoreExportRole.RAW_DNG_SIDECAR) 7 else null,
        if (role == MediaStoreExportRole.RAW_DNG_SIDECAR) "frame_07.dng" else "result.jpg",
        if (role == MediaStoreExportRole.RAW_DNG_SIDECAR) "Pictures/Kepler/RAW" else "Pictures/Kepler",
        if (role == MediaStoreExportRole.RAW_DNG_SIDECAR) "image/x-adobe-dng" else "image/jpeg",
        if (role == MediaStoreExportRole.RAW_DNG_SIDECAR) {
            Uri.parse("content://media/external/file")
        } else {
            Uri.parse("content://media/external/images/media")
        },
        ownerOperationId = operationId
    )

    private fun committedJournal(
        directory: File,
        uri: String,
        operationId: String = "op-1",
        role: MediaStoreExportRole = MediaStoreExportRole.MAIN_IMAGE
    ) = journal(directory, operationId, role)
        .transition(directory, MediaStoreExportState.ROW_INSERTED, uri)
        .transition(directory, MediaStoreExportState.CONTENT_WRITTEN)
        .transition(directory, MediaStoreExportState.PUBLIC_COMMITTED)

    private fun verifiedJournal(
        directory: File,
        uri: String,
        operationId: String = "op-1",
        role: MediaStoreExportRole = MediaStoreExportRole.MAIN_IMAGE
    ) = committedJournal(directory, uri, operationId, role)
        .transition(directory, MediaStoreExportState.VERIFIED)

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
        val attempt = verifiedJournal(directory, "content://media/42").exportAttemptId

        markMediaStoreExportJournalsTerminalPersisted(directory)

        assertTrue(settled(directory, attempt))
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
        val attempt = committedJournal(directory, "content://media/42").exportAttemptId

        markMediaStoreExportJournalsTerminalPersisted(directory)

        assertTrue(settled(directory, attempt))
    }

    @Test
    fun verifiedTerminalRecordDefersLaggingCommittedJournal() = withDirectory { directory ->
        KeplerJobMetadata.write(
            directory,
            terminalMetadata(GalleryExportCommitState.VERIFIED, committed = true, verified = true)
        )
        val attempt = committedJournal(directory, "content://media/42").exportAttemptId

        markMediaStoreExportJournalsTerminalPersisted(directory)

        assertFalse(settled(directory, attempt))
    }

    @Test
    fun unknownCommitRecordDefersEveryJournalAcknowledgment() = withDirectory { directory ->
        KeplerJobMetadata.write(
            directory,
            terminalMetadata(GalleryExportCommitState.UNKNOWN, committed = false, verified = false)
        )
        val verifiedAttempt = verifiedJournal(directory, "content://media/42").exportAttemptId
        val committedAttempt = committedJournal(directory, "content://media/43").exportAttemptId
        val cleanedAttempt = verifiedJournal(directory, "content://media/44")
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
        val attempt = verifiedJournal(directory, "content://media/42", operationId = "foreign-op")
            .exportAttemptId

        markMediaStoreExportJournalsTerminalPersisted(directory)

        assertFalse(settled(directory, attempt))
    }

    @Test
    fun divergentMainUriJournalIsNotAcknowledged() = withDirectory { directory ->
        KeplerJobMetadata.write(
            directory,
            terminalMetadata(GalleryExportCommitState.VERIFIED, committed = true, verified = true)
        )
        val attempt = verifiedJournal(directory, "content://media/OTHER-uri").exportAttemptId

        markMediaStoreExportJournalsTerminalPersisted(directory)

        assertFalse(settled(directory, attempt))
    }

    @Test
    fun committedRecordWithoutClaimedUriDefersMainJournal() = withDirectory { directory ->
        KeplerJobMetadata.write(
            directory,
            terminalMetadata(
                GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED,
                committed = true,
                verified = false,
                exportUri = ""
            )
        )
        val attempt = committedJournal(directory, "content://media/42").exportAttemptId

        markMediaStoreExportJournalsTerminalPersisted(directory)

        assertFalse(settled(directory, attempt))
    }

    @Test
    fun committedRecordWithOnlyLinkageAcknowledgesMatchingJournal() = withDirectory { directory ->
        KeplerJobMetadata.write(
            directory,
            terminalMetadata(
                GalleryExportCommitState.PUBLIC_COMMITTED_UNVERIFIED,
                committed = true,
                verified = false,
                exportUri = "",
                linkage = "content://media/42"
            )
        )
        val attempt = committedJournal(directory, "content://media/42").exportAttemptId

        markMediaStoreExportJournalsTerminalPersisted(directory)

        assertTrue(settled(directory, attempt))
    }

    @Test
    fun sidecarJournalIsAcknowledgedWithoutMatchingMainUri() = withDirectory { directory ->
        KeplerJobMetadata.write(
            directory,
            terminalMetadata(GalleryExportCommitState.VERIFIED, committed = true, verified = true)
        )
        val attempt = verifiedJournal(
            directory,
            "content://media/sidecar-uri",
            role = MediaStoreExportRole.RAW_DNG_SIDECAR
        ).exportAttemptId

        markMediaStoreExportJournalsTerminalPersisted(directory)

        assertTrue(settled(directory, attempt))
    }
}