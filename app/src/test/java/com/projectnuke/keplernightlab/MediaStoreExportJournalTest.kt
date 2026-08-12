package com.projectnuke.keplernightlab

import android.net.Uri
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaStoreExportJournalTest {
    @Test
    fun journalPersistsEveryMediaStoreBoundaryAndExactUri() {
        val jobDir = Files.createTempDirectory("media-export-journal-").toFile()
        try {
            var journal = MediaStoreExportJournal.create(
                jobDir = jobDir,
                role = MediaStoreExportRole.MAIN_IMAGE,
                frameIndex = null,
                displayName = "result.jpg",
                relativePath = "Pictures/Kepler",
                mimeType = "image/jpeg",
                collectionUri = Uri.parse("content://media/external/images/media")
            )
            assertEquals(MediaStoreExportState.PREPARED, journal.state)
            assertNull(journal.uri)

            journal = journal.transition(jobDir, MediaStoreExportState.ROW_INSERTED, "content://media/external/images/media/42")
            journal = journal.transition(jobDir, MediaStoreExportState.CONTENT_WRITTEN)
            journal = journal.transition(jobDir, MediaStoreExportState.PUBLIC_COMMITTED)
            journal = journal.transition(jobDir, MediaStoreExportState.VERIFIED)

            val reloaded = MediaStoreExportJournal.list(jobDir).single()
            assertEquals(MediaStoreExportState.VERIFIED, reloaded.state)
            assertEquals("content://media/external/images/media/42", reloaded.uri)
            assertEquals("result.jpg", reloaded.displayName)
            assertEquals("Pictures/Kepler", reloaded.relativePath)
            assertEquals(MediaStoreExportRole.MAIN_IMAGE, reloaded.role)

            reloaded.markTerminalPersisted(jobDir)
            assertEquals(MediaStoreExportState.TERMINAL_PERSISTED, MediaStoreExportJournal.list(jobDir).single().state)
        } finally {
            jobDir.deleteRecursively()
        }
    }

    @Test
    fun sidecarJournalRetainsFrameIdentityAcrossRestartSimulation() {
        val jobDir = Files.createTempDirectory("media-sidecar-journal-").toFile()
        try {
            MediaStoreExportJournal.create(
                jobDir = jobDir,
                role = MediaStoreExportRole.RAW_DNG_SIDECAR,
                frameIndex = 7,
                displayName = "frame_07.dng",
                relativePath = "Pictures/Kepler/RAW",
                mimeType = "image/x-adobe-dng",
                collectionUri = Uri.parse("content://media/external/file"),
                expectedSizeBytes = 1234L
            )
            val freshProcessView = MediaStoreExportJournal.list(jobDir).single()
            assertEquals(MediaStoreExportRole.RAW_DNG_SIDECAR, freshProcessView.role)
            assertEquals(7, freshProcessView.frameIndex)
            assertEquals(1234L, freshProcessView.expectedSizeBytes)
            assertTrue(freshProcessView.runtimeSessionId.isNotBlank())
        } finally {
            jobDir.deleteRecursively()
        }
    }
}
