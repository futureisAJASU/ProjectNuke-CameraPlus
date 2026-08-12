package com.projectnuke.keplernightlab

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
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
            val journal = MediaStoreExportJournal.create(
                jobDir = dir,
                role = MediaStoreExportRole.RAW_DNG_SIDECAR,
                frameIndex = 2,
                displayName = "frame_02.dng",
                relativePath = "Pictures/Kepler/RAW",
                mimeType = "image/x-adobe-dng",
                collectionUri = Uri.parse("content://media/external/file")
            ).transition(dir, MediaStoreExportState.VERIFIED, "content://media/external/file/2")
            val job = JSONObject().put("frames", JSONArray().put(JSONObject().put("frameIndex", 2)))
            val count = reconstructRawSidecarJournalEvidence(dir, job, listOf(journal))
            assertEquals(1, count)
            assertEquals("PUBLIC_EXPORTED", job.getJSONArray("frames").getJSONObject(0).getString("dngSidecarPublicStatus"))
            assertEquals(journal.uri, job.getJSONArray("frames").getJSONObject(0).getString("publicDngUri"))
            assertEquals(1, job.getInt("rawSidecarPublicExportedCount"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
