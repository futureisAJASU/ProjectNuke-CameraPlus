package com.projectnuke.keplernightlab

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CancellationException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ProcessingArtifactTransactionTest {
    private class TestCancellation(var cancelled: Boolean = false) : KeplerPipelineCancellation {
        override val isCancelled: Boolean get() = cancelled
        override fun throwIfCancelled() {
            if (cancelled) throw CancellationException("cancelled")
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
                state = ProcessingArtifactJournalState.NEW_FINAL_MOVED,
                createdAt = 1L,
                updatedAt = 2L
            ).writeTo(dir)
            assertEquals(ProcessingArtifactRecoveryClassification.RESTORED_PRIOR, recoverProcessingArtifactJournals(dir).single().classification)
            assertEquals("verified-prior", final.readText())
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
}
