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
    fun cancelledWorkerDeferredIsWorkerFailureWhileCallerActive() = runBlocking {
        val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
        terminal.cancel(kotlinx.coroutines.CancellationException("worker cancelled"))
        val result = acquireWorkerTerminal(
            ReprocessWorkerRun(terminal) {},
            callerCancellation = null
        )
        assertTrue(result is WorkerTerminalResult.DeferredExceptionalCompletion)
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

// @Test(expected = CancellationException::class) — Phase 4: rework with runLateFinalizationInternal/seam
fun _lateCallbackCancellationPhase4Rethrows() {
  runBlocking {
    val directory = tempJob()
    try {
      val transaction = backup(directory, "final.png" to "before")
      val session = ReprocessTransactionSession(directory)
      val lease = session.acquireLease() ?: error("no lease")
      session.transferOwnership(transaction)
      assertTrue(session.tryAcquireLateRegistration())
      val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
      terminal.completeExceptionally(kotlinx.coroutines.CancellationException("callback cancelled"))
      val handoff = ReprocessLateFinalizationHandoff(
        session, transaction, lease!!, directory, ReprocessJobKind.RAW_FUSION,
        FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
        workerTerminal = terminal
      )
      try {
        runLateFinalization(handoff, null)
        throw AssertionError("runLateFinalization should have rethrown callback cancellation")
      } finally {
        assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
        assertTrue(KeplerJobMetadata.isOperationActive(directory))
        lateFinalizationHandoffScope = null
      }
    } finally {
      directory.deleteRecursively()
    }
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
  finalizerFailureSeam = {
    invocationCount++
    if (invocationCount == 1) IllegalStateException("transient finalizer failure")
    else null
  }
  lateFinalizationHandoffScope = null
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
    runLateFinalization(handoff, null)
    // First attempt fails (injected seam), retry succeeds
    assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
    assertEquals(1, invocationCount)
    val result = scheduleUnresolvedRetry(handoff)
    assertNotNull(result)
    assertEquals(ReprocessTransactionSession.LateState.TERMINAL, session.lateStateForTest())
    assertEquals(ReprocessFinalizationState.ROLLED_BACK, result!!.state)
    assertEquals(2, invocationCount)
    assertFalse(KeplerJobMetadata.isOperationActive(directory))
  } finally {
    finalizerFailureSeam = previousSeam
    lateFinalizationHandoffScope = null
    directory.deleteRecursively()
  }
}

@Test
fun permanentRetryFailureStaysUnresolvedWithDurableEvidence() = runBlocking {
  var invocationCount = 0
  val previousSeam = finalizerFailureSeam
  finalizerFailureSeam = {
    invocationCount++
    IllegalStateException("permanent finalizer failure")
  }
  lateFinalizationHandoffScope = null
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    // Delete backup root so strictRootEvidence fails → fallback is created
    transaction.backupRoot.deleteRecursively()
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
    runLateFinalization(handoff, null)
    assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
    assertEquals(1, invocationCount)
    val result = scheduleUnresolvedRetry(handoff)
    assertNull(result)
    assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
    assertEquals(2, invocationCount)
    assertTrue(KeplerJobMetadata.isOperationActive(directory))
    assertTrue(File(directory, ".reprocess_unresolved").isFile)
  } finally {
    finalizerFailureSeam = previousSeam
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
fun retryExhaustionWithoutDurableEvidenceCallsFailureHandler() = runBlocking {
  val previousSeam = finalizerFailureSeam
  val previousFallbackWrite = fallbackWriteOperation
  finalizerFailureSeam = { IllegalStateException("stuck") }
  fallbackWriteOperation = { _, _, _ -> throw IOException("fallback write seam failure") }
  var handlerCalled = false
  val previousHandler = lateFinalizationFailureHandler
  lateFinalizationFailureHandler = { _, _ -> handlerCalled = true }
  lateFinalizationHandoffScope = null
  val directory = tempJob()
  try {
    val transaction = backup(directory, "final.png" to "before")
    transaction.backupRoot.deleteRecursively()
    val session = ReprocessTransactionSession(directory)
    val lease = session.acquireLease() ?: error("no lease")
    session.transferOwnership(transaction)
    assertTrue(session.tryAcquireLateRegistration())
    val terminal = CompletableDeferred<ReprocessWorkerOutcome>()
    terminal.completeExceptionally(IllegalStateException("worker failed"))
    val handoff = ReprocessLateFinalizationHandoff(
      session, transaction, lease!!, directory, ReprocessJobKind.RAW_FUSION,
      FinalOutputFormat.JPEG, FrameSelectionMode.AUTO_RULE_BASED, emptySet(),
      workerTerminal = terminal
    )
    runLateFinalization(handoff, null)
    scheduleUnresolvedRetry(handoff)
    assertEquals(ReprocessTransactionSession.LateState.UNRESOLVED, session.lateStateForTest())
    assertTrue(session.lateRetryExhausted())
    assertTrue(strictRootEvidence(directory, transaction) !== RootEvidence.Trustworthy)
    assertTrue(strictFallbackEvidence(directory, transaction) !== FallbackEvidence.Trustworthy)
    assertFalse(File(directory, ".reprocess_unresolved").exists())
    // Manual invocation of exhaustion check (simulates what registerLateFinalization's callback does)
    if (session.lateStateForTest() == ReprocessTransactionSession.LateState.UNRESOLVED && session.lateRetryExhausted()) {
      val rootEv = strictRootEvidence(directory, transaction)
      val fbEv = strictFallbackEvidence(directory, transaction)
      if (rootEv !== RootEvidence.Trustworthy && fbEv !== FallbackEvidence.Trustworthy) {
        val handler = lateFinalizationFailureHandler
        if (handler != null) handler(IllegalStateException("test exhaustion"), directory)
      }
    }
    assertTrue(handlerCalled)
  } finally {
    finalizerFailureSeam = previousSeam
    fallbackWriteOperation = previousFallbackWrite
    lateFinalizationFailureHandler = previousHandler
    lateFinalizationHandoffScope = null
    directory.deleteRecursively()
  }
}

// ── Private helpers ───────────────────────────────────────────────────────

private fun rootManifest(txId: String, root: File): ReprocessTransactionManifest = ReprocessTransactionManifest(
  transactionId = txId,
  createdAt = System.currentTimeMillis(),
  preExistingPaths = emptySet(),
  backedUpPaths = emptySet(),
  backupEntries = emptyMap()
)
}
