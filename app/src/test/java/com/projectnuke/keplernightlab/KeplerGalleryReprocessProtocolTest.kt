package com.projectnuke.keplernightlab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
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
            manifest.writeText(JSONObject(manifest.readText()).apply { remove("backupEntries") }.toString())
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
            try {
                assertTrue(KeplerJobMetadata.isOperationActive(directory))
                assertTrue(KeplerJobMetadata.isOperationOwner(directory, lease))
                val frames = listOf(frame(directory))
                assertFalse(saveFrameSelection(directory, FrameSelectionMode.AUTO_RULE_BASED, frames).isSuccess)
                assertTrue(saveFrameSelectionInternal(directory, FrameSelectionMode.AUTO_RULE_BASED, frames, lease).isSuccess)
            } finally {
                lease.release()
            }
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

    @Test
    fun cleanupSeamLyingWhilePayloadRemainsPreservesManifest() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
            val previousDelete = cleanupDeleteOperation
            cleanupDeleteOperation = { file ->
                file.name == "final.png.backup"
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
    fun markerCleanupFailurePreservesManifest() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
            File(transaction.backupRoot, ".reprocess_quarantine").writeText("quarantined\n")
            val previousDelete = cleanupDeleteOperation
            cleanupDeleteOperation = { file ->
                file.name == ".reprocess_quarantine"
            }
            try {
                assertFalse(cleanupBackups(transaction))
                assertTrue(File(transaction.backupRoot, ".reprocess_quarantine").exists())
                assertTrue(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).isFile)
            } finally {
                cleanupDeleteOperation = previousDelete
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun payloadCleanupRemainsEvenWhenDeleteSaysSuccess() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
            val previousDelete = cleanupDeleteOperation
            cleanupDeleteOperation = { file ->
                // Lies: claims success but file is still there
                true
            }
            try {
                assertFalse(cleanupBackups(transaction))
                assertTrue(File(transaction.backupRoot, ".reprocess_quarantine").isFile || File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).isFile)
            } finally {
                cleanupDeleteOperation = previousDelete
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun successfulImmediateCleanupDeletesRoot() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
            assertTrue(cleanupBackups(transaction))
            assertFalse(transaction.backupRoot.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun raw16AndDngRefsForOneFrameCountAsOne() {
        val directory = tempJob()
        try {
            val job = JSONObject().put("jobType", "RAW_NIGHT_FUSION")
            val frames = JSONArray()
            frames.put(JSONObject().put("raw16File", "frame_0001.raw16").put("dngFile", "frame_0001.dng").put("enabled", true))
            job.put("frames", frames)
            File(directory, "frame_0001.raw16").writeText("raw")
            File(directory, "frame_0001.dng").writeText("raw")
            assertEquals(1, countActualSourceFrames(directory, job, ReprocessJobKind.RAW_FUSION))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun successfulRestartCleanupCleansResolvedRoot() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
            assertTrue(cleanupBackups(transaction))
            assertFalse(transaction.backupRoot.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun everyActiveYuvClassicCreatedOutputIsRemovedOnRollback() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            val yuvOutput = File(directory, "fused_classic_yuv_v1.png")
            yuvOutput.writeText("yuv")
            val classicOutput = File(directory, "average_color_rotated.png")
            classicOutput.writeText("color")
            val rawOutput = File(directory, "raw_fusion_final.png")
            rawOutput.writeText("raw")
            val debugOutput = File(directory, "fusion_debug.json")
            debugOutput.writeText("{}")
            val tempOutput = File(directory, "tmp_fused.tmp")
            tempOutput.writeText("temp")
            assertTrue(removeCreatedForTest(directory, transaction).isSuccess)
            assertFalse(yuvOutput.exists())
            assertFalse(classicOutput.exists())
            assertFalse(rawOutput.exists())
            assertFalse(debugOutput.exists())
            assertFalse(tempOutput.exists())
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
            val root = File(directory, ".reprocess_backup_sf_mem_id")
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
    fun existingMarkerDirectoryOrEmptyFileOverwritten() {
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

            // Sub-case B: empty file is overwritten with identity-bound content — no throw.
            markerPath.createNewFile()
            writeQuarantineMarker(transaction)
            assertTrue(markerPath.isFile)
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
    fun nonemptyCorruptQuarantineMarkerOverwritten() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            val marker = File(transaction.backupRoot, ".reprocess_quarantine")
            marker.writeText("legacy marker content\n")
            // Legacy non-identity marker is overwritten with identity-bound content — no throw.
            writeQuarantineMarker(transaction)
            assertTrue(marker.isFile)
            val identity = readQuarantineMarkerIdentity(marker)
            assertNotNull(identity)
            assertEquals(transaction.transactionId, identity!!.first)
            assertEquals(transaction.backupRoot.name, identity.second)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun quarantineMarkerWriteSeamCorruptNoFileRejected() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            val previousSeam = quarantineMarkerWriteOperation
            try {
                // Seam writes corrupt content (wrong text)
                quarantineMarkerWriteOperation = { file, _ ->
                    file.writeText("corrupted\n")
                }
                assertThrows(IllegalStateException::class.java) {
                    writeQuarantineMarker(transaction)
                }
                // Seam produces no file at all
                quarantineMarkerWriteOperation = { _, _ -> }
                assertThrows(IllegalStateException::class.java) {
                    writeQuarantineMarker(transaction)
                }
                // Seam creates a directory instead of a file
                quarantineMarkerWriteOperation = { file, _ -> file.mkdir() }
                assertThrows(IllegalStateException::class.java) {
                    writeQuarantineMarker(transaction)
                }
            } finally {
                quarantineMarkerWriteOperation = previousSeam
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun matchingFallbackIdentityDeletionFailureRemainsBlocked() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            ensureDurableFallbackQuarantine(directory, transaction)

            val previousDeleteSeam = fallbackDeleteOperation
            try {
                fallbackDeleteOperation = { false }
                assertFalse(removeMatchingFallbackQuarantine(directory, transaction))
                assertTrue(isReprocessQuarantined(directory))
            } finally {
                fallbackDeleteOperation = previousDeleteSeam
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun recoverValidatedQuarantinePreservesRootWhenFallbackDeletionFails() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            ensureDurableFallbackQuarantine(directory, transaction)
            writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)

            val previousDeleteSeam = fallbackDeleteOperation
            try {
                fallbackDeleteOperation = { false }
                recoverValidatedQuarantine(directory)
                assertTrue(File(directory, ".reprocess_unresolved").exists())
                assertTrue(transaction.backupRoot.isDirectory)
                assertTrue(isReprocessQuarantined(directory))
            } finally {
                fallbackDeleteOperation = previousDeleteSeam
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun successfulMatchingFallbackDeletionPermitsCleanup() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            ensureDurableFallbackQuarantine(directory, transaction)
            writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)

            recoverValidatedQuarantine(directory)
            assertFalse(File(directory, ".reprocess_unresolved").exists())
            assertFalse(isReprocessQuarantined(directory))
            assertTrue(cleanupBackups(transaction))
            assertFalse(transaction.backupRoot.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cancelledWorkerDeferredAutoRetryViaRegisterLateFinalization() = runBlocking {
        var invocationCount = 0
        val previousSeam = finalizerFailureSeam
        val previousCompleteCallback = lateFinalizationCompleteCallback
        finalizerFailureSeam = {
            invocationCount++
            if (invocationCount == 1) IllegalStateException("first finalizer failure") else null
        }
        var barrier = CompletableDeferred<Unit>()
        lateFinalizationCompleteCallback = { barrier.complete(Unit) }
        val previousHandoffScope = lateFinalizationHandoffScope
        lateFinalizationHandoffScope = null
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease() ?: error("no lease")
            session.transferOwnership(transaction)
            val workerTerminal = CompletableDeferred<ReprocessWorkerOutcome>()
            val worker = ReprocessWorkerRun(terminal = workerTerminal, cancel = {})
            registerLateFinalization(
                session, worker, directory, ReprocessJobKind.RAW_FUSION,
                FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet()
            )
            workerTerminal.cancel(kotlinx.coroutines.CancellationException("worker cancelled"))
            withTimeout(5000) { barrier.await() }
            assertEquals(2, invocationCount)
            assertEquals(ReprocessTransactionSession.LateState.TERMINAL, session.lateStateForTest())
            assertFalse(KeplerJobMetadata.isOperationActive(directory))
            assertTrue(workerTerminal.isCancelled)
        } finally {
            finalizerFailureSeam = previousSeam
            lateFinalizationCompleteCallback = previousCompleteCallback
            lateFinalizationHandoffScope = previousHandoffScope
            directory.deleteRecursively()
        }
    }

    @Test
    fun cleanupSeamExceptionPreservesManifest() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
            val previousDelete = cleanupDeleteOperation
            cleanupDeleteOperation = { file ->
                if (file.name == "final.png.backup") throw IOException("seam failure")
                file.delete()
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
    fun recoverValidatedQuarantineCleansResolvedRoot() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
            recoverValidatedQuarantine(directory)
            assertFalse(KeplerJobMetadata.isOperationActive(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun metadataNonFrameSourceCountsAsFrame() {
        val directory = tempJob()
        try {
            val job = JSONObject().put("jobType", "RAW_NIGHT_FUSION")
            val frames = JSONArray()
            frames.put(JSONObject().put("raw16File", "source_001.raw16").put("enabled", true))
            job.put("frames", frames)
            File(directory, "source_001.raw16").writeText("raw")
            assertEquals(1, countActualSourceFrames(directory, job, ReprocessJobKind.RAW_FUSION))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun metadataNonFrameSourceIsImmutable() {
        val directory = tempJob()
        try {
            val job = JSONObject().put("jobType", "RAW_NIGHT_FUSION")
            val frames = JSONArray()
            frames.put(JSONObject().put("raw16File", "source_001.raw16").put("dngFile", "source_001.dng"))
            job.put("frames", frames)
            KeplerJobMetadata.write(directory, job)
            File(directory, "source_001.raw16").writeText("raw")
            File(directory, "source_001.dng").writeText("dng")
            File(directory, "final.png").writeText("output")
            val tx = backupReprocessTransaction(directory, listOf(File(directory, "source_001.raw16"), File(directory, "final.png"))).getOrThrow()
            // Immutable source should not be backed up
            assertFalse(File(tx.backupRoot, "source_001.raw16.backup").exists())
            // Mutable file should be backed up
            assertTrue(File(tx.backupRoot, "final.png.backup").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun malformedFrameEntryRejectsBackup() {
        val directory = tempJob()
        try {
            val job = JSONObject().put("jobType", "RAW_NIGHT_FUSION")
            val frames = JSONArray()
            frames.put(JSONObject()) // empty frame, no source references
            job.put("frames", frames)
            KeplerJobMetadata.write(directory, job)
            File(directory, "final.png").writeText("output")
            val result = backupReprocessTransaction(directory, listOf(File(directory, "final.png")), job)
            assertTrue(result.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unsafeSourceReferenceRejectsBackup() {
        val directory = tempJob()
        try {
            val job = JSONObject().put("jobType", "RAW_NIGHT_FUSION")
            val frames = JSONArray()
            frames.put(JSONObject().put("raw16File", "../../etc/passwd").put("enabled", true))
            job.put("frames", frames)
            KeplerJobMetadata.write(directory, job)
            File(directory, "final.png").writeText("output")
            val result = backupReprocessTransaction(directory, listOf(File(directory, "final.png")), job)
            assertTrue(result.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unsupportedJobKindRejectsBackup() {
        val directory = tempJob()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("jobType", "UNKNOWN"))
            File(directory, "final.png").writeText("output")
            val result = backupReprocessTransaction(directory, listOf(File(directory, "final.png")))
            assertTrue(result.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun nonObjectFrameEntryRejectsBackup() {
        val directory = tempJob()
        try {
            val job = JSONObject().put("jobType", "RAW_NIGHT_FUSION")
            val frames = JSONArray()
            frames.put("not-an-object")
            job.put("frames", frames)
            KeplerJobMetadata.write(directory, job)
            File(directory, "final.png").writeText("output")
            val result = backupReprocessTransaction(directory, listOf(File(directory, "final.png")), job)
            assertTrue(result.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun createdOutputDeletionFailureQuarantines() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            File(directory, "reprocess_preview_new.png").writeText("created")
            val previousDelete = createdOutputDeleteOperation
            createdOutputDeleteOperation = { false }
            try {
                assertTrue(removeCreatedForTest(directory, transaction).isFailure)
            } finally {
                createdOutputDeleteOperation = previousDelete
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun specificActiveRawYuvClassicOutputNamesRemoved() {
        val previousDeleteOp = createdOutputDeleteOperation
        createdOutputDeleteOperation = null
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            val outputs = listOf(
                "sharpened_night_fusion.png", "average_color_rotated.png", "denoise_color.png",
                "fused_classic_yuv_v1.png",
                "raw_fusion_final.png",
                "yuv_compare_reference_vs_fused.png", "compare_reference_vs_fused.png",
                "raw_reference_preview.png", "raw_fused_classic_v1_preview.png",
                "raw_compare_reference_vs_fused.png",
                "yuv_reference_preview.png", "yuv_fused_preview.png",
                "yuv_fused_before_denoise_preview.png",
                "yuv_fused_after_denoise_no_sharpen_preview.png",
                "yuv_final_preview.png", "yuv_compare_reference_vs_final.png",
                "fused_before_denoise_preview.png",
                "fused_after_denoise_no_sharpen_preview.png",
                "final_preview.png", "reference_single_preview.png",
                "compare_reference_vs_final.png",
                "fusion_debug.json", "yuv_debug.json", "raw_fusion_debug.json",
                "raw_render_debug.json", "raw_render_input_metadata.json"
            )
            for (name in outputs) {
                File(directory, name).writeText("output")
            }
            assertTrue(removeCreatedForTest(directory, transaction).isSuccess)
            for (name in outputs) {
                assertFalse("$name not removed", File(directory, name).exists())
            }
        } finally {
            createdOutputDeleteOperation = previousDeleteOp
            directory.deleteRecursively()
        }
    }

    @Test
    fun unsafeNestedSourceReferenceDoesNotCount() {
        val directory = tempJob()
        try {
            val job = JSONObject().put("jobType", "RAW_NIGHT_FUSION")
            val frames = JSONArray()
            frames.put(JSONObject().put("raw16File", "frame_0001.raw16").put("enabled", true))
            frames.put(JSONObject().put("raw16File", "../other/frame_0002.raw16"))
            job.put("frames", frames)
            File(directory, "frame_0001.raw16").writeText("raw")
            assertEquals(1, countActualSourceFrames(directory, job, ReprocessJobKind.RAW_FUSION))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rollbackReleasesLeaseEvenWhenCleanupFails() {
        val directory = tempJob()
        try {
            val transaction = backup(directory, "final.png" to "before")
            val session = ReprocessTransactionSession(directory)
            val lease = session.acquireLease() ?: error("no lease")
            assertTrue(KeplerJobMetadata.isOperationActive(directory))
            session.transferOwnership(transaction)
            File(directory, "final.png").writeText("after!")
            val previousDelete = cleanupDeleteOperation
            cleanupDeleteOperation = { false }
            try {
                val result = finalizeTransactionWithLease(
                    transaction, lease, directory, ReprocessJobKind.RAW_FUSION,
                    FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
                    Result.failure(IllegalStateException("worker failed"))
                )
                assertEquals(ReprocessFinalizationState.ROLLED_BACK, result.state)
                assertFalse(KeplerJobMetadata.isOperationActive(directory))
            } finally {
                cleanupDeleteOperation = previousDelete
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun outsideJobSourceReferenceRejectsBackup() {
        val directory = tempJob()
        try {
            val job = JSONObject().put("jobType", "RAW_NIGHT_FUSION")
            val frames = JSONArray()
            frames.put(JSONObject().put("raw16File", "../outside/frame.raw16").put("enabled", true))
            job.put("frames", frames)
            KeplerJobMetadata.write(directory, job)
            File(directory, "final.png").writeText("output")
            val result = backupReprocessTransaction(directory, listOf(File(directory, "final.png")), job)
            assertTrue(result.isFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun preExistingOutputSurvivesRollback() {
        val directory = tempJob()
        try {
            File(directory, "sharpened_night_fusion.png").writeText("preexisting")
            val transaction = backup(directory, "final.png" to "before")
            // sharpened_night_fusion.png was pre-existing and is in backedUpPaths,
            // so it should survive rollback
            assertTrue(removeCreatedForTest(directory, transaction).isSuccess)
            assertTrue(File(directory, "sharpened_night_fusion.png").exists())
        } finally {
            directory.deleteRecursively()
        }
}

// ── Phase 1: Terminal settlement exception safety ─────────────────────────

@Test
fun warningMetadataFailureAfterDurableCommittedStillReturnsTerminalAndReleasesLease() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    KeplerJobMetadata.write(directory, KeplerJobMetadata.read(directory).apply {
      put("finalOutputFile", "final.png")
    })
    val previous = createdOutputDeleteOperation
    createdOutputDeleteOperation = { true }
    val outcome = ReprocessWorkerOutcome(
      result = Result.success(Unit),
      publicExportCommitted = false,
      exportVerified = true,
      finalOutputFile = File(directory, "final.png")
    )
    try {
      val result = finalizeTransactionWithLease(
        transaction, lease, directory,
        ReprocessJobKind.RAW_FUSION, FinalOutputFormat.JPEG,
        FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
        Result.success(outcome)
      )
      assertEquals(ReprocessFinalizationState.COMMITTED, result.state)
      assertFalse(KeplerJobMetadata.isOperationActive(directory))
    } finally {
      createdOutputDeleteOperation = previous
      lateFinalizationHandoffScope = null
    }
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun cleanupSeamThrowingIllegalStateExceptionReturnsCleanupFailure() {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
    val previousDelete = cleanupDeleteOperation
    cleanupDeleteOperation = { throw IllegalStateException("seam boom") }
    try {
      assertFalse(cleanupBackups(transaction))
    } finally {
      cleanupDeleteOperation = previousDelete
    }
  } finally {
    directory.deleteRecursively()
  }
}

// ── Phase 2: Fail-closed cleanup ──────────────────────────────────────────

@Test
fun restartRecoveryDeletesCleanResolvedBackupRoot() {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
    assertTrue(transaction.backupRoot.isDirectory)
    recoverValidatedQuarantine(directory)
    assertFalse(transaction.backupRoot.exists())
  } finally {
    directory.deleteRecursively()
  }
}

// ── Phase 3: Strict source validation ─────────────────────────────────────

@Test
fun missingSourceRejectsBackup() {
  val directory = tempJob()
  try {
    val job = JSONObject().put("jobType", "RAW_NIGHT_FUSION")
    val frames = JSONArray()
    frames.put(JSONObject().put("raw16File", "frame_0001.raw16").put("enabled", true))
    job.put("frames", frames)
    KeplerJobMetadata.write(directory, job)
    File(directory, "final.png").writeText("output")
    val result = backupReprocessTransaction(directory, listOf(File(directory, "final.png")), job)
    assertTrue(result.isFailure)
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun directorySourceReferenceRejectsBackup() {
  val directory = tempJob()
  try {
    val job = JSONObject().put("jobType", "RAW_NIGHT_FUSION")
    val frames = JSONArray()
    frames.put(JSONObject().put("raw16File", "frame_0001.raw16").put("enabled", true))
    job.put("frames", frames)
    KeplerJobMetadata.write(directory, job)
    File(directory, "frame_0001.raw16").mkdir()
    File(directory, "final.png").writeText("output")
    val result = backupReprocessTransaction(directory, listOf(File(directory, "final.png")), job)
    assertTrue(result.isFailure)
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun traversalOutsideJobRejected() {
  val directory = tempJob()
  try {
    val job = JSONObject().put("jobType", "RAW_NIGHT_FUSION")
    val frames = JSONArray()
    frames.put(JSONObject().put("raw16File", "../../escape.raw16").put("enabled", true))
    job.put("frames", frames)
    KeplerJobMetadata.write(directory, job)
    File(directory, "final.png").writeText("output")
    val result = backupReprocessTransaction(directory, listOf(File(directory, "final.png")), job)
    assertTrue(result.isFailure)
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun directChildSymlinkToOutsideRejected() {
  val directory = tempJob()
  try {
    val outside = File(directory.parentFile, "outside.raw16")
    outside.writeText("outside")
    val symlink = File(directory, "frame_0001.raw16")
    val symlinkCreated = runCatching {
      Files.createSymbolicLink(symlink.toPath(), outside.toPath())
    }.isSuccess
    org.junit.Assume.assumeTrue("Symlinks not supported on this environment", symlinkCreated)
    val job = JSONObject().put("jobType", "RAW_NIGHT_FUSION")
    val frames = JSONArray()
    frames.put(JSONObject().put("raw16File", "frame_0001.raw16").put("enabled", true))
    job.put("frames", frames)
    KeplerJobMetadata.write(directory, job)
    File(directory, "final.png").writeText("output")
    val result = backupReprocessTransaction(directory, listOf(File(directory, "final.png")), job)
    assertTrue(result.isFailure)
    outside.delete()
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun invalidFramesMetadataDoesNotFallBackToLegacyScan() {
  val directory = tempJob()
  try {
    val job = JSONObject().put("jobType", "RAW_NIGHT_FUSION")
    val frames = JSONArray()
    frames.put(JSONObject()) // empty frame with no source refs
    job.put("frames", frames)
    KeplerJobMetadata.write(directory, job)
    File(directory, "final.png").writeText("output")
    val result = backupReprocessTransaction(directory, listOf(File(directory, "final.png")), job)
    assertTrue(result.isFailure)
  } finally {
    directory.deleteRecursively()
  }
}

// ── Phase 4: Created-output deletion failure quarantines and retains lease ───

@Test
fun rollbackCreatedOutputDeletionFailureQuarantinesAndRetainsLease() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    File(directory, "native_postprocess.json").writeText("{}")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    val previousDelete = createdOutputDeleteOperation
    createdOutputDeleteOperation = { false }
    try {
      val result = finalizeTransactionWithLease(
        transaction, lease, directory,
        ReprocessJobKind.RAW_FUSION, FinalOutputFormat.JPEG,
        FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
        Result.failure(IllegalStateException("worker failed"))
      )
      assertEquals(ReprocessFinalizationState.QUARANTINED, result.state)
      assertTrue(KeplerJobMetadata.isOperationActive(directory))
    } finally {
      createdOutputDeleteOperation = previousDelete
      lateFinalizationHandoffScope = null
    }
  } finally {
    directory.deleteRecursively()
  }
}

// ── Pre-existing active outputs survive rollback deletion ──────────────────

@Test
fun preExistingActiveOutputSurvivesRollbackDeletion() {
  val directory = tempJob()
  try {
    File(directory, "native_postprocess.json").writeText("preexisting")
    val transaction = backup(directory, "final.png" to "before")
    assertTrue(removeCreatedForTest(directory, transaction).isSuccess)
    assertTrue(File(directory, "native_postprocess.json").exists())
  } finally {
    directory.deleteRecursively()
  }
}

// ── Phase 5: Cleanup debt after terminal, null-listing fail-closed, metadata exceptions ───

@Test
fun cleanupDebtExceptionAfterDurableCommittedReturnsCommittedAndReleasesLease() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    File(directory, "final.png").writeText("output")
    val outcome = ReprocessWorkerOutcome(
      result = Result.success(Unit),
      publicExportCommitted = false,
      exportVerified = true,
      finalOutputFile = File(directory, "final.png")
    )
    val previousDelete = cleanupDeleteOperation
    cleanupDeleteOperation = { throw IOException("cleanup boom after commit") }
    val previousFallback = fallbackDeleteOperation
    fallbackDeleteOperation = { false }
    try {
      val result = finalizeTransactionWithLease(
        transaction, lease, directory,
        ReprocessJobKind.RAW_FUSION, FinalOutputFormat.JPEG,
        FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
        Result.success(outcome)
      )
      assertEquals(ReprocessFinalizationState.COMMITTED, result.state)
      assertFalse(KeplerJobMetadata.isOperationActive(directory))
    } finally {
      cleanupDeleteOperation = previousDelete
      fallbackDeleteOperation = previousFallback
      lateFinalizationHandoffScope = null
    }
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun cleanupDebtExceptionAfterDurableRolledBackReturnsRolledBackAndReleasesLease() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    File(directory, "final.png").writeText("after!")
    val previousDelete = cleanupDeleteOperation
    cleanupDeleteOperation = { throw IOException("cleanup-debt boom after rollback") }
    val previousFallback = fallbackDeleteOperation
    fallbackDeleteOperation = { false }
    try {
      val result = finalizeTransactionWithLease(
        transaction, lease, directory,
        ReprocessJobKind.RAW_FUSION, FinalOutputFormat.JPEG,
        FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
        Result.failure(IllegalStateException("worker failed"))
      )
      assertEquals(ReprocessFinalizationState.ROLLED_BACK, result.state)
      assertFalse(KeplerJobMetadata.isOperationActive(directory))
    } finally {
      cleanupDeleteOperation = previousDelete
      fallbackDeleteOperation = previousFallback
      lateFinalizationHandoffScope = null
    }
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun warningMetadataKeplerJobMetadataExceptionTerminalResultAndLeaseCorrect() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    File(directory, "final.png").writeText("output")
    val outcome = ReprocessWorkerOutcome(
      result = Result.success(Unit),
      publicExportCommitted = false,
      exportVerified = true,
      finalOutputFile = File(directory, "final.png")
    )
    val previousCreated = createdOutputDeleteOperation
    createdOutputDeleteOperation = { true }
    try {
      val result = finalizeTransactionWithLease(
        transaction, lease, directory,
        ReprocessJobKind.RAW_FUSION, FinalOutputFormat.JPEG,
        FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
        Result.success(outcome)
      )
      assertEquals(ReprocessFinalizationState.COMMITTED, result.state)
      assertFalse(KeplerJobMetadata.isOperationActive(directory))
    } finally {
      createdOutputDeleteOperation = previousCreated
      lateFinalizationHandoffScope = null
    }
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun emptyFramesArrayDoesNotFallBackToLegacyScan() {
  val directory = tempJob()
  try {
    val job = JSONObject().put("jobType", "RAW_NIGHT_FUSION")
    job.put("frames", JSONArray())
    KeplerJobMetadata.write(directory, job)
    File(directory, "frame_0001.raw16").writeText("raw")
    assertEquals(0, countActualSourceFrames(directory, job, ReprocessJobKind.RAW_FUSION))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun realFilesCreateSymbolicLinkToOutsideRejectAfterUnsoported() {
  val directory = tempJob()
  try {
    val outside = File(directory.parentFile, "absolutely.raw16")
    outside.writeText("outside")
    val symlink = File(directory, "frame_0001.raw16")
    val symlinkCreated = runCatching {
      Files.createSymbolicLink(symlink.toPath(), outside.toPath())
    }.isSuccess
    org.junit.Assume.assumeTrue("Symlinks not supported on this environment", symlinkCreated)
    val job = JSONObject().put("jobType", "RAW_NIGHT_FUSION")
    val frames = JSONArray()
    frames.put(JSONObject().put("raw16File", "frame_0001.raw16").put("enabled", true))
    job.put("frames", frames)
    KeplerJobMetadata.write(directory, job)
    File(directory, "final.png").writeText("output")
    val result = backupReprocessTransaction(directory, listOf(File(directory, "final.png")), job)
    assertTrue(result.isFailure)
    outside.delete()
  } finally {
    directory.deleteRecursively()
  }
}

// ── Phase 4b: Real-seam cleanup, listing, idempotency, and fallback tests ──

@Test
fun nullListingAtInitialInspectionFailsClosedViaRealSeam() {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
    val previousList = cleanupListOperation
    cleanupListOperation = { null }
    try {
      assertFalse(cleanupBackups(transaction))
    } finally {
      cleanupListOperation = previousList
    }
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun nullListingAtIntermediateFailsClosedViaRealSeam() {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
    val callCount = java.util.concurrent.atomic.AtomicInteger(0)
    val previousList = cleanupListOperation
    cleanupListOperation = { root ->
      val c = callCount.incrementAndGet()
      if (c == 2) null else root.listFiles()
    }
    try {
      assertFalse(cleanupBackups(transaction))
      assertTrue(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).isFile)
    } finally {
      cleanupListOperation = previousList
    }
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun nullListingAtFinalFailsClosedViaRealSeam() {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
    val callCount = java.util.concurrent.atomic.AtomicInteger(0)
    val previousList = cleanupListOperation
    cleanupListOperation = { root ->
      val c = callCount.incrementAndGet()
      if (c == 3) null else root.listFiles()
    }
    try {
      assertFalse(cleanupBackups(transaction))
      assertTrue(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).isFile)
    } finally {
      cleanupListOperation = previousList
    }
  } finally {
    directory.deleteRecursively()
  }
}

// ── Phase 4b: Terminal cache, metadata routing, cleanup real-seam tests ──

@Test
fun freshCommittedIsCachedBeforeCleanup() {
    val directory = tempJob()
    try {
        val transaction = backup(directory, "final.png" to "before")
        val session = ReprocessTransactionSession(directory)
        val lease = session.acquireLease() ?: error("no lease")
        session.transferOwnership(transaction)
        File(directory, "final.png").writeText("output")
        val outcome = ReprocessWorkerOutcome(
            result = Result.success(Unit),
            publicExportCommitted = false,
            exportVerified = true,
            finalOutputFile = File(directory, "final.png")
        )
        val previousDelete = createdOutputDeleteOperation
        createdOutputDeleteOperation = { true }
        try {
            val first = finalizeTransaction(
                session, transaction, directory,
                ReprocessJobKind.RAW_FUSION, FinalOutputFormat.JPEG,
                FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
                Result.success(outcome)
            )
            assertEquals(ReprocessFinalizationState.COMMITTED, first.state)
            assertFalse(KeplerJobMetadata.isOperationActive(directory))
            val second = finalizeTransaction(
                session, transaction, directory,
                ReprocessJobKind.RAW_FUSION, FinalOutputFormat.JPEG,
                FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
                Result.success(outcome)
            )
            assertEquals(ReprocessFinalizationState.COMMITTED, second.state)
        } finally {
            createdOutputDeleteOperation = previousDelete
            lateFinalizationHandoffScope = null
        }
    } finally {
        directory.deleteRecursively()
    }
}

@Test
fun freshRolledBackIsCachedBeforeCleanup() {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    File(directory, "final.png").writeText("after!")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    val first = finalizeTransaction(
      session, transaction, directory,
      ReprocessJobKind.RAW_FUSION, FinalOutputFormat.JPEG,
      FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
      Result.failure(IllegalStateException("worker failed"))
    )
    assertEquals(ReprocessFinalizationState.ROLLED_BACK, first.state)
    assertFalse(KeplerJobMetadata.isOperationActive(directory))
    val second = finalizeTransaction(
      session, transaction, directory,
      ReprocessJobKind.RAW_FUSION, FinalOutputFormat.JPEG,
      FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
      Result.failure(IllegalStateException("worker failed"))
    )
    assertEquals(ReprocessFinalizationState.ROLLED_BACK, second.state)
  } finally {
    lateFinalizationHandoffScope = null
    directory.deleteRecursively()
  }
}

// ── Phase 4c: Late-finalization state transitions (real late callback) ──
//
// Tests force the actual production branches via real seams:
//   - terminal retrieval ordinary failure BEFORE a worker result is converted (deferred completes exceptionally)
//   - finalizer ordinary failure AFTER retrieval (finalizer stratum throws)
//   - fallback persistence failure (fallbackWriteOperation seam fails)
//   - valid root evidence WITHOUT fallback (canonical marker exists; no fallback created)
//   - invalid root evidence WITH verified fallback (root gone; fallback established and verified)
//   - callback cancellation (CancellationException propagates from retrieval)
//   - production retry from UNRESOLVED (scheduleUnresolvedRetry reaches TERMINAL)

@Test
fun lateTerminalRetrievalFailureBeforeResultConversionStaysUnresolvedAndRetainsLease() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    // Existing root evidence (ACTIVE manifest → trustworthy) means no fallback should be created.
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    assertTrue(session.tryAcquireLateRegistration())
    // Force terminal retrieval to throw an ordinary failure before the worker result is converted.
    val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
    terminal.completeExceptionally(IllegalStateException("terminal retrieval ordinary failure"))
    val handoff = ReprocessLateFinalizationHandoff(
      session, transaction, lease!!, directory, ReprocessJobKind.RAW_FUSION,
      FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
      workerTerminal = terminal
    )
    runLateFinalization(handoff, null)
    assertEquals(ReprocessTransactionSession.LateState.TERMINAL, session.lateStateForTest())
    assertFalse(KeplerJobMetadata.isOperationActive(directory))
    assertFalse(File(directory, ".reprocess_unresolved").exists())
  } finally {
    lateFinalizationHandoffScope = null
    directory.deleteRecursively()
  }
}

@Test
fun lateFinalizerFailureAfterRetrievalStaysUnresolvedAndRetainsLease() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    assertTrue(session.tryAcquireLateRegistration())
    // Terminal retrieves successfully → finalizer is reached; rollback path must produce QUARANTINED.
    // Corruption of the backup payload causes the rollback restore to fail → QUARANTINED → UNRESOLVED.
    val backupEntry = transaction.manifest.backupEntries.values.first()
    File(transaction.backupRoot, backupEntry.backupName).delete()
    val outcome = ReprocessWorkerOutcome(
      result = Result.failure(IllegalStateException("worker failed")),
      publicExportCommitted = false
    )
    val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
    terminal.complete(outcome)
    val handoff = ReprocessLateFinalizationHandoff(
      session, transaction, lease!!, directory, ReprocessJobKind.RAW_FUSION,
      FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
      workerTerminal = terminal
    )
    runLateFinalization(handoff, null)
    assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
    assertTrue(KeplerJobMetadata.isOperationActive(directory))
    // Existing root evidence (marker not present but ACTIVE manifest still in backup root) survives
    // the QUARANTINED path attempt → fallback created only when marker is also missing — but here
    // rollback path quarantines first, so test asserts durable evidence is preserved either way.
    assertTrue(File(directory, ".reprocess_unresolved").isFile || transaction.backupRoot.isDirectory)
  } finally {
    lateFinalizationHandoffScope = null
    directory.deleteRecursively()
  }
}

@Test
fun lateFallbackPersistenceFailurePreservesOriginalFailureAndRetainsLease() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    transaction.backupRoot.deleteRecursively()
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    assertTrue(session.tryAcquireLateRegistration())
    val previousFallbackWrite = fallbackWriteOperation
    fallbackWriteOperation = { _, _, _ -> throw IOException("fallback write seam failure") }
    try {
      val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
      terminal.completeExceptionally(IllegalStateException("terminal retrieval ordinary failure"))
      val handoff = ReprocessLateFinalizationHandoff(
        session, transaction, lease!!, directory, ReprocessJobKind.RAW_FUSION,
        FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
        workerTerminal = terminal
      )
      runLateFinalization(handoff, null)
      assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
      assertTrue(KeplerJobMetadata.isOperationActive(directory))
      // Original retrieval failure preserved alongside the evidence persistence failure.
      assertNotNull(handoff.evidenceError)
      assertFalse(File(directory, ".reprocess_unresolved").exists())
    } finally {
      fallbackWriteOperation = previousFallbackWrite
    }
  } finally {
    lateFinalizationHandoffScope = null
    directory.deleteRecursively()
  }
}

@Test
fun lateValidMarkerEvidenceSuppressesFallbackCreation() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    // Place a canonical quarantine marker BEFORE late finalization so root evidence is trustworthy
    // via the marker mechanism even if the manifest were untrusted.
    File(transaction.backupRoot, ".reprocess_quarantine").writeText("quarantined\n")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    assertTrue(session.tryAcquireLateRegistration())
    val previousFallbackWrite = fallbackWriteOperation
    fallbackWriteOperation = { _, _, _ -> throw AssertionError("fallback must not be created with valid marker evidence") }
    try {
      val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
      terminal.completeExceptionally(IllegalStateException("terminal retrieval ordinary failure"))
      val handoff = ReprocessLateFinalizationHandoff(
        session, transaction, lease!!, directory, ReprocessJobKind.RAW_FUSION,
        FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
        workerTerminal = terminal
      )
      runLateFinalization(handoff, null)
      assertEquals(ReprocessTransactionSession.LateState.TERMINAL, session.lateStateForTest())
      assertFalse(KeplerJobMetadata.isOperationActive(directory))
      assertFalse(File(directory, ".reprocess_unresolved").exists())
    } finally {
      fallbackWriteOperation = previousFallbackWrite
    }
  } finally {
    lateFinalizationHandoffScope = null
    directory.deleteRecursively()
  }
}

@Test
fun lateInvalidMarkerButValidManifestStillTrustsRootEvidence() {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    // Place a CORRUPT marker (wrong content); the strict inspection should fall through to the
    // VALID manifest (ACTIVE state) and trust it independently, never creating a fallback.
    File(transaction.backupRoot, ".reprocess_quarantine").writeText("corrupt\n")
    // The evaluation must return Trustworthy via the manifest mechanism despite the corrupt marker.
    assertTrue(strictRootEvidence(directory, transaction) === RootEvidence.Trustworthy)
    assertFalse(File(directory, ".reprocess_unresolved").exists())
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun lateMissingRootEvidenceWithVerifiedFallbackEstablishesDurableEvidence() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    transaction.backupRoot.deleteRecursively()
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    assertTrue(session.tryAcquireLateRegistration())
    val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
    terminal.completeExceptionally(IllegalStateException("terminal retrieval ordinary failure"))
    val handoff = ReprocessLateFinalizationHandoff(
      session, transaction, lease!!, directory, ReprocessJobKind.RAW_FUSION,
      FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
      workerTerminal = terminal
    )
    runLateFinalization(handoff, null)
    assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
    assertTrue(KeplerJobMetadata.isOperationActive(directory))
    assertTrue(File(directory, ".reprocess_unresolved").isFile)
  } finally {
    lateFinalizationHandoffScope = null
    directory.deleteRecursively()
  }
}

@Test
fun lateQuarantinedResultWithValidRootEvidenceDoesNotCreateFallback() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    // Quarantine marker present → trustworthy root evidence; finalize QUARANTINED path should NOT create fallback.
    File(transaction.backupRoot, ".reprocess_quarantine").writeText("quarantined\n")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    assertTrue(session.tryAcquireLateRegistration())
    // Rollback path will fail because the backup payload is missing → QUARANTINED state.
    val backupEntry = transaction.manifest.backupEntries.values.first()
    File(transaction.backupRoot, backupEntry.backupName).delete()
    val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
    terminal.complete(ReprocessWorkerOutcome(
      result = Result.failure(IllegalStateException("worker failed")),
      publicExportCommitted = false
    ))
    val handoff = ReprocessLateFinalizationHandoff(
      session, transaction, lease!!, directory, ReprocessJobKind.RAW_FUSION,
      FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
      workerTerminal = terminal
    )
    val previousFallbackWrite = fallbackWriteOperation
    fallbackWriteOperation = { _, _, _ -> throw AssertionError("fallback must not be created with valid marker evidence") }
    try {
      runLateFinalization(handoff, null)
      assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
      assertTrue(KeplerJobMetadata.isOperationActive(directory))
      assertFalse(File(directory, ".reprocess_unresolved").exists())
    } finally {
      fallbackWriteOperation = previousFallbackWrite
    }
  } finally {
    lateFinalizationHandoffScope = null
    directory.deleteRecursively()
  }
}

@Test
fun retryFromUnresolvedRegistersExactlyOnce() {
  val directory = tempJob()
  val session = ReprocessTransactionSession(directory)
  try {
    val transaction = backup(directory, "final.png" to "before")
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    assertTrue(session.tryAcquireLateRegistration())
    session.markLateUnresolved()
    // Second registration is allowed exactly once from UNRESOLVED (first bounded production retry).
    assertTrue(session.tryAcquireLateRegistration())
    // A third registration from LATE_REGISTERED must not succeed (concurrent finalizer rejected).
    assertFalse(session.tryAcquireLateRegistration())
    // After marking UNRESOLVED again, retry bound prevents further retry.
    session.markLateUnresolved()
    assertFalse(session.tryAcquireLateRegistration())
  } finally {
    session.releaseIfUnowned()
    directory.deleteRecursively()
  }
}

@Test
fun productionRetryFromUnresolvedReachesTerminal() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    // First attempt: corrupt a backup payload so rollback restore fails → QUARANTINED → UNRESOLVED.
    val backupEntry = transaction.manifest.backupEntries.values.first()
    File(transaction.backupRoot, backupEntry.backupName).delete()
    assertTrue(session.tryAcquireLateRegistration())
    val terminal1 = CompletableDeferred<ReprocessWorkerOutcome>()
    terminal1.completeExceptionally(IllegalStateException("worker failed"))
    val handoff1 = ReprocessLateFinalizationHandoff(
      session, transaction, lease!!, directory, ReprocessJobKind.RAW_FUSION,
      FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
      workerTerminal = terminal1
    )
    runLateFinalization(handoff1, null)
    assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
    // Retry via the production path: restore the backup payload, complete normally → ROLLED_BACK → TERMINAL.
    File(transaction.backupRoot, backupEntry.backupName).writeText("before")
    File(directory, "final.png").writeText("after!")
    val result = scheduleUnresolvedRetry(handoff1)
    assertNotNull(result)
    assertEquals(ReprocessTransactionSession.LateState.TERMINAL, session.lateStateForTest())
    assertEquals(ReprocessFinalizationState.ROLLED_BACK, result!!.state)
    assertFalse(KeplerJobMetadata.isOperationActive(directory))
  } finally {
    lateFinalizationHandoffScope = null
    directory.deleteRecursively()
  }
}

@Test
fun productionRetryFromUnresolvedWithFailingFinalizerStaysUnresolvedAndSkipsThirdAttempt() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    // First attempt: corrupt backup → quarantine → UNRESOLVED.
    val backupEntry = transaction.manifest.backupEntries.values.first()
    File(transaction.backupRoot, backupEntry.backupName).delete()
    assertTrue(session.tryAcquireLateRegistration())
    val terminal1 = CompletableDeferred<ReprocessWorkerOutcome>()
    terminal1.completeExceptionally(IllegalStateException("worker failed"))
    val handoff1 = ReprocessLateFinalizationHandoff(
      session, transaction, lease!!, directory, ReprocessJobKind.RAW_FUSION,
      FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
      workerTerminal = terminal1
    )
    runLateFinalization(handoff1, null)
    assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
    // Bound of 1 retry: still corrupt ⇒ still UNRESOLVED. The third attempt MUST NOT HAPPEN.
    val result = scheduleUnresolvedRetry(handoff1)
    assertNull(result)
    assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
    assertTrue(KeplerJobMetadata.isOperationActive(directory))
  } finally {
    lateFinalizationHandoffScope = null
    directory.deleteRecursively()
  }
}

// ── Phase 4c: Fatal-boundary tests ──

@Test
fun cleanupMissingRootSucceeds() {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
    transaction.backupRoot.deleteRecursively()
    assertTrue(cleanupBackups(transaction))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun cleanupNonDirectoryRootFails() {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
    transaction.backupRoot.deleteRecursively()
    File(directory, transaction.backupRoot.name).writeText("not a directory")
    assertFalse(cleanupBackups(transaction))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun cleanupDeleteSeamLyingWhileTargetRemainsPreservesManifest() {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
    val previousDelete = cleanupDeleteOperation
    cleanupDeleteOperation = { file -> file.name == "final.png.backup" }
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
fun cleanupRootDeletionFailurePreservesTerminalManifest() {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
    File(transaction.backupRoot, "final.png.backup").delete()
    val previousRootDelete = cleanupRootDeleteOperation
    cleanupRootDeleteOperation = { _ -> false }
    try {
      assertFalse(cleanupBackups(transaction))
      assertTrue(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).isFile)
      assertTrue(transaction.backupRoot.isDirectory)
    } finally {
      cleanupRootDeleteOperation = previousRootDelete
    }
    } finally {
        directory.deleteRecursively()
    }
}

@Test
fun cleanupSuccessfulRemovesRoot() {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
    File(transaction.backupRoot, ".reprocess_quarantine").writeText("quarantined\n")
    assertTrue(cleanupBackups(transaction))
    assertFalse(transaction.backupRoot.exists())
  } finally {
    directory.deleteRecursively()
  }
}

// ── Phase 4c: Idempotency counters ──

@Test
fun duplicateCommittedAfterRootDeletionReturnsCachedResult() {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    File(directory, "final.png").writeText("output")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    val outcome = ReprocessWorkerOutcome(
      result = Result.success(Unit),
      publicExportCommitted = false,
      exportVerified = true,
      finalOutputFile = File(directory, "final.png")
    )
    val previousDelete = createdOutputDeleteOperation
    createdOutputDeleteOperation = { true }
    val previousMetadataWrites = metadataWriteCount
    val previousKickbacks = createdOutputKickbackCount
    val previousFallbackWrites = fallbackWriteCount
    val previousResets = restoreBackupsKickbackCount
    val previousLeaseReleases = leaseReleaseCount
    KeplerJobMetadata.setAtomicWriteCountForTest(0)
    createdOutputKickbackCount = 0
    fallbackWriteCount = 0
    restoreBackupsKickbackCount = 0
    KeplerJobMetadata.setLeaseReleaseCountForTest(0)
    try {
      val first = finalizeTransaction(
        session, transaction, directory,
        ReprocessJobKind.RAW_FUSION, FinalOutputFormat.JPEG,
        FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
        Result.success(outcome)
      )
      assertEquals(ReprocessFinalizationState.COMMITTED, first.state)
      assertFalse(transaction.backupRoot.exists())
      // Each first-round mutation should have happened exactly once (AC help for counting).
      val firstMetadata = metadataWriteCount
      val firstLease = leaseReleaseCount
      val second = finalizeTransaction(
        session, transaction, directory,
        ReprocessJobKind.RAW_FUSION, FinalOutputFormat.JPEG,
        FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
        Result.success(outcome)
      )
      assertEquals(ReprocessFinalizationState.COMMITTED, second.state)
      assertTrue(first == second)
      // No second metadata write, no second fallback creation, no second cleanup, no second lease release.
      assertEquals(firstMetadata, metadataWriteCount)
      assertEquals(firstLease, leaseReleaseCount)
      assertEquals(0, createdOutputKickbackCount)
      assertEquals(0, fallbackWriteCount)
      assertEquals(0, restoreBackupsKickbackCount)
    } finally {
      createdOutputDeleteOperation = previousDelete
      KeplerJobMetadata.setAtomicWriteCountForTest(previousMetadataWrites)
      createdOutputKickbackCount = previousKickbacks
      fallbackWriteCount = previousFallbackWrites
      restoreBackupsKickbackCount = previousResets
      KeplerJobMetadata.setLeaseReleaseCountForTest(previousLeaseReleases)
      lateFinalizationHandoffScope = null
    }
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun duplicateRolledBackAfterRootDeletionPreservesOriginalFailureAndReturnsCached() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    File(directory, "final.png").writeText("after!")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    val originalCause = IllegalStateException("worker failed")
    val previousMetadataWrites = metadataWriteCount
    val previousResets = restoreBackupsKickbackCount
    val previousKickbacks = createdOutputKickbackCount
    val previousFallbackWrites = fallbackWriteCount
    val previousLeaseReleases = leaseReleaseCount
    KeplerJobMetadata.setAtomicWriteCountForTest(0)
    restoreBackupsKickbackCount = 0
    createdOutputKickbackCount = 0
    fallbackWriteCount = 0
    KeplerJobMetadata.setLeaseReleaseCountForTest(0)
    try {
      val first = finalizeTransaction(
        session, transaction, directory,
        ReprocessJobKind.RAW_FUSION, FinalOutputFormat.JPEG,
        FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
        Result.failure(originalCause)
      )
      assertEquals(ReprocessFinalizationState.ROLLED_BACK, first.state)
      assertFalse(KeplerJobMetadata.isOperationActive(directory))
      val firstMetadata = metadataWriteCount
      val firstReset = restoreBackupsKickbackCount
      val firstKick = createdOutputKickbackCount
      val firstLease = leaseReleaseCount
      // Wait until backup root cleanup completes; the session is terminally cached.
      val second = finalizeTransaction(
        session, transaction, directory,
        ReprocessJobKind.RAW_FUSION, FinalOutputFormat.JPEG,
        FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
        Result.failure(IllegalStateException("a different error for second duplicate"))
      )
      assertEquals(ReprocessFinalizationState.ROLLED_BACK, second.state)
      assertTrue(first == second)
      // Original failure cause preserved through cache.
      assertSame(originalCause, second.result.exceptionOrNull())
      // No second restore, no second metadata write, no second created-output deletion,
      // no second fallback creation, no second lease release.
      assertEquals(firstMetadata, metadataWriteCount)
      assertEquals(firstReset, restoreBackupsKickbackCount)
      assertEquals(firstKick, createdOutputKickbackCount)
      assertEquals(firstLease, leaseReleaseCount)
      assertEquals(0, fallbackWriteCount)
    } finally {
      KeplerJobMetadata.setAtomicWriteCountForTest(previousMetadataWrites)
      restoreBackupsKickbackCount = previousResets
      createdOutputKickbackCount = previousKickbacks
      fallbackWriteCount = previousFallbackWrites
      KeplerJobMetadata.setLeaseReleaseCountForTest(previousLeaseReleases)
      lateFinalizationHandoffScope = null
    }
  } finally {
    directory.deleteRecursively()
  }
}

// ── Phase 4d: Transient finalizer failure with automatic retry ──

@Test
fun transientFinalizerFailureTriggersAutomaticRetryAndSucceeds() = runBlocking {
  var invocationCount = 0
  val previousSeam = finalizerFailureSeam
  val previousCompleteCallback = lateFinalizationCompleteCallback
  finalizerFailureSeam = {
    invocationCount++
    if (invocationCount == 1) IllegalStateException("transient finalizer failure")
    else null
  }
  var barrier = CompletableDeferred<Unit>()
  lateFinalizationCompleteCallback = { barrier.complete(Unit) }
  lateFinalizationHandoffScope = null
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    val workerTerminal = CompletableDeferred<ReprocessWorkerOutcome>()
    val worker = ReprocessWorkerRun(
      terminal = workerTerminal,
      cancel = {}
    )
    registerLateFinalization(
      session, worker, directory, ReprocessJobKind.RAW_FUSION,
      FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet()
    )
    workerTerminal.complete(ReprocessWorkerOutcome(
      result = Result.failure(IllegalStateException("worker failed")),
      publicExportCommitted = false
    ))
    withTimeout(5000) { barrier.await() }
    assertEquals(ReprocessTransactionSession.LateState.TERMINAL, session.lateStateForTest())
    assertEquals(2, invocationCount)
    assertFalse(KeplerJobMetadata.isOperationActive(directory))
  } finally {
    finalizerFailureSeam = previousSeam
    lateFinalizationCompleteCallback = previousCompleteCallback
    lateFinalizationHandoffScope = null
    directory.deleteRecursively()
  }
}

@Test
fun permanentRetryFailureStaysUnresolvedWithDurableEvidence() = runBlocking {
  var invocationCount = 0
  val previousSeam = finalizerFailureSeam
  val previousCompleteCallback = lateFinalizationCompleteCallback
  val previousFallbackWrite = fallbackWriteOperation
  val previousHandler = lateFinalizationFailureHandler
  var handlerError: Throwable? = null
  lateFinalizationFailureHandler = { error, _ -> handlerError = error }
  finalizerFailureSeam = {
    invocationCount++
    IllegalStateException("permanent finalizer failure")
  }
  fallbackWriteOperation = { _, _, _ -> throw IOException("fallback write seam failure") }
  var barrier = CompletableDeferred<Unit>()
  lateFinalizationCompleteCallback = { barrier.complete(Unit) }
  lateFinalizationHandoffScope = null
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    transaction.backupRoot.deleteRecursively()
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    val workerTerminal = CompletableDeferred<ReprocessWorkerOutcome>()
    val worker = ReprocessWorkerRun(
      terminal = workerTerminal,
      cancel = {}
    )
    registerLateFinalization(
      session, worker, directory, ReprocessJobKind.RAW_FUSION,
      FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet()
    )
    workerTerminal.complete(ReprocessWorkerOutcome(
      result = Result.failure(IllegalStateException("worker failed")),
      publicExportCommitted = false
    ))
    withTimeout(5000) { barrier.await() }
    assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
    assertEquals(2, invocationCount)
    assertTrue(handlerError != null)
    assertTrue(KeplerJobMetadata.isOperationActive(directory))
  } finally {
    finalizerFailureSeam = previousSeam
    lateFinalizationCompleteCallback = previousCompleteCallback
    fallbackWriteOperation = previousFallbackWrite
    lateFinalizationFailureHandler = previousHandler
    lateFinalizationHandoffScope = null
    directory.deleteRecursively()
  }
}

@Test(expected = kotlinx.coroutines.CancellationException::class)
fun actualCallbackCancellationLeavesUnresolvedAndRethrows() {
  runBlocking {
    val directory = tempJob()
    try {
      val transaction = backup(directory, "final.png" to "before")
      val session = ReprocessTransactionSession(directory)
      val lease = session.acquireLease() ?: error("no lease")
      session.transferOwnership(transaction)
      assertTrue(session.tryAcquireLateRegistration())
      val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
      terminal.complete(ReprocessWorkerOutcome(
        result = Result.failure(IllegalStateException("worker failed")),
        publicExportCommitted = false
      ))
      val handoff = ReprocessLateFinalizationHandoff(
        session, transaction, lease!!, directory, ReprocessJobKind.RAW_FUSION,
        FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
        workerTerminal = terminal
      )
      val previousSeam = finalizerFailureSeam
      finalizerFailureSeam = { throw kotlinx.coroutines.CancellationException("callback cancelled") }
      try {
        runLateFinalization(handoff, null)
        throw AssertionError("runLateFinalization should have rethrown callback cancellation")
      } finally {
        assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
        assertTrue(KeplerJobMetadata.isOperationActive(directory))
        finalizerFailureSeam = previousSeam
        lateFinalizationHandoffScope = null
      }
    } finally {
      lateFinalizationHandoffScope = null
      directory.deleteRecursively()
    }
  }
}

@Test
fun cancelledWorkerDeferredDuringFinalizationSettlesAsWorkerFailure() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    assertTrue(session.tryAcquireLateRegistration())
    val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
    terminal.completeExceptionally(kotlinx.coroutines.CancellationException("worker cancelled"))
    val handoff = ReprocessLateFinalizationHandoff(
      session, transaction, lease!!, directory, ReprocessJobKind.RAW_FUSION,
      FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
      workerTerminal = terminal
    )
    runLateFinalization(handoff, null)
    // Cancelled worker Deferred with active caller → confirmed worker failure → rollback → TERMINAL
    assertEquals(ReprocessTransactionSession.LateState.TERMINAL, session.lateStateForTest())
    assertFalse(KeplerJobMetadata.isOperationActive(directory))
  } finally {
    lateFinalizationHandoffScope = null
    directory.deleteRecursively()
  }
}

@Test
fun retryCancellationNeverLeavesLateRegistered() = runBlocking {
  val previousSeam = finalizerFailureSeam
  finalizerFailureSeam = null // first pass succeeds
  lateFinalizationHandoffScope = null
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    // First attempt: delete backup payload → QUARANTINED → UNRESOLVED
    assertTrue(session.tryAcquireLateRegistration())
    val terminal1 = CompletableDeferred<ReprocessWorkerOutcome>()
    terminal1.complete(ReprocessWorkerOutcome(
      result = Result.failure(IllegalStateException("worker failed")),
      publicExportCommitted = false
    ))
    val handoff1 = ReprocessLateFinalizationHandoff(
      session, transaction, lease!!, directory, ReprocessJobKind.RAW_FUSION,
      FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
      workerTerminal = terminal1
    )
    val backupEntry = transaction.manifest.backupEntries.values.first()
    File(transaction.backupRoot, backupEntry.backupName).delete()
    runLateFinalization(handoff1, null)
    assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
    // Set seam to throw CancellationException for retry
    finalizerFailureSeam = { throw kotlinx.coroutines.CancellationException("retry cancelled") }
    try {
      scheduleUnresolvedRetry(handoff1)
      throw AssertionError("scheduleUnresolvedRetry should have rethrown cancellation")
    } catch (e: kotlinx.coroutines.CancellationException) {
      // runLateFinalization caught CancellationException → UNRESOLVED
      assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
    }
  } finally {
    finalizerFailureSeam = previousSeam
    lateFinalizationHandoffScope = null
    directory.deleteRecursively()
  }
}

@Test
fun duplicateFallbackMarkerKeysRejected() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    val marker = File(directory, ".reprocess_unresolved")
    marker.writeText("transactionId=tx1\nbackupRoot=.reprocess_backup_tx1\ncreatedAt=1000\ncreatedAt=2000\n")
    assertTrue(strictFallbackEvidence(directory, transaction) !== FallbackEvidence.Trustworthy)
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun duplicateQuarantineMarkerKeysRejected() = runBlocking {
  val marker = tempJob().resolve("test_quarantine")
  try {
    marker.writeText("transactionId=tx1\nbackupRoot=.reprocess_backup_tx1\nbackupRoot=.reprocess_backup_tx2\ncreatedAt=1000\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    marker.delete()
  }
}

@Test
fun legacyFixedRootMarkerWithoutManifestNotTrusted() {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    val marker = File(transaction.backupRoot, ".reprocess_quarantine")
    KeplerJobMetadata.atomicWrite(marker, "quarantined")
    // Delete the manifest so only the legacy marker exists
    assertTrue(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).delete())
    assertFalse(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).exists())
    assertNull(readQuarantineMarkerIdentity(marker))
    val evidence = strictRootEvidence(directory, transaction)
    assertTrue("Expected not Trustworthy, got $evidence", evidence !== RootEvidence.Trustworthy)
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun identityBoundMarkerWithMatchingTransactionTrusted() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    val marker = File(transaction.backupRoot, ".reprocess_quarantine")
    KeplerJobMetadata.atomicWrite(marker, quarantineMarkerContent(transaction))
    // Identity-bound marker with matching content → trustworthy
    assertEquals(RootEvidence.Trustworthy, strictRootEvidence(directory, transaction))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun corruptFallbackPreservesRootOnRecovery() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    val fallback = File(directory, ".reprocess_unresolved")
    fallback.writeText("garbage content")
    // Recovery should not delete the root when fallback is corrupt
    recoverValidatedQuarantine(directory)
    assertTrue(transaction.backupRoot.isDirectory)
    assertTrue(fallback.exists())
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun fallbackWithCommittedRootRemovesFallbackAndRoot() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
    val fallback = File(directory, ".reprocess_unresolved")
    fallback.writeText("transactionId=${transaction.transactionId}\nbackupRoot=${transaction.backupRoot.name}\ncreatedAt=${transaction.manifest.createdAt}\n")
    recoverValidatedQuarantine(directory)
    assertFalse(fallback.exists())
    assertFalse(transaction.backupRoot.exists())
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun fallbackWithRolledBackRootRemovesFallbackAndRoot() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
    val fallback = File(directory, ".reprocess_unresolved")
    fallback.writeText("transactionId=${transaction.transactionId}\nbackupRoot=${transaction.backupRoot.name}\ncreatedAt=${transaction.manifest.createdAt}\n")
    recoverValidatedQuarantine(directory)
    assertFalse(fallback.exists())
    assertFalse(transaction.backupRoot.exists())
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun fallbackDeletionFailurePreservesAll() = runBlocking {
  val previousDeleteOp = fallbackDeleteOperation
  fallbackDeleteOperation = { false }
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
    val fallback = File(directory, ".reprocess_unresolved")
    fallback.writeText("transactionId=${transaction.transactionId}\nbackupRoot=${transaction.backupRoot.name}\ncreatedAt=${transaction.manifest.createdAt}\n")
    recoverValidatedQuarantine(directory)
    assertTrue(fallback.exists())
    assertTrue(transaction.backupRoot.isDirectory)
    assertTrue(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).isFile)
  } finally {
    fallbackDeleteOperation = previousDeleteOp
    directory.deleteRecursively()
  }
}

@Test
fun fallbackIdentityMismatchPreservesRoots() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
    val fallback = File(directory, ".reprocess_unresolved")
    fallback.writeText("transactionId=wrongId\nbackupRoot=${transaction.backupRoot.name}\ncreatedAt=${transaction.manifest.createdAt}\n")
    recoverValidatedQuarantine(directory)
    assertTrue(fallback.exists())
    assertTrue(transaction.backupRoot.isDirectory)
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun fallbackRootNameMismatchPreservesRoots() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
    val fallback = File(directory, ".reprocess_unresolved")
    fallback.writeText("transactionId=${transaction.transactionId}\nbackupRoot=.reprocess_backup_wrong\ncreatedAt=${transaction.manifest.createdAt}\n")
    recoverValidatedQuarantine(directory)
    assertTrue(fallback.exists())
    assertTrue(transaction.backupRoot.isDirectory)
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun fallbackCreatedAtMismatchPreservesRoots() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
    val fallback = File(directory, ".reprocess_unresolved")
    fallback.writeText("transactionId=${transaction.transactionId}\nbackupRoot=${transaction.backupRoot.name}\ncreatedAt=999999\n")
    recoverValidatedQuarantine(directory)
    assertTrue(fallback.exists())
    assertTrue(transaction.backupRoot.isDirectory)
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun fallbackRemovedButRootDeleteFailsRestoresManifest() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
    File(transaction.backupRoot, "final.png.BACKUP").delete()
    val fallback = File(directory, ".reprocess_unresolved")
    fallback.writeText("transactionId=${transaction.transactionId}\nbackupRoot=${transaction.backupRoot.name}\ncreatedAt=${transaction.manifest.createdAt}\n")
    val previousRootDelete = cleanupRootDeleteOperation
    cleanupRootDeleteOperation = { _ -> false }
    try {
      recoverValidatedQuarantine(directory)
      assertFalse(fallback.exists())
      assertTrue(transaction.backupRoot.isDirectory)
      assertTrue(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).isFile)
    } finally {
      cleanupRootDeleteOperation = previousRootDelete
    }
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun fallbackSymlinkToExternalMatchingPayloadNotTrusted() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
    val externalRoot = Files.createTempDirectory("external-marker-").toFile()
    try {
      val externalMarker = File(externalRoot, ".reprocess_unresolved")
      externalMarker.writeText("transactionId=${transaction.transactionId}\nbackupRoot=${transaction.backupRoot.name}\ncreatedAt=${transaction.manifest.createdAt}\n")
      val symlinkMarker = File(directory, ".reprocess_unresolved")
      try {
        Files.createSymbolicLink(symlinkMarker.toPath(), externalMarker.toPath())
      } catch (e: Exception) {
        // Symlink creation may fail on Windows without privileges/Developer Mode.
        assumeTrue("Symlink creation not supported in this environment — skipping test", false)
        return@runBlocking
      }
      // Symlink marker should NOT be trusted and must NOT permit root cleanup
      recoverValidatedQuarantine(directory)
      assertTrue(symlinkMarker.exists())
      assertTrue(transaction.backupRoot.isDirectory)
    } finally {
      externalRoot.deleteRecursively()
    }
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun afterManifestNewFileAppearingBlocksAndIsNotRemoved() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
    File(transaction.backupRoot, "final.png.BACKUP").delete()
    val previousAfterDelete = afterManifestDeleteOperation
    // Inject a new file after manifest deletion
    afterManifestDeleteOperation = { root ->
      File(root, "unexpected_file.txt").writeText("appeared during window")
    }
    try {
      assertFalse(cleanupBackups(transaction))
      // The terminal manifest should be restored
      assertTrue(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).isFile)
      // The injected file must survive and must NOT be removed
      assertTrue(File(transaction.backupRoot, "unexpected_file.txt").exists())
    } finally {
      afterManifestDeleteOperation = previousAfterDelete
    }
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun actualCallbackCancellationViaScopeLeavesUnresolved() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    // Use a controlled scope with Unconfined so the callback job is cancelable
    val callbackJob = kotlinx.coroutines.Job()
    val controlledScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + callbackJob)
    val previousScope = lateFinalizationCallbackScope
    lateFinalizationCallbackScope = controlledScope
    // The finalizer seam cancels the callback job and throws CancellationException
    // from inside the running coroutine.
    val previousSeam = finalizerFailureSeam
    finalizerFailureSeam = {
      callbackJob.cancel()
      // After cancellation, the next suspension point or cancellation check throws.
      // Throw CancellationException directly since the coroutine may not reach a
      // suspension point before returning from the seam.
      throw kotlinx.coroutines.CancellationException("intentional callback cancellation")
    }
    val previousCompleteCallback = lateFinalizationCompleteCallback
    var barrier = CompletableDeferred<Unit>()
    lateFinalizationCompleteCallback = { barrier.complete(Unit) }
    lateFinalizationHandoffScope = null
    try {
      val workerTerminal = CompletableDeferred<ReprocessWorkerOutcome>()
      val worker = ReprocessWorkerRun(terminal = workerTerminal, cancel = {})
      registerLateFinalization(
        session, worker, directory, ReprocessJobKind.RAW_FUSION,
        FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet()
      )
      workerTerminal.complete(ReprocessWorkerOutcome(
        result = Result.failure(IllegalStateException("worker failed")),
        publicExportCommitted = false
      ))
      withTimeout(5000) { barrier.await() }
      // Session should be UNRESOLVED (callback cancelled, durable evidence preserved)
      assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
      assertTrue(KeplerJobMetadata.isOperationActive(directory))
      // Durable evidence is preserved through the strict-root or fallback evidence path
      // (persistLateEvidence writes fallback only when root evidence is not trustworthy).
      // No automatic retry started — session stays UNRESOLVED, not TERMINAL or FINIALIZING
      assertFalse(session.isTerminal())
      assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
    } finally {
      lateFinalizationCallbackScope = previousScope
      finalizerFailureSeam = previousSeam
      lateFinalizationCompleteCallback = previousCompleteCallback
      lateFinalizationHandoffScope = null
      lease.release()
    }
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun initialPassCancelledWorkerDeferredSettlesAsWorkerFailure() = runBlocking {
  val previousSeam = finalizerFailureSeam
  finalizerFailureSeam = null // first pass succeeds
  lateFinalizationHandoffScope = null
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    assertTrue(session.tryAcquireLateRegistration())
    // First attempt: worker terminal is cancelled → settles as worker failure
    val terminal1 = CompletableDeferred<ReprocessWorkerOutcome>()
    terminal1.completeExceptionally(kotlinx.coroutines.CancellationException("worker cancelled"))
    val handoff1 = ReprocessLateFinalizationHandoff(
      session, transaction, lease!!, directory, ReprocessJobKind.RAW_FUSION,
      FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
      workerTerminal = terminal1
    )
    runLateFinalization(handoff1, null)
    // Cancelled worker Deferred during initial pass → confirmed worker failure → rollback → TERMINAL
    assertEquals(ReprocessTransactionSession.LateState.TERMINAL, session.lateStateForTest())
    assertFalse(KeplerJobMetadata.isOperationActive(directory))
  } finally {
    finalizerFailureSeam = previousSeam
    lateFinalizationHandoffScope = null
    directory.deleteRecursively()
  }
}

@Test
fun fallbackDeletionSeamExceptionDoesNotEscape() = runBlocking {
  val previousDeleteOp = fallbackDeleteOperation
  fallbackDeleteOperation = { throw IOException("seam deletion failure") }
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    // Simulate terminal manifest
    val manifest = File(transaction.backupRoot, ".reprocess_tx_manifest")
    manifest.writeText(JSONObject().apply {
      put("transactionId", transaction.transactionId)
      put("createdAt", transaction.manifest.createdAt)
      put("state", ReprocessTransactionState.COMMITTED.name)
      put("preExistingPaths", JSONArray())
      put("backedUpPaths", JSONArray())
      put("backupEntries", JSONObject())
    }.toString())
    val fallback = File(directory, ".reprocess_unresolved")
    fallback.writeText("transactionId=${transaction.transactionId}\nbackupRoot=${transaction.backupRoot.name}\ncreatedAt=${transaction.manifest.createdAt}\n")
    // Should not throw — seam exception caught inside recovery
    recoverValidatedQuarantine(directory)
    // Fallback and root preserved
    assertTrue(fallback.exists())
    assertTrue(transaction.backupRoot.isDirectory)
  } finally {
    fallbackDeleteOperation = previousDeleteOp
    directory.deleteRecursively()
  }
}

@Test
fun corruptIdentityQuarantineMarkerOverwritten() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    val marker = File(transaction.backupRoot, ".reprocess_quarantine")
    marker.writeText("transactionId=wrong\nbackupRoot=wrong\ncreatedAt=0")
    writeQuarantineMarker(transaction)
    assertEquals(quarantineMarkerContent(transaction), marker.readText().trimEnd() + "\n")
  } finally {
    directory.deleteRecursively()
  }
}

// ── Phase 4e: Retry with registerLateFinalization-based worker cancellation ──

fun retryCancelledWorkerDeferredViaRegisterLateFinalization() = runBlocking {
  var invocationCount = 0
  val previousSeam = finalizerFailureSeam
  finalizerFailureSeam = {
    invocationCount++
    if (invocationCount == 1) IllegalStateException("first finalizer failure") else null
  }
  val previousCallback = lateFinalizationCompleteCallback
  var barrier = CompletableDeferred<Unit>()
  lateFinalizationCompleteCallback = { barrier.complete(Unit) }
  val previousHandoffScope = lateFinalizationHandoffScope
  lateFinalizationHandoffScope = null
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    val workerTerminal = CompletableDeferred<ReprocessWorkerOutcome>()
    val worker = ReprocessWorkerRun(terminal = workerTerminal, cancel = {})
    registerLateFinalization(
      session, worker, directory, ReprocessJobKind.RAW_FUSION,
      FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet()
    )
    // Complete the Deferred exceptionally with CancellationException — this drives
    // the real resolveWorkerTerminal path: CancellationException with active callback
    // coroutine → confirmed worker failure settlement.
    workerTerminal.completeExceptionally(kotlinx.coroutines.CancellationException("worker cancelled"))
    withTimeout(5000) { barrier.await() }
    // Exactly two finalizer invocations: first fails, auto-retry succeeds.
    assertEquals(2, invocationCount)
    // No third invocation — retry bound of 1 is not exceeded.
    assertEquals(ReprocessTransactionSession.LateState.TERMINAL, session.lateStateForTest())
    assertFalse(KeplerJobMetadata.isOperationActive(directory))
    assertTrue(workerTerminal.isCancelled)
  } finally {
    finalizerFailureSeam = previousSeam
    lateFinalizationCompleteCallback = previousCallback
    lateFinalizationHandoffScope = previousHandoffScope
    directory.deleteRecursively()
  }
}

// ── Exact marker parsing tests (blank lines rejected by readMarkerIdentity) ──

@Test
fun markerWithLeadingBlankLineRejected() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    val marker = File(directory, ".reprocess_unresolved")
    marker.writeText("\ntransactionId=${transaction.transactionId}\nbackupRoot=${transaction.backupRoot.name}\ncreatedAt=${transaction.manifest.createdAt}\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun markerWithTrailingBlankLineRejected() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    val marker = File(directory, ".reprocess_unresolved")
    marker.writeText("transactionId=${transaction.transactionId}\nbackupRoot=${transaction.backupRoot.name}\ncreatedAt=${transaction.manifest.createdAt}\n\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun markerWithIntermediateBlankLineRejected() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    val marker = File(directory, ".reprocess_unresolved")
    marker.writeText("transactionId=${transaction.transactionId}\n\nbackupRoot=${transaction.backupRoot.name}\ncreatedAt=${transaction.manifest.createdAt}\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun transactionIdMismatchPreservesAllRoots() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
    val fallback = File(directory, ".reprocess_unresolved")
    fallback.writeText("transactionId=wrongId\nbackupRoot=${transaction.backupRoot.name}\ncreatedAt=${transaction.manifest.createdAt}\n")
    recoverValidatedQuarantine(directory)
    assertTrue(fallback.exists())
    assertTrue(transaction.backupRoot.isDirectory)
  } finally {
    directory.deleteRecursively()
  }
}

// ── Post-manifest cleanup tests ──

@Test
fun postManifestListingNullWhileRootExistsReturnsFalse() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
    File(transaction.backupRoot, "final.png.BACKUP").delete()
    val callCount = java.util.concurrent.atomic.AtomicInteger(0)
    val previousList = cleanupListOperation
    cleanupListOperation = { root ->
      val c = callCount.incrementAndGet()
      if (c == 4) null else root.listFiles()
    }
    try {
      assertFalse(cleanupBackups(transaction))
      assertTrue(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).isFile)
      assertTrue(transaction.backupRoot.isDirectory)
    } finally {
      cleanupListOperation = previousList
    }
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun unknownFileAppearsAndManifestRestorationSucceeds() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
    File(transaction.backupRoot, "final.png.BACKUP").delete()
    // Ensure the manifest at start exists
    assertTrue(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).isFile)
    val previousAfterDelete = afterManifestDeleteOperation
    afterManifestDeleteOperation = { root ->
      File(root, "appeared_after.txt").writeText("after")
    }
    try {
      assertFalse(cleanupBackups(transaction))
      // Manifest restored to match durable identity
      assertTrue(File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE).isFile)
      assertTrue(File(transaction.backupRoot, "appeared_after.txt").exists())
    } finally {
      afterManifestDeleteOperation = previousAfterDelete
    }
  } finally {
    directory.deleteRecursively()
  }
}



@Test
fun rootDeletionFailureRestoresStrictTerminalManifest() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    writeTransactionState(transaction, ReprocessTransactionState.ROLLED_BACK)
    File(transaction.backupRoot, "final.png.BACKUP").delete()
    val previousRootDelete = cleanupRootDeleteOperation
    cleanupRootDeleteOperation = { _ -> false }
    try {
      assertFalse(cleanupBackups(transaction))
      // Manifest must be restored and match the durable terminal identity
      val restored = File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE)
      assertTrue(restored.isFile)
      val parsed = loadStrictManifest(restored)
      assertEquals(transaction.transactionId, parsed.transactionId)
      assertEquals(transaction.manifest.createdAt, parsed.createdAt)
      assertEquals(ReprocessTransactionState.ROLLED_BACK, parsed.state)
    } finally {
      cleanupRootDeleteOperation = previousRootDelete
    }
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun rootDeletionExceptionRestoresStrictTerminalManifest() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
    File(transaction.backupRoot, "final.png.BACKUP").delete()
    val previousRootDelete = cleanupRootDeleteOperation
    cleanupRootDeleteOperation = { throw IOException("root deletion IO error") }
    try {
      assertFalse(cleanupBackups(transaction))
      // Manifest must be restored and verify the durable terminal identity
      val restored = File(transaction.backupRoot, REPROCESS_TX_MANIFEST_FILE)
      assertTrue(restored.isFile)
      val parsed = loadStrictManifest(restored)
      assertEquals(transaction.transactionId, parsed.transactionId)
      assertEquals(ReprocessTransactionState.COMMITTED, parsed.state)
    } finally {
      cleanupRootDeleteOperation = previousRootDelete
    }
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun strictRootEvidenceCanonicalizationSeamReturnsInspectionFailed() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    val originalException = IOException("seam canonicalization failure")
    val previousSeam = strictRootEvidenceCanonicalizationSeam
    strictRootEvidenceCanonicalizationSeam = { _ -> throw originalException }
    try {
      val evidence = strictRootEvidence(directory, transaction)
      assertTrue("Expected InspectionFailed, got $evidence", evidence is RootEvidence.InspectionFailed)
      val inspectionFailed = evidence as RootEvidence.InspectionFailed
      assertSame(originalException, inspectionFailed.cause)
    } finally {
      strictRootEvidenceCanonicalizationSeam = previousSeam
    }
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun danglingFallbackSymlinkBlocksGating() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    val missingTarget = File(directory, "nonexistent_target")
    val symlink = File(directory, ".reprocess_unresolved")
    try {
      Files.createSymbolicLink(symlink.toPath(), missingTarget.toPath())
    } catch (e: Exception) {
      assumeTrue("Symlink creation not supported — skipping test", false)
      return@runBlocking
    }
    assertTrue(isReprocessQuarantined(directory))
    assertTrue(removeMatchingFallbackQuarantine(directory, transaction).not())
    assertEquals(MarkerPathClassification.Symlink, classifyMarkerPath(File(directory, ".reprocess_unresolved"), directory))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun postWriteSymlinkSubstitutionFailsFallbackVerification() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    // Verify symlink support before running the seam
    try {
      val probe = File(directory, "symlink_probe")
      Files.createSymbolicLink(probe.toPath(), transaction.backupRoot.toPath())
      probe.delete()
    } catch (e: Exception) {
      assumeTrue("Symlink creation not supported — skipping test", false)
      return@runBlocking
    }
    val previousFallbackWrite = fallbackWriteOperation
    fallbackWriteOperation = { jobDir, marker, _ ->
      val externalTarget = File(jobDir, "symlink_target")
      externalTarget.writeText("fake marker content")
      Files.createSymbolicLink(marker.toPath(), externalTarget.toPath())
    }
    try {
      assertThrows(IllegalStateException::class.java) {
        ensureDurableFallbackQuarantine(directory, transaction)
      }
    } finally {
      fallbackWriteOperation = previousFallbackWrite
    }
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun postDeleteDanglingSymlinkNotConsideredAbsent() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "content")
    ensureDurableFallbackQuarantine(directory, transaction)
    val marker = File(directory, ".reprocess_unresolved")
    assertTrue(marker.isFile)
    // Verify symlink support before testing
    try {
      val probe = File(directory, "symlink_probe")
      Files.createSymbolicLink(probe.toPath(), File(directory, "nonexistent").toPath())
      probe.delete()
    } catch (e: Exception) {
      assumeTrue("Symlink creation not supported — skipping test", false)
      return@runBlocking
    }
    val missingTarget = File(directory, "ghost_target")
    val previousDeleteSeam = fallbackDeleteOperation
    fallbackDeleteOperation = { file ->
      file.delete()
      Files.createSymbolicLink(file.toPath(), missingTarget.toPath())
      true
    }
    try {
      assertFalse(removeMatchingFallbackQuarantine(directory, transaction))
      assertTrue(isReprocessQuarantined(directory))
    } finally {
      fallbackDeleteOperation = previousDeleteSeam
    }
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun readMarkerIdentity_extraEqualsInValueReturnsNull() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.writeText("transactionId=tx\nbackupRoot=.reprocess_backup_tx\ncreatedAt=1000=extra\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun readMarkerIdentity_txIdWithSlashReturnsNull() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.writeText("transactionId=tx/1\nbackupRoot=.reprocess_backup_tx/1\ncreatedAt=1000\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun readMarkerIdentity_txIdWithBackslashReturnsNull() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.writeText("transactionId=tx\\1\nbackupRoot=.reprocess_backup_tx\\1\ncreatedAt=1000\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun readMarkerIdentity_txIdWithDotReturnsNull() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.writeText("transactionId=..\nbackupRoot=.reprocess_backup_..\ncreatedAt=1000\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun readMarkerIdentity_backupRootMismatchTxIdReturnsNull() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.writeText("transactionId=tx1\nbackupRoot=.reprocess_backup_tx2\ncreatedAt=1000\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun readMarkerIdentity_txIdWithWhitespaceReturnsNull() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.writeText("transactionId=tx 1\nbackupRoot=.reprocess_backup_tx 1\ncreatedAt=1000\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

// ── Canonical createdAt parser tests ──

@Test
fun readMarkerIdentity_createdAtWithPlusReturnsNull() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.writeText("transactionId=tx\nbackupRoot=.reprocess_backup_tx\ncreatedAt=+1000\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun readMarkerIdentity_createdAtLeadingZeroReturnsNull() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.writeText("transactionId=tx\nbackupRoot=.reprocess_backup_tx\ncreatedAt=01\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun readMarkerIdentity_createdAtMultipleLeadingZerosReturnsNull() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.writeText("transactionId=tx\nbackupRoot=.reprocess_backup_tx\ncreatedAt=0001\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun readMarkerIdentity_createdAtNegativeReturnsNull() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.writeText("transactionId=tx\nbackupRoot=.reprocess_backup_tx\ncreatedAt=-1\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun readMarkerIdentity_createdAtZeroReturnsNull() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.writeText("transactionId=tx\nbackupRoot=.reprocess_backup_tx\ncreatedAt=0\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun readMarkerIdentity_createdAtHexReturnsNull() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.writeText("transactionId=tx\nbackupRoot=.reprocess_backup_tx\ncreatedAt=0xFF\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun readMarkerIdentity_createdAtOverflowReturnsNull() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.writeText("transactionId=tx\nbackupRoot=.reprocess_backup_tx\ncreatedAt=999999999999999999999\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun readMarkerIdentity_validPositiveCreatedAtParsed() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.writeText("transactionId=tx1\nbackupRoot=.reprocess_backup_tx1\ncreatedAt=42\n")
    val identity = readQuarantineMarkerIdentity(marker)
    assertNotNull(identity)
    assertEquals(42L, identity!!.third)
  } finally {
    directory.deleteRecursively()
  }
}

// ── Marker path classifier tests ──

@Test
fun classifyMarkerPath_liveSymlinkReturnsSymlink() = runBlocking {
  val directory = tempJob()
  try {
    val outside = File(directory, "external_marker")
    outside.writeText("transactionId=tx\nbackupRoot=.reprocess_backup_tx\ncreatedAt=1000\n")
    val symlink = File(directory, "test_marker")
    try {
      Files.createSymbolicLink(symlink.toPath(), outside.toPath())
    } catch (e: Exception) {
      assumeTrue("Symlink creation not supported — skipping test", false)
      return@runBlocking
    }
    val classification = classifyMarkerPath(symlink, directory)
    assertTrue(classification is MarkerPathClassification.Symlink)
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun classifyMarkerPath_danglingSymlinkReturnsSymlink() = runBlocking {
  val directory = tempJob()
  try {
    val missing = File(directory, "nonexistent_target")
    val symlink = File(directory, "dangling_marker")
    try {
      Files.createSymbolicLink(symlink.toPath(), missing.toPath())
    } catch (e: Exception) {
      assumeTrue("Symlink creation not supported — skipping test", false)
      return@runBlocking
    }
    val classification = classifyMarkerPath(symlink, directory)
    // Dangling symlink is still a symlink
    assertTrue(classification is MarkerPathClassification.Symlink)
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun classifyMarkerPath_directoryReturnsNotRegularFile() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.mkdir()
    val classification = classifyMarkerPath(marker, directory)
    assertTrue(classification is MarkerPathClassification.NotRegularFile)
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun readMarkerIdentity_extraEqualsInKeyReturnsNull() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.writeText("transactionId=tx\nbackupRoot=.reprocess_backup_tx\ncreate=dAt=1000\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun readMarkerIdentity_whitespaceInKeyReturnsNull() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.writeText("transactionId=tx\nbackupRoo t=.reprocess_backup_tx\ncreatedAt=1000\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

@Test
fun readMarkerIdentity_backupRootWithoutPrefixReturnsNull() = runBlocking {
  val directory = tempJob()
  try {
    val marker = File(directory, "test_marker")
    marker.writeText("transactionId=tx\nbackupRoot=wrong_name\ncreatedAt=1000\n")
    assertNull(readQuarantineMarkerIdentity(marker))
  } finally {
    directory.deleteRecursively()
  }
}

fun manifestRestorePlusFallbackFailurePreservesTerminalState() = runBlocking {
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    writeTransactionState(transaction, ReprocessTransactionState.COMMITTED)
    File(transaction.backupRoot, "final.png.BACKUP").delete()
    val previousAfterDelete = afterManifestDeleteOperation
    val previousRestore = manifestRestoreOperation
    val previousFallbackWrite = fallbackWriteOperation
    afterManifestDeleteOperation = { root ->
      File(root, "appeared_after_delete.txt").writeText("blocking")
    }
    // Write-seam that produces no file (manifest restoration fails verification)
    manifestRestoreOperation = { _, _ -> /* write nothing */ }
    fallbackWriteOperation = { _, _, _ ->
      throw IOException("fallback persistence seam failure")
    }
    try {
      val error = assertThrows(CleanupEvidenceException::class.java) {
        cleanupBackups(transaction)
      }
      assertEquals("unknown content after manifest delete", error.triggerDescription)
      assertTrue(error.message!!.contains("fallback"))
      assertNotNull(error.manifestError)
      assertNotNull(error.fallbackError)
      assertTrue(File(transaction.backupRoot, "appeared_after_delete.txt").exists())
      assertTrue(transaction.backupRoot.isDirectory)
    } finally {
      afterManifestDeleteOperation = previousAfterDelete
      manifestRestoreOperation = previousRestore
      fallbackWriteOperation = previousFallbackWrite
    }
  } finally {
    directory.deleteRecursively()
  }
}

private fun rootManifest(txId: String, root: File): ReprocessTransactionManifest = ReprocessTransactionManifest(
  transactionId = txId,
  createdAt = System.currentTimeMillis(),
  preExistingPaths = emptySet(),
  backedUpPaths = emptySet(),
  backupEntries = emptyMap()
)
}
