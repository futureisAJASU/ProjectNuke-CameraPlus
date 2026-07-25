package com.projectnuke.keplernightlab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class KeplerGalleryReprocessProtocolTest {

    @Test
    fun immutableIdentitySurvivesQuarantineTerminalTransitionAndCleanup() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            writeTransactionState(transaction, ReprocessTransactionState.QUARANTINED)
            assertTrue(validateTransactionIdentity(directory, transaction))
            writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
            assertTrue(validateTransactionIdentity(directory, transaction))
            assertTrue(cleanupBackups(transaction))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fallbackIsOnlyCreatedWhenRootQuarantineCannotPersist() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            val rootResult = quarantineWithPersistence(transaction, IllegalStateException("failure"))
            assertTrue(rootResult.result.isFailure)
            assertFalse(File(directory, ".reprocess_unresolved").exists())

            transaction.backupRoot.deleteRecursively()
            val missingRoot = quarantineWithPersistence(transaction, IllegalStateException("failure"))
            assertTrue(missingRoot.result.isFailure)
            assertTrue(File(directory, ".reprocess_unresolved").isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun strictManifestAcceptsOnlyCompleteSafeManifest() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            assertTrue(validateTransactionIdentity(directory, transaction))

            val manifest = File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE)
            manifest.writeText(JSONObject(manifest.readText()).remove("backupEntries").toString())
            assertFalse(validateTransactionIdentity(directory, transaction))
            assertTrue(isReprocessQuarantined(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unsafeAndMismatchedManifestEvidenceFailsClosed() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            val manifest = File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE)
            val json = JSONObject(manifest.readText())
            json.put("transactionId", "other")
            json.getJSONObject("backupEntries").getJSONObject("final.png").put("relativePath", "../escape")
            manifest.writeText(json.toString())

            assertFalse(validateTransactionIdentity(directory, transaction))
            assertTrue(isReprocessQuarantined(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun exactRollbackRestoresSameSizeContentAndDeletesCreatedOutputs() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            File(directory, "final.png").writeText("after!")
            File(directory, "reprocess_preview_new.png").writeText("created")

            assertTrue(restoreBackups(directory, transaction).isSuccess)
            assertEquals("before", File(directory, "final.png").readText())
            assertTrue(
                removeCreatedForTest(directory, transaction).isSuccess
            )
            assertFalse(File(directory, "reprocess_preview_new.png").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptDurableManifestDoesNotFallBackToMemoryRollback() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            File(directory, "final.png").writeText("after!")
            File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).writeText("{bad")

            assertTrue(restoreBackups(directory, transaction).isFailure)
            assertEquals("after!", File(directory, "final.png").readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ownerLeaseBlocksExternalMutationAndInternalOwnerIsRecognized() = runBlocking {
        val directory = tempJob()
        try {
            val lease = KeplerJobMetadata.acquireOperation(directory)!!
            assertTrue(KeplerJobMetadata.isOperationActive(directory))
            assertTrue(KeplerJobMetadata.isOperationOwner(directory, lease))
            val frames = listOf(frame(directory))
            assertFalse(saveFrameSelection(directory, FrameSelectionMode.AUTO_RULE_BASED, frames).isSuccess)
            assertTrue(saveFrameSelectionInternal(directory, FrameSelectionMode.AUTO_RULE_BASED, frames, lease).isSuccess)
            lease.release()
            assertFalse(KeplerJobMetadata.isOperationActive(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cancelFailureDoesNotPermitRollbackBeforeWorkerExit() = runBlocking {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            File(directory, "final.png").writeText("after!")
            val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
            val previousTimeout = reprocessWorkerExitTimeoutMsForTest
            val previousReprocessTimeout = reprocessTimeoutMsForTest
            reprocessWorkerExitTimeoutMsForTest = 1L
            reprocessTimeoutMsForTest = 1L
            try {
                val result = acquireWorkerTerminal(
                    ReprocessWorkerRun(terminal) { throw IllegalStateException("cancel failed") },
                    callerCancellation = null
                )
                assertTrue(result is WorkerTerminalResult.WorkerDidNotExitBeforeTimeout)
                assertEquals("after!", File(directory, "final.png").readText())
            } finally {
                reprocessWorkerExitTimeoutMsForTest = previousTimeout
                reprocessTimeoutMsForTest = previousReprocessTimeout
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun exceptionalDeferredCompletionUsesRealLateCallbackAndRollsBackOnce() = runBlocking {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            val session = ReprocessTransactionSession(directory)
            val owner = session.acquireLease() ?: error("owner lease missing")
            assertTrue(KeplerJobMetadata.isOperationOwner(directory, owner))
            session.transferOwnership(transaction)
            File(directory, "final.png").writeText("after!")
            val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
            val handoff = registerLateFinalization(
                session,
                ReprocessWorkerRun(terminal) {},
                directory,
                ReprocessJobKind.RAW_FUSION,
                FinalOutputFormat.JPEG,
                FrameSelectionMode.AUTO_RULE_BASED,
                emptySet()
            )
            assertNotNull(handoff)
            assertNull(registerLateFinalization(session, null, directory, ReprocessJobKind.RAW_FUSION,
                FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet()))

            terminal.completeExceptionally(IllegalStateException("worker failed"))
            withTimeout(5_000L) {
                while (KeplerJobMetadata.isOperationActive(directory)) kotlinx.coroutines.yield()
            }
            assertEquals("before", File(directory, "final.png").readText())
            assertFalse(KeplerJobMetadata.isOperationActive(directory))
            assertTrue(owner === owner)
        } finally {
            lateFinalizationHandoffScope = null
            directory.deleteRecursively()
        }
    }

    @Test
    fun cleanupKeepsTerminalManifestWhenUnknownPayloadRemains() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
            File(transaction.backupRoot, "unknown").mkdir()
            assertFalse(cleanupBackups(transaction))
            assertTrue(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun tempJob(): File = Files.createTempDirectory("kepler-reprocess-").toFile().also {
        KeplerJobMetadata.write(it, JSONObject().put("jobType", "RAW_NIGHT_FUSION"))
    }

    private fun backup(directory: File, vararg files: Pair<String, String>): ReprocessTransaction {
        files.forEach { (name, contents) -> File(directory, name).writeText(contents) }
        return backupReprocessTransaction(directory, files.map { File(directory, it.first) }).getOrThrow()
    }

    private fun frame(directory: File) = KeplerFrameReviewItem(
        index = 0,
        file = File(directory, "frame_0001.raw16").apply { writeText("raw") },
        fileName = "frame_0001.raw16",
        included = true,
        recommendedInclude = true,
        userDecision = FrameUserDecision.AUTO,
        quality = null,
        thumbnailFile = null,
        reason = null
    )

    private fun removeCreatedForTest(directory: File, transaction: ReprocessTransaction): Result<Unit> =
        removeTransactionCreatedFilesForTest(directory, transaction)

    @Test
    fun terminalManifestRemainsAuthoritativeAfterStateTransition() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            writeTransactionState(transaction, ReprocessTransactionState.QUARANTINED)
            writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
            assertTrue(validateTransactionIdentity(directory, transaction))
            assertTrue(cleanupBackups(transaction))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun lateRegistrationAllowsOnlyExplicitUnresolvedRetry() {
        val session = ReprocessTransactionSession(Files.createTempDirectory("kepler-session-").toFile())
        try {
            assertTrue(session.tryAcquireLateRegistration())
            assertFalse(session.tryAcquireLateRegistration())
            session.markLateUnresolved()
            assertTrue(session.tryAcquireLateRegistration())
            assertFalse(session.tryAcquireLateRegistration())
        } finally {
            session.releaseIfUnowned()
            session.jobDir.deleteRecursively()
        }
    }

    @Test
    fun mismatchedOrCorruptFallbackRemainsBlocking() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            ensureDurableFallbackQuarantine(directory, transaction)
            assertTrue(isReprocessQuarantined(directory))

            val marker = File(directory, ".reprocess_unresolved")
            marker.writeText("transactionId=corrupt\nbackupRoot=bad\ncreatedAt=0\n")
            assertTrue(isReprocessQuarantined(directory))
            assertFalse(removeMatchingFallbackQuarantine(directory, transaction))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun matchingTerminalFallbackRemovedAndCrashAfterTerminalStateRecovers() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            ensureDurableFallbackQuarantine(directory, transaction)
            assertTrue(isReprocessQuarantined(directory))

            writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
            // Simulated restart recovery
            recoverValidatedQuarantine(directory)
            assertFalse(File(directory, ".reprocess_unresolved").exists())
            assertFalse(isReprocessQuarantined(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun payloadOrMarkerDeletionFailurePreservesManifest() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
            val previousDelete = cleanupDeleteOperation
            cleanupDeleteOperation = { file ->
                if (file.name.endsWith(".backup")) false else file.delete()
            }
            try {
                assertFalse(cleanupBackups(transaction))
                assertTrue(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).isFile)
            } finally {
                cleanupDeleteOperation = previousDelete
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unknownDirectoryPreservesManifestAndImmediateAndRestartCleanupMatch() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
            File(transaction.backupRoot, "unknown_dir").mkdir()

            assertFalse(cleanupBackups(transaction))
            assertTrue(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).isFile)

            recoverValidatedQuarantine(directory)
            assertTrue(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).isFile)
        } finally {
            directory.deleteRecursively()
        }
    }
}
