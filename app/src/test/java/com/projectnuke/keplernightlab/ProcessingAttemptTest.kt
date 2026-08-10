package com.projectnuke.keplernightlab

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProcessingAttemptTest {
    @Test
    fun newAttemptClearsPriorRunClaimsAndOwnsItsCommittedArtifact() {
        val dir = Files.createTempDirectory("processing-attempt").toFile()
        try {
            KeplerJobMetadata.write(
                dir,
                JSONObject()
                    .put("pipelineFailed", true)
                    .put("rawFusedPreviewFile", "stale.png")
            )
            val attempt = beginProcessingAttempt(
                dir,
                "CLASSIC_RAW",
                setOf("rawFusedPreviewFile")
            )
            val afterStart = KeplerJobMetadata.read(dir)
            assertEquals(attempt.id, afterStart.getString("processingAttemptId"))
            assertFalse(afterStart.has("pipelineFailed"))
            assertFalse(afterStart.has("rawFusedPreviewFile"))

            val output = dir.resolve("merged.raw16").apply { writeBytes(byteArrayOf(1, 2)) }
            markProcessingArtifactClaim(dir, attempt, "mergedRawFile", output)
            val committed = KeplerJobMetadata.read(dir)
            assertEquals(output.name, committed.getString("mergedRawFile"))
            assertTrue(committed.getBoolean("processingOutputCommitted"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
