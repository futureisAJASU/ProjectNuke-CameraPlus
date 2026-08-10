package com.projectnuke.keplernightlab

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
            assertEquals("ADOPTED", committed.getJSONArray("processingArtifactSettlements")
                .getJSONObject(0).getString("status"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun newAttemptCreatesMetadataForNewProcessingDirectory() {
        val dir = Files.createTempDirectory("processing-attempt-new").toFile()
        try {
            val attempt = beginProcessingAttempt(dir, "SUPER_RESOLUTION")
            val job = KeplerJobMetadata.read(dir)
            assertEquals(attempt.id, job.getString("processingAttemptId"))
            assertEquals("SUPER_RESOLUTION", job.getString("processingMode"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun concurrentAttemptIsRejectedUntilOwnerReleases() {
        val dir = Files.createTempDirectory("processing-attempt-concurrent").toFile()
        try {
            val first = beginProcessingAttempt(dir, "CLASSIC_YUV")
            assertThrows(ProcessingAlreadyActiveException::class.java) {
                beginProcessingAttempt(dir, "SUPER_RESOLUTION")
            }
            first.releaseOwnedLease()
            val second = beginProcessingAttempt(dir, "SUPER_RESOLUTION")
            assertEquals(second.id, KeplerJobMetadata.read(dir).getString("processingAttemptId"))
            second.releaseOwnedLease()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun borrowedLeaseIsNotReleasedByNestedAttempt() {
        val dir = Files.createTempDirectory("processing-attempt-borrowed").toFile()
        val lease = KeplerJobMetadata.acquireOperation(dir)
        requireNotNull(lease)
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "REPROCESS"))
            val nested = beginProcessingAttempt(dir, "CLASSIC_YUV", operationLease = lease)
            nested.releaseOwnedLease()
            assertTrue(KeplerJobMetadata.isOperationOwner(dir, lease))
        } finally {
            lease.release()
            dir.deleteRecursively()
        }
    }

    @Test
    fun borrowedLeaseAllowsOnlyOneProcessingSubleaseAtATime() {
        val dir = Files.createTempDirectory("processing-attempt-sublease").toFile()
        val lease = KeplerJobMetadata.acquireOperation(dir)
        requireNotNull(lease)
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "REPROCESS"))
            val first = beginProcessingAttempt(dir, "CLASSIC_YUV", operationLease = lease)
            assertThrows(ProcessingAlreadyActiveException::class.java) {
                beginProcessingAttempt(dir, "SUPER_RESOLUTION", operationLease = lease)
            }
            assertEquals(first.id, KeplerJobMetadata.read(dir).getString("processingAttemptId"))
            first.release()
            val second = beginProcessingAttempt(dir, "SUPER_RESOLUTION", operationLease = lease)
            assertEquals(second.id, KeplerJobMetadata.read(dir).getString("processingAttemptId"))
            second.release()
            assertTrue(KeplerJobMetadata.isOperationOwner(dir, lease))
        } finally {
            lease.release()
            dir.deleteRecursively()
        }
    }
}
