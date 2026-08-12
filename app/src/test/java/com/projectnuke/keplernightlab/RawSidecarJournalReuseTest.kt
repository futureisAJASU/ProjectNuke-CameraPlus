package com.projectnuke.keplernightlab

import android.net.Uri
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RawSidecarJournalReuseTest {
    @Test
    fun verifiedFrameJournalIsSelectedInsteadOfCreatingDuplicateAttempt() {
        val dir = Files.createTempDirectory("sidecar-reuse-").toFile()
        try {
            val journal = MediaStoreExportJournal.create(
                jobDir = dir,
                role = MediaStoreExportRole.RAW_DNG_SIDECAR,
                frameIndex = 3,
                displayName = "frame_03.dng",
                relativePath = "Pictures/Kepler/RAW",
                mimeType = "image/x-adobe-dng",
                collectionUri = Uri.parse("content://media/external/file"),
                expectedSizeBytes = 40L
            ).transition(dir, MediaStoreExportState.VERIFIED, "content://media/external/file/3")
            val selected = findReusableRawSidecarJournal(
                MediaStoreExportJournal.list(dir),
                frameIndex = 3,
                displayName = "frame_03.dng",
                expectedSizeBytes = 40L,
                verifier = { it.uri == journal.uri }
            )
            assertNotNull(selected)
            assertEquals(journal.exportAttemptId, selected?.exportAttemptId)
        } finally {
            dir.deleteRecursively()
        }
    }
}
