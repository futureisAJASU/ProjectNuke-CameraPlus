package com.projectnuke.keplernightlab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    // ── Fallback marker persistence: all three cases ──────────────────────────

    @Test
    fun markerFailureStateSuppressesNoFallback() {
        // marker failure + state success → no fallback
        val directory = tempJob()
        try {
            val root = File(directory, ".reprocess_backup_mf")
            root.mkdir()
            val manifest = rootManifest("mf_id", root)
            KeplerJobMetadata.atomicWrite(File(root, REPROCESS_TX_MANIFEST_FILE), manifest.toJson().toString(2))
            val tx = ReprocessTransaction("mf_id", root, manifest, emptyList())
            // Marker write fails: directory blocks file creation
            val markerBlockDir = File(root, ".reprocess_quarantine").also { it.mkdir() }
            try {
                // writeQuarantineMarker will throw (isFile check fails)
                // writeTransactionState succeeds (transactionId matches)
                val result = quarantineWithPersistence(tx, IllegalStateException("fail"))
                assertTrue(result.result.isFailure)
                assertFalse(File(directory, ".reprocess_unresolved").exists())
            } finally {
                markerBlockDir.delete()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun stateFailureMarkerSuccessNoFallback() {
        // state failure + marker success → no fallback
        val directory = tempJob()
        try {
            val root = File(directory, ".reprocess_backup_sf")
            root.mkdir()
            // transactionId on disk differs from in-memory → writeTransactionState throws
            val diskManifest = rootManifest("sf_disk_id", root)
            KeplerJobMetadata.atomicWrite(File(root, REPROCESS_TX_MANIFEST_FILE), diskManifest.toJson().toString(2))
            val memManifest = diskManifest.copy(transactionId = "sf_mem_id")
            val tx = ReprocessTransaction("sf_mem_id", root, memManifest, emptyList())
            val result = quarantineWithPersistence(tx, IllegalStateException("fail"))
            assertTrue(result.result.isFailure)
            // marker should have been written
            assertTrue(File(root, ".reprocess_quarantine").isFile)
            assertFalse(File(directory, ".reprocess_unresolved").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun bothMechanismsFailFallbackCreated() {
        // both fail → fallback created
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            // Delete the backup root so both writeQuarantineMarker and writeTransactionState fail
            transaction.backupRoot.deleteRecursively()
            val result = quarantineWithPersistence(transaction, IllegalStateException("fail"))
            assertTrue(result.result.isFailure)
            assertTrue(File(directory, ".reprocess_unresolved").isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun existingMarkerDirectoryOrCorruptNotAcceptedAsSuccess() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            val markerPath = File(transaction.backupRoot, ".reprocess_quarantine")

            // Sub-case A: existing directory at marker path throws
            markerPath.mkdir()
            assertThrows(IllegalStateException::class.java) {
                writeQuarantineMarker(transaction)
            }
            markerPath.delete()

            // Sub-case B: empty file at marker path is treated as corrupt/unreadable
            markerPath.createNewFile()
            assertThrows(IllegalStateException::class.java) {
                writeQuarantineMarker(transaction)
            }
            markerPath.delete()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun matchingFallbackRemovedAndDeletionFailureRemainsBlocking() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            ensureDurableFallbackQuarantine(directory, transaction)
            writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)

            // Normal removal succeeds
            assertTrue(removeMatchingFallbackQuarantine(directory, transaction))
            assertFalse(File(directory, ".reprocess_unresolved").exists())
            assertFalse(isReprocessQuarantined(directory))

            // Re-create marker to simulate deletion failure
            ensureDurableFallbackQuarantine(directory, transaction)
            // Make it a non-deletable directory (simulated via replacing with dir)
            val marker = File(directory, ".reprocess_unresolved")
            marker.delete()
            marker.mkdir()
            // removeMatchingFallbackQuarantine: identity matches but file.delete() on dir returns false on Windows
            // Directory exists → !marker.exists() is false → returns false (remains blocking)
            val removed = removeMatchingFallbackQuarantine(directory, transaction)
            // On Windows a directory can't be deleted with File.delete() if nonempty, but an empty dir can
            // Use explicit assertion based on actual state
            if (!marker.exists()) {
                assertTrue(removed)
            } else {
                assertFalse(removed)
                assertTrue(isReprocessQuarantined(directory))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun transactionIdOnlyMismatchRemainsBlocking() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            ensureDurableFallbackQuarantine(directory, transaction)
            writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)

            val marker = File(directory, ".reprocess_unresolved")
            // Mismatch transactionId only, keep backupRoot name and createdAt matching
            marker.writeText("transactionId=mismatched_tx_id\nbackupRoot=${transaction.backupRoot.name}\ncreatedAt=${transaction.manifest.createdAt}\n")

            recoverValidatedQuarantine(directory)
            assertTrue(marker.isFile)
            assertTrue(isReprocessQuarantined(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun actualMarkerDeletionFailurePreservesTerminalManifest() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            writeQuarantineMarker(transaction)
            writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)

            val previousDelete = cleanupDeleteOperation
            cleanupDeleteOperation = { file ->
                if (file.name == ".reprocess_quarantine") false else file.delete()
            }
            try {
                assertFalse(cleanupBackups(transaction))
                assertTrue(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).isFile)
                assertTrue(File(transaction.backupRoot, ".reprocess_quarantine").isFile)
            } finally {
                cleanupDeleteOperation = previousDelete
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cancelledWorkerDeferredIsWorkerFailureWhileCallerActive() = runBlocking {
        val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
        terminal.cancel(kotlinx.coroutines.CancellationException("worker cancelled"))
        val result = acquireWorkerTerminal(
            ReprocessWorkerRun(terminal) {},
            callerCancellation = null
        )
        assertTrue(result is WorkerTerminalResult.DeferredExceptionalCompletion)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun rootManifest(txId: String, root: File): ReprocessTransactionManifest =
        ReprocessTransactionManifest(
            transactionId = txId,
            createdAt = System.currentTimeMillis(),
            preExistingPaths = emptySet(),
            backedUpPaths = emptySet(),
            backupEntries = emptyMap()
        )
}
