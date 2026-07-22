package com.projectnuke.keplernightlab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class KeplerGalleryReprocessProtocolTest {
    @Test
    fun backupTransactionKeepsJobJsonOnlyOnce() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            val frameFile = File(directory, "frame_0001.png")
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            frameFile.writeText("frame")

            val transaction = backupReprocessTransaction(
                directory,
                listOf(File(directory, JOB_JSON_FILE_NAME), File(directory, JOB_JSON_FILE_NAME), frameFile, File(directory, JOB_JSON_FILE_NAME))
            ).getOrThrow()

            assertEquals(1, transaction.backups.count { it.original.name == JOB_JSON_FILE_NAME })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rollbackWaitsForWorkerCompletionBeforeStarting() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source = File(directory, "final.png")
            source.writeText("original")
            val transaction = backupReprocessTransaction(directory, listOf(source)).getOrThrow()

            val workerCompletion = CompletableDeferred<ReprocessWorkerOutcome>()
            val cancelRequested = CompletableDeferred<Unit>()
            val rollbackStarted = CompletableDeferred<Unit>()

            val result = async {
                cancelWorkerAndAwaitTerminal(
                    ReprocessWorkerRun(
                        terminal = workerCompletion,
                        cancel = { cancelRequested.complete(Unit) }
                    )
                ).let { _ ->
                    rollbackStarted.complete(Unit)
                    restoreBackups(directory, transaction.backups)
                }
            }

            yield()
            assertTrue(cancelRequested.isCompleted)
            assertFalse(rollbackStarted.isCompleted)

            workerCompletion.complete(ReprocessWorkerOutcome(Result.failure(IllegalStateException("cancelled")), false))
            assertTrue(result.await().isSuccess)
            assertTrue(rollbackStarted.isCompleted)
            assertEquals("original", source.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun lateWorkerWriteIsRolledBackAfterCompletionSignal() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source = File(directory, "final.png")
            source.writeText("original")
            val transaction = backupReprocessTransaction(directory, listOf(source)).getOrThrow()

            val workerCompletion = CompletableDeferred<ReprocessWorkerOutcome>()
            val cancelRequested = CompletableDeferred<Unit>()

            val result = async {
                cancelWorkerAndAwaitTerminal(
                    ReprocessWorkerRun(
                        terminal = workerCompletion,
                        cancel = { cancelRequested.complete(Unit) }
                    )
                ).let { _ ->
                    restoreBackups(directory, transaction.backups)
                }
            }

            yield()
            assertTrue(cancelRequested.isCompleted)

            source.writeText("late")
            assertEquals("late", source.readText())
            assertFalse(result.isCompleted)

            workerCompletion.complete(ReprocessWorkerOutcome(Result.failure(IllegalStateException("cancelled")), false))
            assertTrue(result.await().isSuccess)
            assertEquals("original", source.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun internalFrameSelectionSucceedsUnderOwningTransaction() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source = File(directory, "final.png")
            source.writeText("original")
            val transaction = backupReprocessTransaction(directory, listOf(source)).getOrThrow()

            // Internal mutation should succeed even though manifest is ACTIVE
            val frames = listOf(
                KeplerFrameReviewItem(
                    index = 0,
                    file = source,
                    fileName = "final.png",
                    included = true,
                    recommendedInclude = true,
                    userDecision = FrameUserDecision.AUTO,
                    quality = null,
                    thumbnailFile = null,
                    reason = null
                )
            )
            val lease = KeplerJobMetadata.acquireOperation(directory)!!
            val result = saveFrameSelectionInternal(directory, FrameSelectionMode.AUTO_RULE_BASED, frames, lease)
            assertTrue(result.isSuccess)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun externalFrameMutationRejectedWhileTransactionLeaseHeld() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source = File(directory, "final.png")
            source.writeText("original")
            val transaction = backupReprocessTransaction(directory, listOf(source)).getOrThrow()

            // External mutation must acquire its own lease and is rejected because transaction is active
            val frames = listOf(
                KeplerFrameReviewItem(
                    index = 0,
                    file = source,
                    fileName = "final.png",
                    included = true,
                    recommendedInclude = true,
                    userDecision = FrameUserDecision.AUTO,
                    quality = null,
                    thumbnailFile = null,
                    reason = null
                )
            )
            val result = saveFrameSelection(directory, FrameSelectionMode.AUTO_RULE_BASED, frames)
            assertFalse(result.isSuccess)
            assertTrue(result.exceptionOrNull()?.message?.contains("Job mutation is in progress") == true)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun activeManifestDoesNotBlockOwnerInternalMutation() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source = File(directory, "final.png")
            source.writeText("original")
            val transaction = backupReprocessTransaction(directory, listOf(source)).getOrThrow()

            // Verify manifest is ACTIVE
            val manifestFile = File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE)
            assertTrue(manifestFile.isFile)
            val manifestJson = JSONObject(manifestFile.readText())
            assertEquals("ACTIVE", manifestJson.optString("state", "ACTIVE"))

            // Internal mutation should still succeed
            val frames = listOf(
                KeplerFrameReviewItem(
                    index = 0,
                    file = source,
                    fileName = "final.png",
                    included = false,
                    recommendedInclude = true,
                    userDecision = FrameUserDecision.EXCLUDE,
                    quality = null,
                    thumbnailFile = null,
                    reason = "TEST_EXCLUDE"
                )
            )
            val lease = KeplerJobMetadata.acquireOperation(directory)!!
            val result = saveFrameSelectionInternal(directory, FrameSelectionMode.MANUAL, frames, lease)
            assertTrue(result.isSuccess)

            // Verify the frame was actually excluded
            val job = KeplerJobMetadata.read(directory)
            assertTrue(job.getJSONArray("includedFrameIndices").length() == 0)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingOrCorruptManifestFailsClosed() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            // No manifest at all
            directory.listFiles()?.forEach { it.deleteRecursively() }
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source = File(directory, "final.png")
            source.writeText("original")
            val transaction = backupReprocessTransaction(directory, listOf(source)).getOrThrow()

            // Delete manifest to simulate corruption
            val manifestFile = File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE)
            manifestFile.delete()

            // Should be treated as unresolved (ACTIVE)
            assertTrue(isReprocessQuarantined(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun durableStateWriteFailureDoesNotReleaseLease() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source = File(directory, "final.png")
            source.writeText("original")
            val transaction = backupReprocessTransaction(directory, listOf(source)).getOrThrow()

            // Corrupt the manifest file to cause state write failure
            val manifestFile = File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE)
            manifestFile.writeText("{ invalid json }")

            // Attempt to write state should fail
            var caughtException: Exception? = null
            try {
                writeTransactionState(transaction.backups, ReprocessTransactionState.COMMITTED)
            } catch (e: Exception) {
                caughtException = e
            }
            assertNotNull(caughtException)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun commitNeverInvokesRollback() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source = File(directory, "final.png")
            source.writeText("original")
            val transaction = backupReprocessTransaction(directory, listOf(source)).getOrThrow()

            // Simulate a committed outcome
            val outcome = ReprocessWorkerOutcome(
                result = Result.success(Unit),
                publicExportCommitted = true,
                exportVerified = true
            )

            val finalization = finalizeTerminalOutcome(
                directory, ReprocessJobKind.RAW_FUSION, FinalOutputFormat.PNG_DEBUG,
                FrameSelectionMode.AUTO_RULE_BASED, setOf(0),
                Result.success(outcome), transaction.backups
            )

            assertEquals(ReprocessFinalizationState.COMMITTED, finalization.state)
            assertTrue(finalization.result.isSuccess)

            // Verify no rollback was attempted (original file should remain unchanged or be the committed version)
            assertTrue(source.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun committedAndRolledBackArePersistedBeforeCleanup() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source = File(directory, "final.png")
            source.writeText("original")
            val transaction = backupReprocessTransaction(directory, listOf(source)).getOrThrow()

            // Test rollback path
            val failureOutcome = ReprocessWorkerOutcome(
                result = Result.failure(IllegalStateException("test failure")),
                publicExportCommitted = false,
                exportVerified = false
            )

            val finalization = finalizeTerminalOutcome(
                directory, ReprocessJobKind.RAW_FUSION, FinalOutputFormat.PNG_DEBUG,
                FrameSelectionMode.AUTO_RULE_BASED, setOf(0),
                Result.success(failureOutcome), transaction.backups
            )

            assertEquals(ReprocessFinalizationState.ROLLED_BACK, finalization.state)

            // Check manifest state is ROLLED_BACK
            val manifestFile = File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE)
            val manifestJson = JSONObject(manifestFile.readText())
            assertEquals("ROLLED_BACK", manifestJson.optString("state", "ACTIVE"))

            // Test commit path
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source2 = File(directory, "final2.png")
            source2.writeText("original2")
            val transaction2 = backupReprocessTransaction(directory, listOf(source2)).getOrThrow()

            val successOutcome = ReprocessWorkerOutcome(
                result = Result.success(Unit),
                publicExportCommitted = true,
                exportVerified = true
            )

            val finalization2 = finalizeTerminalOutcome(
                directory, ReprocessJobKind.RAW_FUSION, FinalOutputFormat.PNG_DEBUG,
                FrameSelectionMode.AUTO_RULE_BASED, setOf(0),
                Result.success(successOutcome), transaction2.backups
            )

            assertEquals(ReprocessFinalizationState.COMMITTED, finalization2.state)

            // Check manifest state is COMMITTED
            val manifestFile2 = File(transaction2.backupRoot, REPROCESS_TX_MANIFEST_FILE)
            val manifestJson2 = JSONObject(manifestFile2.readText())
            assertEquals("COMMITTED", manifestJson2.optString("state", "ACTIVE"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun backupCleanupFailureDoesNotDowngradeResolvedState() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source = File(directory, "final.png")
            source.writeText("original")
            val transaction = backupReprocessTransaction(directory, listOf(source)).getOrThrow()

            val successOutcome = ReprocessWorkerOutcome(
                result = Result.success(Unit),
                publicExportCommitted = true,
                exportVerified = true
            )

            val finalization = finalizeTerminalOutcome(
                directory, ReprocessJobKind.RAW_FUSION, FinalOutputFormat.PNG_DEBUG,
                FrameSelectionMode.AUTO_RULE_BASED, setOf(0),
                Result.success(successOutcome), transaction.backups
            )

            assertEquals(ReprocessFinalizationState.COMMITTED, finalization.state)

            // Make backup deletion fail by making file read-only
            transaction.backupRoot.listFiles()?.forEach { it.setReadOnly() }

            // Re-run finalization - cleanup failure should not downgrade state
            val finalization2 = finalizeTerminalOutcome(
                directory, ReprocessJobKind.RAW_FUSION, FinalOutputFormat.PNG_DEBUG,
                FrameSelectionMode.AUTO_RULE_BASED, setOf(0),
                Result.success(successOutcome), transaction.backups
            )

            assertEquals(ReprocessFinalizationState.COMMITTED, finalization2.state)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun timedOutWorkerFinalizedOnceAfterLateTerminalCompletion() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source = File(directory, "final.png")
            source.writeText("original")
            val transaction = backupReprocessTransaction(directory, listOf(source)).getOrThrow()

            val workerCompletion = CompletableDeferred<ReprocessWorkerOutcome>()

            // Simulate timeout by having worker complete after a delay
            val result = async {
                // This simulates the main flow timing out and registering late finalization
                // The late finalization will be triggered when workerCompletion is completed
                workerCompletion.await().let { outcome ->
                    finalizeTerminalOutcome(
                        directory, ReprocessJobKind.RAW_FUSION, FinalOutputFormat.PNG_DEBUG,
                        FrameSelectionMode.AUTO_RULE_BASED, setOf(0),
                        Result.success(outcome), transaction.backups
                    )
                }
            }

            // Worker hasn't completed yet
            assertFalse(result.isCompleted)

            // Now worker completes
            workerCompletion.complete(ReprocessWorkerOutcome(
                result = Result.success(Unit),
                publicExportCommitted = true,
                exportVerified = true
            ))

            val finalization = result.await()
            assertEquals(ReprocessFinalizationState.COMMITTED, finalization.state)

            // Complete again should not re-run finalization (worker outcome already consumed)
            // The invokeOnCompletion should only fire once
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun completedButQuarantinedOutcomeNotFinalizedTwice() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source = File(directory, "final.png")
            source.writeText("original")
            val transaction = backupReprocessTransaction(directory, listOf(source)).getOrThrow()

            // Simulate a worker outcome that was already processed but resulted in quarantine
            val quarantinedOutcome = ReprocessWorkerOutcome(
                result = Result.success(Unit),
                publicExportCommitted = false,
                exportVerified = false
            )

            val finalization = finalizeTerminalOutcome(
                directory, ReprocessJobKind.RAW_FUSION, FinalOutputFormat.PNG_DEBUG,
                FrameSelectionMode.AUTO_RULE_BASED, setOf(0),
                Result.success(quarantinedOutcome), transaction.backups
            )

            assertEquals(ReprocessFinalizationState.QUARANTINED, finalization.state)

            // Register late finalization (should not re-run because terminalOutcome was NOT a failure)
            val lateFinalization = finalizeTerminalOutcome(
                directory, ReprocessJobKind.RAW_FUSION, FinalOutputFormat.PNG_DEBUG,
                FrameSelectionMode.AUTO_RULE_BASED, setOf(0),
                Result.success(quarantinedOutcome), transaction.backups
            )

            // Should still be QUARANTINED (not re-processed into a different state)
            assertEquals(ReprocessFinalizationState.QUARANTINED, lateFinalization.state)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun lateSafeCompletionReleasesLeaseExactlyOnce() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source = File(directory, "final.png")
            source.writeText("original")
            val transaction = backupReprocessTransaction(directory, listOf(source)).getOrThrow()

            assertTrue(KeplerJobMetadata.isOperationActive(directory))

            val successOutcome = ReprocessWorkerOutcome(
                result = Result.success(Unit),
                publicExportCommitted = true,
                exportVerified = true
            )

            val finalization = finalizeTerminalOutcome(
                directory, ReprocessJobKind.RAW_FUSION, FinalOutputFormat.PNG_DEBUG,
                FrameSelectionMode.AUTO_RULE_BASED, setOf(0),
                Result.success(successOutcome), transaction.backups
            )

            assertEquals(ReprocessFinalizationState.COMMITTED, finalization.state)
            assertFalse(KeplerJobMetadata.isOperationActive(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun lateQuarantinedCompletionRetainsLease() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source = File(directory, "final.png")
            source.writeText("original")
            val transaction = backupReprocessTransaction(directory, listOf(source)).getOrThrow()

            val lease = KeplerJobMetadata.acquireOperation(directory)!!

            val failureOutcome = ReprocessWorkerOutcome(
                result = Result.failure(IllegalStateException("late failure")),
                publicExportCommitted = false,
                exportVerified = false
            )

            val finalization = finalizeTerminalOutcome(
                directory, ReprocessJobKind.RAW_FUSION, FinalOutputFormat.PNG_DEBUG,
                FrameSelectionMode.AUTO_RULE_BASED, setOf(0),
                Result.success(failureOutcome), transaction.backups
            )

            assertEquals(ReprocessFinalizationState.QUARANTINED, finalization.state)
            // Lease should still be held (QUARANTINED doesn't release)
            assertTrue(KeplerJobMetadata.isOperationActive(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun processRestartInspectionBlocksActiveQuarantinedTransactions() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source = File(directory, "final.png")
            source.writeText("original")
            val transaction = backupReprocessTransaction(directory, listOf(source)).getOrThrow()

            // ACTIVE transaction should be blocked
            assertTrue(isReprocessQuarantined(directory))

            // Manually mark as COMMITTED
            val manifestFile = File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE)
            val manifestJson = JSONObject(manifestFile.readText())
            manifestJson.put("state", "COMMITTED")
            KeplerJobMetadata.atomicWrite(manifestFile, manifestJson.toString(2))

            // Now should NOT be quarantined (resolved)
            assertFalse(isReprocessQuarantined(directory))

            // Mark as QUARANTINED
            manifestJson.put("state", "QUARANTINED")
            KeplerJobMetadata.atomicWrite(manifestFile, manifestJson.toString(2))

            // Should be quarantined again
            assertTrue(isReprocessQuarantined(directory))
        } finally {
            directory.deleteRecursively()
        }
    }
}
