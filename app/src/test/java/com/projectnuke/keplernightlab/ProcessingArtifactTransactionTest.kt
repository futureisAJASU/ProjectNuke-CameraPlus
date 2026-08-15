package com.projectnuke.keplernightlab

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CancellationException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.json.JSONObject
import org.junit.Assert.assertThrows

@RunWith(RobolectricTestRunner::class)
class ProcessingArtifactTransactionTest {
    private class TestCancellation(var cancelled: Boolean = false) : KeplerPipelineCancellation {
        override val isCancelled: Boolean get() = cancelled
        override fun throwIfCancelled() {
            if (cancelled) throw CancellationException("cancelled")
        }
    }

    @Test
    fun ordinaryProcessingJournalReadFailureRemainsInvalidEvidence() {
        val dir = Files.createTempDirectory("processing-journal-read-failure-").toFile()
        try {
            val journal = ProcessingArtifactJournal(
                transactionId = UUID.randomUUID().toString(),
                processingAttemptId = null,
                runtimeSessionId = "old-runtime",
                artifactType = "BIN",
                finalName = "result.bin",
                tempName = ".result.tmp",
                priorName = ".result.prior",
                state = ProcessingArtifactJournalState.PREPARED,
                createdAt = 1L,
                updatedAt = 2L
            )
            journal.writeTo(dir)
            processingArtifactJournalReadFailureForTest = java.io.IOException("ordinary processing journal read")
            val scan = ProcessingArtifactJournal.scan(dir)
            assertTrue(scan.validJournals.isEmpty())
            assertEquals(1, scan.invalidFiles.size)
            assertTrue(scan.invalidFiles.single().exists())
        } finally {
            processingArtifactJournalReadFailureForTest = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun fatalProcessingJournalReadErrorPropagatesAndPreservesEvidence() {
        val dir = Files.createTempDirectory("processing-journal-read-fatal-").toFile()
        try {
            val journal = ProcessingArtifactJournal(
                transactionId = UUID.randomUUID().toString(),
                processingAttemptId = null,
                runtimeSessionId = "old-runtime",
                artifactType = "BIN",
                finalName = "result.bin",
                tempName = ".result.tmp",
                priorName = ".result.prior",
                state = ProcessingArtifactJournalState.PREPARED,
                createdAt = 1L,
                updatedAt = 2L
            )
            journal.writeTo(dir)
            val journalFile = ProcessingArtifactJournal.list(dir).single()
            processingArtifactJournalReadFailureForTest = AssertionError("fatal processing journal read")
            assertThrows(AssertionError::class.java) { ProcessingArtifactJournal.scan(dir) }
            assertTrue(journalFile.exists())
        } finally {
            processingArtifactJournalReadFailureForTest = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun fatalProcessingJournalScanErrorDoesNotBecomeMissingCleanupBlocker() {
        val dir = Files.createTempDirectory("processing-journal-blocker-fatal-").toFile()
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "YUV_NIGHT_FUSION"))
            val journal = ProcessingArtifactJournal(
                transactionId = UUID.randomUUID().toString(),
                processingAttemptId = null,
                runtimeSessionId = "old-runtime",
                artifactType = "BIN",
                finalName = "result.bin",
                tempName = ".result.tmp",
                priorName = ".result.prior",
                state = ProcessingArtifactJournalState.PREPARED,
                createdAt = 1L,
                updatedAt = 2L
            )
            journal.writeTo(dir)
            processingArtifactJournalReadFailureForTest = AssertionError("fatal cleanup-blocker scan")
            assertThrows(AssertionError::class.java) { KeplerJobMetadata.hasProcessingCleanupBlocker(dir) }
            assertTrue(ProcessingArtifactJournal.list(dir).isNotEmpty())
        } finally {
            processingArtifactJournalReadFailureForTest = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun ordinaryProcessingArtifactVerificationFailureRemainsAmbiguous() {
        val dir = Files.createTempDirectory("processing-verify-failure-").toFile()
        try {
            val final = File(dir, "result.bin").apply { writeText("current") }
            val journal = ProcessingArtifactJournal(
                transactionId = UUID.randomUUID().toString(),
                processingAttemptId = null,
                runtimeSessionId = "old-runtime",
                artifactType = "BIN",
                finalName = final.name,
                tempName = ".result.tmp",
                priorName = ".result.prior",
                expectedSizeBytes = final.length(),
                expectedSha256 = NoFollowFileSystem.digestVerified(final).sha256,
                state = ProcessingArtifactJournalState.NEW_FINAL_MOVED,
                createdAt = 1L,
                updatedAt = 2L
            )
            journal.writeTo(dir)
            processingArtifactJournalVerifyFailureForTest = java.io.IOException("ordinary verification failure")
            val result = recoverProcessingArtifactJournals(dir).single()
            assertEquals(ProcessingArtifactRecoveryClassification.AMBIGUOUS, result.classification)
            assertTrue(final.exists())
            assertTrue(ProcessingArtifactJournal.list(dir).isNotEmpty())
        } finally {
            processingArtifactJournalVerifyFailureForTest = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun fatalProcessingArtifactVerificationErrorPropagatesAndPreservesEvidence() {
        val dir = Files.createTempDirectory("processing-verify-fatal-").toFile()
        try {
            val final = File(dir, "result.bin").apply { writeText("current") }
            val journal = ProcessingArtifactJournal(
                transactionId = UUID.randomUUID().toString(),
                processingAttemptId = null,
                runtimeSessionId = "old-runtime",
                artifactType = "BIN",
                finalName = final.name,
                tempName = ".result.tmp",
                priorName = ".result.prior",
                expectedSizeBytes = final.length(),
                expectedSha256 = NoFollowFileSystem.digestVerified(final).sha256,
                state = ProcessingArtifactJournalState.NEW_FINAL_MOVED,
                createdAt = 1L,
                updatedAt = 2L
            )
            journal.writeTo(dir)
            processingArtifactJournalVerifyFailureForTest = AssertionError("fatal processing artifact verification")
            assertThrows(AssertionError::class.java) { recoverProcessingArtifactJournals(dir) }
            assertTrue(final.exists())
            assertTrue(ProcessingArtifactJournal.list(dir).isNotEmpty())
        } finally {
            processingArtifactJournalVerifyFailureForTest = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun fatalProcessingJournalDeleteErrorPropagatesAndPreservesJournalEvidence() {
        val dir = Files.createTempDirectory("processing-journal-delete-fatal-").toFile()
        try {
            val journal = ProcessingArtifactJournal(
                transactionId = UUID.randomUUID().toString(),
                processingAttemptId = null,
                runtimeSessionId = "old-runtime",
                artifactType = "BIN",
                finalName = "result.bin",
                tempName = ".result.tmp",
                priorName = ".result.prior",
                state = ProcessingArtifactJournalState.SETTLED,
                adoptedResult = "NO_OUTPUT",
                createdAt = 1L,
                updatedAt = 2L
            )
            journal.writeTo(dir)
            processingArtifactJournalDeleteErrorForTest = AssertionError("fatal processing journal delete")
            assertThrows(AssertionError::class.java) { journal.deleteIfOwned(dir) }
            assertTrue(ProcessingArtifactJournal.list(dir).isNotEmpty())
        } finally {
            processingArtifactJournalDeleteErrorForTest = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun moveIntentWithoutPriorSettlesVerifiedUnadoptedTempAfterCrash() {
        val dir = Files.createTempDirectory("processing-move-intent-").toFile()
        try {
            val temp = File(dir, ".result.tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val final = File(dir, "result.bin")
            val journal = ProcessingArtifactJournal(
                transactionId = UUID.randomUUID().toString(),
                processingAttemptId = null,
                runtimeSessionId = "old-runtime",
                artifactType = "BIN",
                finalName = final.name,
                tempName = temp.name,
                priorName = ".result.prior",
                verificationKind = "BIN",
                expectedSizeBytes = temp.length(),
                expectedSha256 = NoFollowFileSystem.digestVerified(temp).sha256,
                state = ProcessingArtifactJournalState.NEW_FINAL_MOVE_STARTED,
                createdAt = 1L,
                updatedAt = 2L
            )
            journal.writeTo(dir)

            val result = recoverProcessingArtifactJournals(dir).single()

            assertEquals(ProcessingArtifactRecoveryClassification.SETTLED_TEMP, result.classification)
            assertFalse(temp.exists())
            assertFalse(final.exists())
            assertTrue(ProcessingArtifactJournal.list(dir).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun preExistingFinalRemainsUntouchedWhenPreparedTransactionSettlesNoOutput() {
        val dir = Files.createTempDirectory("processing-no-output-previous-final-").toFile()
        val priorFinal = File(dir, "result.bin").apply { writeText("old-valid-result") }
        val temp = File(dir, ".result.bin.tmp").apply { writeText("new-candidate") }
        val journal = ProcessingArtifactJournal(
            transactionId = UUID.randomUUID().toString(),
            processingAttemptId = null,
            runtimeSessionId = "old-runtime",
            artifactType = "BIN",
            finalName = priorFinal.name,
            tempName = temp.name,
            priorName = ".result.bin.prior",
            verificationKind = "BIN",
            expectedSizeBytes = temp.length(),
            expectedSha256 = NoFollowFileSystem.digestVerified(temp).sha256,
            state = ProcessingArtifactJournalState.PREPARED,
            createdAt = 1L,
            updatedAt = 2L
        )
        try {
            journal.writeTo(dir)
            processingArtifactJournalDeleteFailureForTest = true
            val first = recoverProcessingArtifactJournals(dir).single()
            assertEquals(ProcessingArtifactRecoveryClassification.SETTLED_NO_OUTPUT_WITH_CLEANUP_DEBT, first.classification)
            assertEquals("old-valid-result", priorFinal.readText())
            assertFalse(temp.exists())
            val retainedJournalFile = ProcessingArtifactJournal.list(dir).single()
            assertEquals("PREVIOUS_FINAL_UNTOUCHED", ProcessingArtifactJournal.read(retainedJournalFile).noOutputDisposition)

            processingArtifactJournalDeleteFailureForTest = false
            val second = recoverProcessingArtifactJournals(dir).single()
            assertEquals(ProcessingArtifactRecoveryClassification.SETTLED_TEMP, second.classification)
            assertEquals("old-valid-result", priorFinal.readText())
            assertTrue(ProcessingArtifactJournal.list(dir).isEmpty())
        } finally {
            processingArtifactJournalDeleteFailureForTest = false
            dir.deleteRecursively()
        }
    }

    @Test
    fun productionMoveIntentCrashCutConvergesWithoutPrior() {
        val dir = Files.createTempDirectory("processing-move-cut-").toFile()
        try {
            val final = File(dir, "result.bin")
            processingArtifactCrashAfterMoveIntentForTest = true
            try {
                org.junit.Assert.assertThrows(ProcessingArtifactSimulatedCrashForTest::class.java) {
                    commitProcessingArtifact(
                        finalFile = final,
                        writeTemp = { it.writeBytes(byteArrayOf(4, 5, 6)) },
                        verifyFinal = { check(it.readBytes().contentEquals(byteArrayOf(4, 5, 6))) }
                    )
                }
            } finally {
                processingArtifactCrashAfterMoveIntentForTest = false
            }
            assertEquals(ProcessingArtifactRecoveryClassification.SETTLED_TEMP, recoverProcessingArtifactJournals(dir).single().classification)
            assertFalse(final.exists())

            processingArtifactCrashAfterMoveForTest = true
            try {
                org.junit.Assert.assertThrows(ProcessingArtifactSimulatedCrashForTest::class.java) {
                    commitProcessingArtifact(
                        finalFile = final,
                        writeTemp = { it.writeBytes(byteArrayOf(7, 8, 9)) },
                        verifyFinal = { check(it.readBytes().contentEquals(byteArrayOf(7, 8, 9))) }
                    )
                }
            } finally {
                processingArtifactCrashAfterMoveForTest = false
            }
            assertTrue(final.exists())
            assertEquals(ProcessingArtifactRecoveryClassification.ADOPTED_CURRENT, recoverProcessingArtifactJournals(dir).single().classification)
            assertTrue(final.readBytes().contentEquals(byteArrayOf(7, 8, 9)))
        } finally {
            processingArtifactCrashAfterMoveIntentForTest = false
            processingArtifactCrashAfterMoveForTest = false
            dir.deleteRecursively()
        }
    }

    @Test
    fun settledNoOutputJournalUnlinkFailureRemainsRetryableNotAmbiguous() {
        val dir = Files.createTempDirectory("processing-no-output-cleanup-").toFile()
        try {
            val temp = File(dir, ".result.tmp")
            val journal = ProcessingArtifactJournal(
                transactionId = UUID.randomUUID().toString(),
                processingAttemptId = null,
                runtimeSessionId = "old-runtime",
                artifactType = "BIN",
                finalName = "result.bin",
                tempName = temp.name,
                priorName = ".result.prior",
                verificationKind = "BIN",
                expectedSizeBytes = null,
                expectedSha256 = null,
                adoptedResult = "NO_OUTPUT",
                state = ProcessingArtifactJournalState.SETTLED,
                createdAt = 1L,
                updatedAt = 2L
            )
            journal.writeTo(dir)
            processingArtifactJournalDeleteFailureForTest = true
            try {
                val first = recoverProcessingArtifactJournals(dir).single()
                assertEquals(ProcessingArtifactRecoveryClassification.SETTLED_NO_OUTPUT_WITH_CLEANUP_DEBT, first.classification)
            } finally {
                processingArtifactJournalDeleteFailureForTest = false
            }
            assertTrue(ProcessingArtifactJournal.list(dir).isNotEmpty())
            val second = recoverProcessingArtifactJournals(dir).single()
            assertEquals(ProcessingArtifactRecoveryClassification.SETTLED_TEMP, second.classification)
            assertTrue(ProcessingArtifactJournal.list(dir).isEmpty())
        } finally {
            processingArtifactJournalDeleteFailureForTest = false
            dir.deleteRecursively()
        }
    }

    @Test
    fun authoritativeJournalCreationIsSerializedPerJob() {
        val dir = Files.createTempDirectory("processing-journal-concurrent-").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "YUV_NIGHT_FUSION").put("processingMode", "CLASSIC_YUV"))
            val start = CountDownLatch(1)
            val results = (1..2).map { index ->
                executor.submit<Result<ProcessingArtifactJournal>> {
                    start.await()
                    runCatching {
                        ProcessingArtifactJournal.create(
                            jobDir = dir,
                            transactionId = UUID.randomUUID().toString(),
                            processingAttemptId = "same-attempt",
                            artifactType = "PNG",
                            finalName = "result-$index.png",
                            tempName = ".result-$index.tmp",
                            priorName = ".result-$index.prior",
                            claimKey = "finalFile"
                        )
                    }
                }
            }
            start.countDown()
            val completed: List<Result<ProcessingArtifactJournal>> = results.map { it.get() }
            assertEquals(1, completed.count { it.isSuccess })
            assertEquals(1, completed.count { it.exceptionOrNull() is ProcessingArtifactClaimConflictException })
        } finally {
            executor.shutdownNow()
            dir.deleteRecursively()
        }
    }

    @Test
    fun durableJournalExplainsAndRestoresPriorAfterSimulatedCrash() {
        val dir = Files.createTempDirectory("processing-journal-restore").toFile()
        try {
            val final = File(dir, "result.bin").apply { writeText("prior") }
            val temp = File(dir, ".result.bin.crash.tmp").apply { writeText("new") }
            val prior = File(dir, ".result.bin.crash.prior").apply { writeText("prior") }
            final.delete()
            ProcessingArtifactJournal(
                transactionId = UUID.randomUUID().toString(),
                processingAttemptId = "attempt",
                runtimeSessionId = "old-runtime",
                artifactType = "bin",
                finalName = final.name,
                tempName = temp.name,
                priorName = prior.name,
                verificationKind = "BIN",
                priorExpectedSizeBytes = prior.length(),
                priorExpectedSha256 = NoFollowFileSystem.digestVerified(prior).sha256,
                priorSemanticVerified = true,
                state = ProcessingArtifactJournalState.PRIOR_BACKED_UP,
                createdAt = 1L,
                updatedAt = 2L
            ).writeTo(dir)

            val result = recoverProcessingArtifactJournals(dir)

            assertEquals(ProcessingArtifactRecoveryClassification.RESTORED_PRIOR, result.single().classification)
            assertEquals("prior", final.readText())
            assertFalse(temp.exists())
            assertFalse(prior.exists())
            assertTrue(ProcessingArtifactJournal.list(dir).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun conflictingAuthoritativeJournalsArePreflightedWithoutJobMutation() {
        val dir = Files.createTempDirectory("processing-journal-conflict").toFile()
        try {
            val job = JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("processingMode", "CLASSIC_YUV")
                .put("processingAttemptId", "attempt-conflict")
            KeplerJobMetadata.write(dir, job)
            val first = File(dir, "first.png")
            val second = File(dir, "second.png")
            val common = ProcessingArtifactJournal(
                transactionId = UUID.randomUUID().toString(),
                processingAttemptId = "attempt-conflict",
                runtimeSessionId = "old-runtime",
                artifactType = "PNG",
                finalName = first.name,
                tempName = ".first.tmp",
                priorName = ".first.prior",
                verificationKind = "PNG",
                expectedSizeBytes = 1,
                expectedSha256 = "0".repeat(64),
                adoptedResult = "NEW_FINAL",
                claimKey = "finalFile",
                state = ProcessingArtifactJournalState.ADOPTED,
                createdAt = 1,
                updatedAt = 1
            )
            common.copy(transactionId = UUID.randomUUID().toString(), finalName = second.name, updatedAt = 2).also {
                first.writeBytes(byteArrayOf(1))
                second.writeBytes(byteArrayOf(2))
                common.writeTo(dir)
                it.writeTo(dir)
            }
            val before = KeplerJobMetadata.read(dir).toString()
            val results = recoverProcessingArtifactJournals(dir, KeplerJobMetadata.read(dir))
            assertTrue(results.all { it.classification == ProcessingArtifactRecoveryClassification.AMBIGUOUS })
            assertEquals(before, KeplerJobMetadata.read(dir).toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun existingDurableClaimDominatesDifferentFinalJournal() {
        val dir = Files.createTempDirectory("processing-journal-claimed-conflict").toFile()
        try {
            val job = JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("processingMode", "CLASSIC_YUV")
                .put("processingAttemptId", "attempt-claimed")
                .put("processingArtifactClaimAttemptId", "attempt-claimed")
                .put("processingOutputCommitted", true)
                .put("finalFile", "first.png")
            KeplerJobMetadata.write(dir, job)
            File(dir, "second.png").writeBytes(byteArrayOf(2))
            ProcessingArtifactJournal(
                transactionId = UUID.randomUUID().toString(),
                processingAttemptId = "attempt-claimed",
                runtimeSessionId = "old-runtime",
                artifactType = "PNG",
                finalName = "second.png",
                tempName = ".second.tmp",
                priorName = ".second.prior",
                verificationKind = "PNG",
                expectedSizeBytes = 1,
                expectedSha256 = "0".repeat(64),
                adoptedResult = "NEW_FINAL",
                claimKey = "finalFile",
                state = ProcessingArtifactJournalState.ADOPTED,
                createdAt = 1,
                updatedAt = 1
            ).writeTo(dir)
            val results = recoverProcessingArtifactJournals(dir, KeplerJobMetadata.read(dir))
            assertEquals(ProcessingArtifactRecoveryClassification.AMBIGUOUS, results.single().classification)
            assertEquals("first.png", KeplerJobMetadata.read(dir).getString("finalFile"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ambiguousJournalPreservesCandidates() {
        val dir = Files.createTempDirectory("processing-journal-ambiguous").toFile()
        try {
            val final = File(dir, "result.bin")
            val temp = File(dir, ".result.bin.ambiguous.tmp").apply { writeText("candidate") }
            val prior = File(dir, ".result.bin.ambiguous.prior").apply { writeText("candidate") }
            ProcessingArtifactJournal(
                transactionId = UUID.randomUUID().toString(),
                processingAttemptId = null,
                runtimeSessionId = "old-runtime",
                artifactType = "bin",
                finalName = final.name,
                tempName = temp.name,
                priorName = prior.name,
                verificationKind = "BIN",
                expectedSizeBytes = prior.length(),
                expectedSha256 = "0".repeat(64),
                state = ProcessingArtifactJournalState.NEW_FINAL_MOVED,
                createdAt = 1L,
                updatedAt = 2L
            ).writeTo(dir)

            val result = recoverProcessingArtifactJournals(dir)

            assertEquals(ProcessingArtifactRecoveryClassification.AMBIGUOUS, result.single().classification)
            assertTrue(temp.exists())
            assertTrue(prior.exists())
            assertTrue(ProcessingArtifactJournal.list(dir).isNotEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptCurrentFinalRestoresValidPriorWithoutDeletingEvidence() {
        val dir = Files.createTempDirectory("processing-journal-corrupt-current").toFile()
        try {
            val final = File(dir, "result.bin").apply { writeBytes(byteArrayOf(1)) }
            val prior = File(dir, ".result.bin.restore.prior").apply { writeText("valid-prior") }
            val id = UUID.randomUUID().toString()
            ProcessingArtifactJournal(
                transactionId = id,
                processingAttemptId = null,
                runtimeSessionId = "old-runtime",
                artifactType = "bin",
                finalName = final.name,
                tempName = ".result.bin.restore.tmp",
                priorName = prior.name,
                verificationKind = "BIN",
                expectedSizeBytes = "new-valid".toByteArray().size.toLong(),
                expectedSha256 = "0".repeat(64),
                priorExpectedSizeBytes = prior.length(),
                priorExpectedSha256 = NoFollowFileSystem.digestVerified(prior).sha256,
                priorSemanticVerified = true,
                state = ProcessingArtifactJournalState.NEW_FINAL_MOVED,
                createdAt = 1L,
                updatedAt = 2L
            ).writeTo(dir)

            val result = recoverProcessingArtifactJournals(dir).single()

            assertEquals(ProcessingArtifactRecoveryClassification.RESTORED_PRIOR, result.classification)
            assertEquals("valid-prior", final.readText())
            assertFalse(prior.exists())
            assertTrue(ProcessingArtifactJournal.list(dir).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptCurrentFinalIsReplacedByVerifiedPriorUsingAtomicReplacement() {
        val dir = Files.createTempDirectory("processing-journal-replace-").toFile()
        try {
            val final = File(dir, "result.bin").apply { writeText("corrupt") }
            val prior = File(dir, ".result.bin.replace.prior").apply { writeText("verified-prior") }
            val id = UUID.randomUUID().toString()
            ProcessingArtifactJournal(
                transactionId = id,
                processingAttemptId = null,
                runtimeSessionId = "old-runtime",
                artifactType = "bin",
                finalName = final.name,
                tempName = ".result.bin.replace.tmp",
                priorName = prior.name,
                verificationKind = "BIN",
                expectedSizeBytes = 7L,
                expectedSha256 = "0".repeat(64),
                priorExpectedSizeBytes = prior.length(),
                priorExpectedSha256 = NoFollowFileSystem.digestVerified(prior).sha256,
                priorSemanticVerified = true,
                state = ProcessingArtifactJournalState.NEW_FINAL_MOVED,
                createdAt = 1L,
                updatedAt = 2L
            ).writeTo(dir)
            assertEquals(ProcessingArtifactRecoveryClassification.RESTORED_PRIOR, recoverProcessingArtifactJournals(dir).single().classification)
            assertEquals("verified-prior", final.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun priorRestoredJournalIsVerifiedAgainstPriorEvidenceAfterRestart() {
        val dir = Files.createTempDirectory("processing-journal-prior-restored-").toFile()
        try {
            val final = File(dir, "result.bin")
            val prior = File(dir, ".result.bin.prior").apply { writeText("restored") }
            val priorEvidence = NoFollowFileSystem.digestVerified(prior)
            val id = UUID.randomUUID().toString()
            ProcessingArtifactJournal(
                transactionId = id,
                processingAttemptId = null,
                runtimeSessionId = "old-runtime",
                artifactType = "BIN",
                finalName = final.name,
                tempName = ".result.bin.tmp",
                priorName = prior.name,
                verificationKind = "BIN",
                priorExpectedSizeBytes = priorEvidence.size,
                priorExpectedSha256 = priorEvidence.sha256,
                priorSemanticVerified = true,
                adoptedResult = "PRIOR_FINAL",
                state = ProcessingArtifactJournalState.PRIOR_RESTORED,
                createdAt = 1L,
                updatedAt = 2L
            ).writeTo(dir)
            final.writeText("restored")
            val result = recoverProcessingArtifactJournals(dir).single()
            assertEquals(ProcessingArtifactRecoveryClassification.RESTORED_PRIOR, result.classification)
            assertTrue(ProcessingArtifactJournal.list(dir).isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun rollbackStartedAfterPriorMoveIsIdempotentlyRecognizedAsRestored() {
        val dir = Files.createTempDirectory("processing-journal-rollback-cut-").toFile()
        try {
            val final = File(dir, "result.bin").apply { writeText("restored-prior") }
            val prior = File(dir, ".result.bin.prior").apply { delete() }
            val evidence = NoFollowFileSystem.digestVerified(final)
            ProcessingArtifactJournal(
                transactionId = UUID.randomUUID().toString(),
                processingAttemptId = null,
                runtimeSessionId = "old-runtime",
                artifactType = "BIN",
                finalName = final.name,
                tempName = ".result.bin.tmp",
                priorName = prior.name,
                verificationKind = "BIN",
                expectedSizeBytes = 1L,
                expectedSha256 = "0".repeat(64),
                priorExpectedSizeBytes = evidence.size,
                priorExpectedSha256 = evidence.sha256,
                priorSemanticVerified = true,
                state = ProcessingArtifactJournalState.ROLLBACK_STARTED,
                createdAt = 1L,
                updatedAt = 2L
            ).writeTo(dir)
            assertEquals(ProcessingArtifactRecoveryClassification.RESTORED_PRIOR, recoverProcessingArtifactJournals(dir).single().classification)
            assertTrue(final.exists())
            assertTrue(ProcessingArtifactJournal.list(dir).isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun malformedProcessingJournalIdentityIsPreserved() {
        val dir = Files.createTempDirectory("processing-journal-hostile").toFile()
        try {
            val file = File(dir, ".processing_tx_not-a-uuid.json")
            file.writeText("{\"transactionId\":\"../escape\"}")
            val result = recoverProcessingArtifactJournals(dir)
            assertEquals(ProcessingArtifactRecoveryClassification.INVALID_JOURNAL, result.single().classification)
            assertTrue(file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun textArtifactCommitsThroughTempAndVerifiesFinal() {
        val dir = Files.createTempDirectory("processing-artifact").toFile()
        try {
            val finalFile = File(dir, "fusion_debug.json")
            val result = writeVerifiedJsonArtifact(finalFile, "{\"ok\":true}")
            assertEquals(ProcessingArtifactState.ADOPTED, result.state)
            assertEquals("{\"ok\":true}", NoFollowFileSystem.readTextVerified(finalFile))
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun failedVerificationDoesNotAdvertiseAnAdoptedFinal() {
        val dir = Files.createTempDirectory("processing-artifact-failure").toFile()
        try {
            val finalFile = File(dir, "bad.bin")
            var thrown: ProcessingArtifactException? = null
            try {
                commitProcessingArtifact(
                    finalFile,
                    writeTemp = { it.writeBytes(byteArrayOf(1, 2, 3)) },
                    verifyFinal = { error("verification failed") }
                )
            } catch (failure: ProcessingArtifactException) {
                thrown = failure
            }
            assertTrue(thrown != null)
            assertEquals(finalFile.absolutePath, thrown!!.finalFile.absolutePath)
            assertFalse(finalFile.exists())
            assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun copiedArtifactUsesVerifiedSourceAndAtomicFinal() {
        val dir = Files.createTempDirectory("processing-copy").toFile()
        try {
            val source = File(dir, "source.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val final = File(dir, "copy.bin")
            val result = copyVerifiedArtifact(source, final)
            assertEquals(ProcessingArtifactState.ADOPTED, result.state)
            assertEquals(source.readBytes().toList(), final.readBytes().toList())
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun textArtifactRejectsInvalidJsonBeforeAdoption() {
        val dir = Files.createTempDirectory("processing-json").toFile()
        try {
            val final = File(dir, "debug.json")
            var rejected = false
            try {
                writeVerifiedJsonArtifact(final, "{not-json")
            } catch (_: ProcessingArtifactException) {
                rejected = true
            }
            assertTrue(rejected)
            assertFalse(final.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun failedReplacementRestoresVerifiedPriorFinal() {
        val dir = Files.createTempDirectory("processing-artifact-restore").toFile()
        try {
            val finalFile = File(dir, "result.bin").apply { writeBytes("prior".toByteArray()) }
            var thrown: ProcessingArtifactException? = null
            try {
                commitProcessingArtifact(
                    finalFile,
                    writeTemp = { it.writeBytes("new".toByteArray()) },
                    verifyFinal = { committed ->
                        if (committed.readText() == "new") error("new verification failed")
                    }
                )
            } catch (failure: ProcessingArtifactException) {
                thrown = failure
            }
            assertTrue(thrown != null)
            assertEquals("prior", finalFile.readText())
            assertTrue(thrown!!.settlements.none { it.role == ProcessingArtifactResourceRole.RESTORED_PRIOR })
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") || it.name.endsWith(".prior") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun failedRestoreMoveRecordsTheSurvivingPriorBackupPath() {
        val dir = Files.createTempDirectory("processing-artifact-restore-move-failure").toFile()
        try {
            val finalFile = File(dir, "result.bin").apply { writeBytes("prior".toByteArray()) }
            var restoreMoveFailure: Throwable? = null
            var thrown: ProcessingArtifactException? = null
            try {
                commitProcessingArtifact(
                    finalFile,
                    writeTemp = { it.writeBytes("new".toByteArray()) },
                    verifyFinal = { error("new verification failed") },
                    move = { source, destination ->
                        if (source.name.endsWith(".prior")) {
                            restoreMoveFailure = IllegalStateException("restore move failed")
                            throw restoreMoveFailure!!
                        }
                        java.nio.file.Files.move(
                            source.toPath(),
                            destination.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                )
            } catch (failure: ProcessingArtifactException) {
                thrown = failure
            }
            assertTrue(thrown != null)
            assertTrue(thrown!!.settlements.none { it.status == ProcessingArtifactSettlementStatus.RESTORE_MOVE_FAILED })
            assertTrue(finalFile.exists())
            assertEquals("prior", finalFile.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun successfulRestoreWithFailedVerificationRecordsFinalPathAsUnverified() {
        val dir = Files.createTempDirectory("processing-artifact-restore-unverified").toFile()
        try {
            val finalFile = File(dir, "result.bin").apply { writeBytes("prior".toByteArray()) }
            val verificationFailures = mutableListOf<Throwable>()
            var thrown: ProcessingArtifactException? = null
            try {
                commitProcessingArtifact(
                    finalFile,
                    writeTemp = { it.writeBytes("new".toByteArray()) },
                    verifyFinal = {
                        val failure = IllegalStateException("verification failed")
                        verificationFailures += failure
                        throw failure
                    }
                )
            } catch (failure: ProcessingArtifactException) {
                thrown = failure
            }
            assertTrue(thrown != null)
            assertEquals(1, verificationFailures.size)
            assertEquals("prior", finalFile.readText())
            assertTrue(thrown!!.settlements.none { it.role == ProcessingArtifactResourceRole.RESTORED_PRIOR })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun writerFailureLeavesPriorFinalUntouched() {
        val dir = Files.createTempDirectory("processing-artifact-writer-failure").toFile()
        try {
            val finalFile = File(dir, "result.bin").apply { writeBytes("prior".toByteArray()) }
            var rejected = false
            try {
                commitProcessingArtifact(
                    finalFile,
                    writeTemp = { error("writer failed") },
                    verifyFinal = { error("unreachable") }
                )
            } catch (_: ProcessingArtifactException) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals("prior", finalFile.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cancellationBeforeCommitRestoresPriorFinal() {
        val dir = Files.createTempDirectory("processing-artifact-cancel").toFile()
        try {
            val finalFile = File(dir, "result.bin").apply { writeBytes("prior".toByteArray()) }
            val cancellation = TestCancellation(cancelled = true)
            var cancelled = false
            try {
                commitProcessingArtifact(
                    finalFile,
                    writeTemp = { it.writeBytes("new".toByteArray()) },
                    verifyFinal = {},
                    cancellation = cancellation
                )
            } catch (_: CancellationException) {
                cancelled = true
            } catch (_: ProcessingArtifactException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertEquals("prior", finalFile.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun truncatedPngAndJpegHeadersAreRejected() {
        val dir = Files.createTempDirectory("processing-corrupt-images").toFile()
        try {
            val png = File(dir, "bad.png").apply {
                writeBytes(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0))
            }
            val jpeg = File(dir, "bad.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)) }
            var pngRejected = false
            var jpegRejected = false
            try { verifyPngArtifact(png) } catch (_: Throwable) { pngRejected = true }
            try { verifyJpegArtifact(jpeg) } catch (_: Throwable) { jpegRejected = true }
            assertTrue(pngRejected)
            assertTrue(jpegRejected)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cancellationAfterCommitKeepsVerifiedNewFinal() {
        val dir = Files.createTempDirectory("processing-artifact-post-commit-cancel").toFile()
        try {
            val finalFile = File(dir, "result.bin")
            val cancellation = object : KeplerPipelineCancellation {
                var cancelled = false
                override val isCancelled: Boolean get() = cancelled
                override fun throwIfCancelled() {
                    if (cancelled) throw CancellationException("cancelled")
                }
            }
            val result = commitProcessingArtifact(
                finalFile,
                writeTemp = { it.writeBytes("new".toByteArray()) },
                verifyFinal = { committed ->
                    check(committed.readText() == "new")
                    if (!committed.name.endsWith(".tmp")) cancellation.cancelled = true
                },
                cancellation = cancellation
            )
            assertEquals(ProcessingArtifactState.ADOPTED, result.state)
            assertTrue(result.hadPriorFinal.not())
            assertEquals("new", finalFile.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun settlementObserverRunsExactlyOnceOnSuccess() {
        val dir = Files.createTempDirectory("processing-artifact-observer-success").toFile()
        try {
            val reports = mutableListOf<ProcessingArtifactSettlementReport>()
            val result = commitProcessingArtifact(
                finalFile = File(dir, "result.bin"),
                writeTemp = { it.writeBytes("ok".toByteArray()) },
                verifyFinal = { check(it.readText() == "ok") },
                onSettlement = { reports += it }
            )
            assertEquals(ProcessingArtifactState.ADOPTED, result.state)
            assertEquals(1, reports.size)
            assertEquals(ProcessingArtifactState.ADOPTED, reports.single().state)
            assertTrue(reports.single().settlements.any {
                it.status == ProcessingArtifactSettlementStatus.ADOPTED
            })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun settlementObserverRunsBeforeCancellationRethrow() {
        val dir = Files.createTempDirectory("processing-artifact-observer-cancel").toFile()
        try {
            val reports = mutableListOf<ProcessingArtifactSettlementReport>()
            val cancellation = TestCancellation(cancelled = true)
            var cancelled = false
            try {
                commitProcessingArtifact(
                    finalFile = File(dir, "result.bin"),
                    writeTemp = { it.writeBytes("never".toByteArray()) },
                    verifyFinal = {},
                    cancellation = cancellation,
                    onSettlement = { reports += it }
                )
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertEquals(1, reports.size)
            assertTrue(reports.single().settlements.isNotEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fatalCleanupBeforeAdoptionPropagatesAndLeavesEvidence() {
        val dir = Files.createTempDirectory("processing-artifact-fatal-delete-pre-adoption").toFile()
        val fatal = AssertionError("fatal artifact delete")
        try {
            processingArtifactDeleteErrorForTest = fatal
            val finalFile = File(dir, "result.bin")
            assertThrows(AssertionError::class.java) {
                commitProcessingArtifact(
                    finalFile,
                    writeTemp = { it.writeBytes("new".toByteArray()) },
                    verifyFinal = { error("ordinary verification failure") }
                )
            }
            assertFalse(finalFile.exists())
            assertTrue(dir.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
            assertTrue(ProcessingArtifactJournal.list(dir).isNotEmpty())
        } finally {
            processingArtifactDeleteErrorForTest = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun fatalCleanupAfterAdoptionPropagatesWithoutDeletingCurrentFinal() {
        val dir = Files.createTempDirectory("processing-artifact-fatal-delete-adopted").toFile()
        val fatal = AssertionError("fatal prior cleanup")
        try {
            val finalFile = File(dir, "result.bin").apply { writeBytes("prior".toByteArray()) }
            processingArtifactDeleteErrorForTest = fatal
            val result = try {
                commitProcessingArtifact(
                    finalFile,
                    writeTemp = { it.writeBytes("new".toByteArray()) },
                    verifyFinal = { file ->
                        check(file.readText() == "new")
                    }
                )
            } catch (failure: AssertionError) {
                assertSame(fatal, failure)
                null
            }
            assertTrue(result == null)
            assertEquals("new", finalFile.readText())
            assertTrue(ProcessingArtifactJournal.list(dir).isNotEmpty())
        } finally {
            processingArtifactDeleteErrorForTest = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun fatalPriorSemanticVerificationPropagatesBeforePriorMove() {
        val dir = Files.createTempDirectory("processing-artifact-fatal-prior-verify").toFile()
        val fatal = AssertionError("fatal prior semantic verification")
        try {
            val finalFile = File(dir, "result.bin").apply { writeBytes("prior".toByteArray()) }
            assertThrows(AssertionError::class.java) {
                commitProcessingArtifact(
                    finalFile,
                    writeTemp = { it.writeBytes("new".toByteArray()) },
                    verifyFinal = { file ->
                        if (file == finalFile) throw fatal
                    }
                )
            }
            assertEquals("prior", finalFile.readText())
        } finally {
            processingArtifactDeleteErrorForTest = null
            dir.deleteRecursively()
        }
    }
}
