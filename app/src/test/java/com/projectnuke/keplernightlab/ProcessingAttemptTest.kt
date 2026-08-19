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
    fun nextProductionAcquisitionRetriesAProcessingClearAfterScopeReturns() {
        val dir = Files.createTempDirectory("processing-terminal-convergence").toFile()
        var retainedLease: JobOperationLease? = null
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "YUV_NIGHT_FUSION"))
            fun runProcessingProductionScope() {
                val attempt = beginProcessingAttempt(dir, "CLASSIC_YUV")
                retainedLease = requireNotNull(attempt.operationLease)
                KeplerJobMetadata.atomicWriteFailureForTest = IllegalStateException("one-shot clear failure")
                try {
                    // The real processing function's finally is the only release call in this scope.
                } finally {
                    attempt.releaseOwnedLease()
                }
            }
            runProcessingProductionScope()

            val oldLease = requireNotNull(retainedLease)
            assertTrue(KeplerJobMetadata.isOperationOwner(dir, oldLease))
            assertTrue(KeplerJobMetadata.read(dir).has(ACTIVE_OPERATION_ID))

            val next = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                dir,
                JobRecoveryMutationIntent.REPROCESS
            )
            assertFalse(KeplerJobMetadata.isOperationOwner(dir, oldLease))
            assertFalse(KeplerJobMetadata.read(dir).has(ACTIVE_OPERATION_ID))
            next.release()
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            retainedLease?.release()
            dir.deleteRecursively()
        }
    }

    @Test
    fun durableCurrentClaimPreservesRequiredOutputAfterAttemptReturnsThroughFailureBoundary() {
        val dir = Files.createTempDirectory("processing-terminal-output").toFile()
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "YUV_NIGHT_FUSION"))
            val attempt = beginProcessingAttempt(dir, "CLASSIC_YUV")
            val output = dir.resolve("final.png")
            try {
                commitProcessingArtifact(
                    finalFile = output,
                    writeTemp = { it.writeBytes(byteArrayOf(1, 2, 3)) },
                    verifyFinal = { check(it.readBytes().contentEquals(byteArrayOf(1, 2, 3))) },
                    processingAttemptId = attempt.id,
                    claimKey = "finalFile"
                )
                markProcessingArtifactClaim(dir, attempt, "finalFile", output)

                // Model the caller observing an exceptional return after the
                // durable claim, before it receives a File result.
                assertTrue(requiredOutputCommittedAfterProcessing(dir, attempt.operationLease))
                attempt.releaseOwnedLease()
                assertTrue(requiredOutputCommittedAfterProcessing(dir))
            } finally {
                attempt.releaseOwnedLease()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun previousFinalIsNotCountedForAnewUncommittedAttempt() {
        val dir = Files.createTempDirectory("processing-terminal-previous").toFile()
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "YUV_NIGHT_FUSION"))
            val first = beginProcessingAttempt(dir, "CLASSIC_YUV")
            val output = dir.resolve("final.png")
            commitProcessingArtifact(
                finalFile = output,
                writeTemp = { it.writeBytes(byteArrayOf(7, 8, 9)) },
                verifyFinal = { check(it.readBytes().contentEquals(byteArrayOf(7, 8, 9))) },
                processingAttemptId = first.id,
                claimKey = "finalFile"
            )
            markProcessingArtifactClaim(dir, first, "finalFile", output)
            first.releaseOwnedLease()

            val second = beginProcessingAttempt(dir, "CLASSIC_YUV")
            try {
                assertFalse(requiredOutputCommittedAfterProcessing(dir, second.operationLease))
                assertTrue(output.exists())
            } finally {
                second.releaseOwnedLease()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun previousClaimIsNotCountedBeforeTheNewAttemptClaimsAnOutput() {
        val dir = Files.createTempDirectory("processing-terminal-pre-attempt").toFile()
        var lease: JobOperationLease? = null
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "YUV_NIGHT_FUSION"))
            val first = beginProcessingAttempt(dir, "CLASSIC_YUV")
            val output = dir.resolve("final.png")
            commitProcessingArtifact(
                finalFile = output,
                writeTemp = { it.writeBytes(byteArrayOf(4, 5, 6)) },
                verifyFinal = { check(it.readBytes().contentEquals(byteArrayOf(4, 5, 6))) },
                processingAttemptId = first.id,
                claimKey = "finalFile"
            )
            markProcessingArtifactClaim(dir, first, "finalFile", output)
            first.releaseOwnedLease()

            lease = KeplerJobMetadata.acquireOperation(dir)
            assertFalse(requiredOutputCommittedAfterProcessing(dir, lease))
        } finally {
            lease?.release()
            dir.deleteRecursively()
        }
    }

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
            try {
                val afterStart = KeplerJobMetadata.read(dir)
                assertEquals(attempt.id, afterStart.getString("processingAttemptId"))
                assertFalse(afterStart.has("pipelineFailed"))
                assertFalse(afterStart.has("rawFusedPreviewFile"))

                val output = dir.resolve("merged.raw16")
                commitProcessingArtifact(
                    finalFile = output,
                    writeTemp = { it.writeBytes(byteArrayOf(1, 2)) },
                    verifyFinal = { check(it.readBytes().contentEquals(byteArrayOf(1, 2))) },
                    processingAttemptId = attempt.id,
                    claimKey = "mergedRawFile"
                )
                markProcessingArtifactClaim(dir, attempt, "mergedRawFile", output)
                val committed = KeplerJobMetadata.read(dir)
                assertEquals(output.name, committed.getString("mergedRawFile"))
                assertTrue(committed.getBoolean("processingOutputCommitted"))
                assertEquals("ADOPTED", committed.getJSONArray("processingArtifactSettlements")
                    .getJSONObject(0).getString("status"))
            } finally {
                attempt.releaseOwnedLease()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun newAttemptCreatesMetadataForNewProcessingDirectory() {
        val dir = Files.createTempDirectory("processing-attempt-new").toFile()
        try {
            val attempt = beginProcessingAttempt(dir, "SUPER_RESOLUTION")
            try {
                val job = KeplerJobMetadata.read(dir)
                assertEquals(attempt.id, job.getString("processingAttemptId"))
                assertEquals("SUPER_RESOLUTION", job.getString("processingMode"))
            } finally {
                attempt.releaseOwnedLease()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun claimAckRequiresOneExactAttemptKeyAndFinalJournal() {
        val dir = Files.createTempDirectory("processing-attempt-exact-claim").toFile()
        try {
            val attempt = beginProcessingAttempt(dir, "CLASSIC_YUV")
            try {
                val first = dir.resolve("first.png")
                val second = dir.resolve("second.png")
                commitProcessingArtifact(
                    finalFile = first,
                    writeTemp = { it.writeBytes(byteArrayOf(1, 2)) },
                    verifyFinal = { check(it.readBytes().contentEquals(byteArrayOf(1, 2))) },
                    processingAttemptId = attempt.id,
                    claimKey = "finalFile"
                )
                assertThrows(ProcessingArtifactClaimConflictException::class.java) {
                    commitProcessingArtifact(
                        finalFile = second,
                        writeTemp = { it.writeBytes(byteArrayOf(3, 4)) },
                        verifyFinal = { check(it.readBytes().contentEquals(byteArrayOf(3, 4))) },
                        processingAttemptId = attempt.id,
                        claimKey = "finalFile"
                    )
                }
                markProcessingArtifactClaim(dir, attempt, "finalFile", first)
                val remaining = ProcessingArtifactJournal.list(dir)
                    .map { ProcessingArtifactJournal.read(it) }
                assertTrue(remaining.isEmpty())
                assertTrue(KeplerJobMetadata.read(dir).optBoolean("processingOutputCommitted", false))
            } finally {
                attempt.releaseOwnedLease()
            }
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
    fun malformedProcessingJournalBlocksNewAttempt() {
        val dir = Files.createTempDirectory("processing-attempt-invalid-journal").toFile()
        try {
            KeplerJobMetadata.write(dir, org.json.JSONObject().put("jobType", "YUV_NIGHT_FUSION"))
            dir.resolve(".processing_tx_broken.json").writeText("not-json")
            assertThrows(JobRecoveryMutationBlockedException::class.java) {
                beginProcessingAttempt(dir, "CLASSIC_YUV")
            }
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
