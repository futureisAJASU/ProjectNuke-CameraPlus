package com.projectnuke.keplernightlab

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class KeplerJobMetadataTest {
    @Test
    fun atomicWriteKeepsReadableMetadataAndAddsSchemaVersion() {
        val directory = Files.createTempDirectory("kepler-job-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            KeplerJobMetadata.update(directory) { it.put("status", "COMPLETE") }

            KeplerJobMetadata.update(directory) {
                it.remove("status")
                it.put("status", "COMPLETE")
                it.put("temporaryKey", "removed")
                it.remove("temporaryKey")
            }

            val writers = (0 until 8).map { index ->
                Thread {
                    KeplerJobMetadata.update(directory) { it.put("independent_$index", index) }
                }.also { it.start() }
            }
            writers.forEach { it.join() }

            val job = KeplerJobMetadata.read(directory)
            assertEquals("COMPLETE", job.getString("status"))
            assertFalse(job.has("temporaryKey"))
            assertTrue(job.getInt("schemaVersion") >= 1)
            (0 until 8).forEach { index -> assertEquals(index, job.getInt("independent_$index")) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun activeOperationMarkerPersistsRuntimeIdentityAndClearsByOwner() {
        val directory = Files.createTempDirectory("kepler-runtime-operation-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val operationId = KeplerJobMetadata.beginActiveOperation(
                directory,
                kind = KeplerActiveOperationKind.PROCESSING_RAW,
                startedAt = 123L
            )
            val active = KeplerJobMetadata.read(directory)
            assertEquals(KeplerRuntimeSession.id, active.getString(ACTIVE_RUNTIME_SESSION_ID))
            assertEquals(operationId, active.getString(ACTIVE_OPERATION_ID))
            assertEquals("PROCESSING_RAW", active.getString(ACTIVE_OPERATION_KIND))
            assertEquals(123L, active.getLong(ACTIVE_OPERATION_STARTED_AT))

            assertFalse(KeplerJobMetadata.clearActiveOperation(directory, "other-operation"))
            assertTrue(KeplerJobMetadata.clearActiveOperation(directory, operationId))
            val cleared = KeplerJobMetadata.read(directory)
            assertFalse(cleared.has(ACTIVE_RUNTIME_SESSION_ID))
            assertFalse(cleared.has(ACTIVE_OPERATION_ID))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unrelatedOperationCannotReplaceAnExistingProcessLocalOwner() {
        val directory = Files.createTempDirectory("kepler-runtime-replacement-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val first = KeplerJobMetadata.beginActiveOperation(directory, kind = KeplerActiveOperationKind.CAPTURE_RAW)
            assertThrows(IllegalStateException::class.java) {
                KeplerJobMetadata.beginActiveOperation(directory, kind = KeplerActiveOperationKind.PUBLIC_EXPORT)
            }
            val current = KeplerJobMetadata.read(directory)
            assertEquals(first, current.getString(ACTIVE_OPERATION_ID))
            assertEquals("CAPTURE_RAW", current.getString(ACTIVE_OPERATION_KIND))
            assertTrue(KeplerJobMetadata.clearActiveOperation(directory, first))
            assertFalse(KeplerJobMetadata.read(directory).has(ACTIVE_OPERATION_ID))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedWorkerDispatchConsumesCaptureHandoffExactlyOnce() {
        val directory = Files.createTempDirectory("kepler-dispatch-handoff-").toFile()
        var lease: JobOperationLease? = null
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "CAPTURING"))
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.PROCESSING_START
            )
            val captureOperationId = KeplerJobMetadata.beginActiveOperation(
                directory,
                kind = KeplerActiveOperationKind.CAPTURE_YUV,
                ownerLease = lease
            )
            assertTrue(
                KeplerJobMetadata.publishProcessingHandoff(
                    directory,
                    captureOperationId,
                    KeplerActiveOperationKind.PROCESSING_YUV
                )
            )
            assertTrue(KeplerJobMetadata.clearActiveOperation(directory, captureOperationId))
            assertTrue(KeplerJobMetadata.isOperationOwner(directory, lease!!))

            assertTrue(
                KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(
                    directory,
                    lease
                )
            )
            val settled = KeplerJobMetadata.read(directory)
            assertFalse(settled.has(PROCESSING_HANDOFF_OPERATION_ID))
            assertEquals("STABLE", settled.getString("recoveryState"))
            assertEquals("INTERRUPTED_PRE_COMMIT", settled.getString("lastRecoveryClassification"))

            assertTrue(
                KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(
                    directory,
                    lease
                )
            )
            assertFalse(KeplerJobMetadata.read(directory).has(PROCESSING_HANDOFF_OPERATION_ID))
        } finally {
            lease?.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun selfAcquiredHandoffSettlementDoesNotLeakLeaseWhenWriteFails() {
        val directory = Files.createTempDirectory("kepler-dispatch-handoff-leak-").toFile()
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(
                directory,
                JSONObject()
                    .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                    .put(PROCESSING_HANDOFF_OPERATION_ID, "handoff-operation")
                    .put(PROCESSING_HANDOFF_KIND, KeplerActiveOperationKind.PROCESSING_YUV.name)
            )
            KeplerJobMetadata.atomicWriteFailureForTest = IllegalStateException("handoff settlement failed")

            assertFalse(
                KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(directory)
            )
            assertFalse(KeplerJobMetadata.isOperationActive(directory))
            assertTrue(KeplerJobMetadata.read(directory).has(PROCESSING_HANDOFF_OPERATION_ID))

            KeplerJobMetadata.atomicWriteFailureForTest = null
            assertTrue(
                KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(directory)
            )
            assertFalse(KeplerJobMetadata.read(directory).has(PROCESSING_HANDOFF_OPERATION_ID))
            assertFalse(KeplerJobMetadata.isOperationActive(directory))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            directory.deleteRecursively()
        }
    }

    @Test
    fun selfAcquiredHandoffFatalSettlementDoesNotLeakLease() {
        val directory = Files.createTempDirectory("kepler-dispatch-handoff-fatal-leak-").toFile()
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(
                directory,
                JSONObject()
                    .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                    .put(PROCESSING_HANDOFF_OPERATION_ID, "handoff-operation")
                    .put(PROCESSING_HANDOFF_KIND, KeplerActiveOperationKind.PROCESSING_YUV.name)
            )
            KeplerJobMetadata.atomicWriteFailureForTest = AssertionError("fatal handoff settlement failed")

            assertThrows(AssertionError::class.java) {
                KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(directory)
            }
            assertFalse(KeplerJobMetadata.isOperationActive(directory))
            assertTrue(KeplerJobMetadata.read(directory).has(PROCESSING_HANDOFF_OPERATION_ID))

            KeplerJobMetadata.atomicWriteFailureForTest = null
            assertTrue(
                KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(directory)
            )
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            directory.deleteRecursively()
        }
    }

    @Test
    fun ownerSuppliedHandoffSettlementRetriesThroughNextProductionAcquisition() {
        val directory = Files.createTempDirectory("kepler-dispatch-handoff-owner-retry-").toFile()
        var lease: JobOperationLease? = null
        var replacement: JobOperationLease? = null
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(
                directory,
                JSONObject()
                    .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                    .put(PROCESSING_HANDOFF_OPERATION_ID, "handoff-operation")
                    .put(PROCESSING_HANDOFF_KIND, KeplerActiveOperationKind.PROCESSING_YUV.name)
            )
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.PROCESSING_START,
                consumesProcessingHandoff = true
            )
            KeplerJobMetadata.atomicWriteFailureForTest = IllegalStateException("handoff settlement failed")

            assertFalse(
                KeplerJobMetadata.settleUnconsumedProcessingHandoffAfterWorkerDispatchFailure(
                    directory,
                    requireNotNull(lease)
                )
            )
            assertTrue(KeplerJobMetadata.isOperationActive(directory))
            assertTrue(KeplerJobMetadata.read(directory).has(PROCESSING_HANDOFF_OPERATION_ID))

            KeplerJobMetadata.atomicWriteFailureForTest = null
            replacement = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.PROCESSING_START,
                consumesProcessingHandoff = true
            )
            assertFalse(KeplerJobMetadata.read(directory).has(PROCESSING_HANDOFF_OPERATION_ID))
            assertTrue(KeplerJobMetadata.isOperationOwner(directory, replacement!!))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            replacement?.release()
            lease?.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedHandoffPublicationRetainsExactCaptureOwner() {
        val directory = Files.createTempDirectory("kepler-handoff-publication-failure-").toFile()
        var lease: JobOperationLease? = null
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "CAPTURING"))
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.PROCESSING_START
            )
            val ownerLease = requireNotNull(lease)
            val operationId = KeplerJobMetadata.beginActiveOperation(
                directory,
                kind = KeplerActiveOperationKind.CAPTURE_YUV,
                ownerLease = ownerLease
            )
            KeplerJobMetadata.atomicWriteFailureForTest = AssertionError("fatal handoff write failed")
            assertThrows(AssertionError::class.java) {
                KeplerJobMetadata.publishProcessingHandoff(
                    directory,
                    operationId,
                    KeplerActiveOperationKind.PROCESSING_YUV
                )
            }
            assertTrue(KeplerJobMetadata.isOperationOwner(directory, lease!!))
            KeplerJobMetadata.atomicWriteFailureForTest = IllegalStateException("handoff write failed")
            assertFalse(
                KeplerJobMetadata.publishProcessingHandoff(
                    directory,
                    operationId,
                    KeplerActiveOperationKind.PROCESSING_YUV
                )
            )
            KeplerJobMetadata.atomicWriteFailureForTest = IllegalStateException("owner clear failed")
            assertFalse(
                KeplerJobMetadata.settleCaptureOwnerAfterHandoffFailure(
                    directory,
                    operationId,
                    lease!!
                )
            )
            assertTrue(KeplerJobMetadata.isOperationOwner(directory, lease!!))
            assertEquals(operationId, KeplerJobMetadata.read(directory).getString(ACTIVE_OPERATION_ID))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            lease?.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun processingAttemptRetainsOwnerWhenActiveClearFailsAndConvergesOnRetry() {
        val directory = Files.createTempDirectory("kepler-processing-release-failure-").toFile()
        var lease: JobOperationLease? = null
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val attempt = beginProcessingAttempt(directory, "CLASSIC_YUV")
            lease = requireNotNull(attempt.operationLease)
            KeplerJobMetadata.atomicWriteFailureForTest = IllegalStateException("active clear failed")

            attempt.releaseOwnedLease()

            assertTrue(KeplerJobMetadata.isOperationOwner(directory, lease))
            assertTrue(lease.isProcessingAttemptOwner(attempt.id))
            assertEquals(attempt.id, KeplerJobMetadata.read(directory).getString(ACTIVE_OPERATION_ID))
            assertFalse(lease.releaseIfProcessingSettled())
            assertTrue(KeplerJobMetadata.isOperationOwner(directory, lease))
            KeplerJobMetadata.atomicWriteFailureForTest = IllegalStateException("retry active clear failed")
            assertThrows(ProcessingAlreadyActiveException::class.java) {
                KeplerJobMetadata.acquireRecoveryCheckedOperation(
                    directory,
                    JobRecoveryMutationIntent.REPROCESS
                )
            }

            KeplerJobMetadata.atomicWriteFailureForTest = null
            val next = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.REPROCESS
            )
            assertTrue(KeplerJobMetadata.isOperationOwner(directory, next))
            next.release()
            assertFalse(KeplerJobMetadata.isOperationOwner(directory, lease))
            assertFalse(lease.isProcessingAttemptOwner(attempt.id))
            assertFalse(KeplerJobMetadata.read(directory).has(ACTIVE_OPERATION_ID))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            lease?.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun fatalProcessingActiveClearFailurePropagatesAndRetainsOwner() {
        val directory = Files.createTempDirectory("kepler-processing-release-fatal-").toFile()
        var lease: JobOperationLease? = null
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val attempt = beginProcessingAttempt(directory, "CLASSIC_YUV")
            lease = requireNotNull(attempt.operationLease)
            KeplerJobMetadata.atomicWriteFailureForTest = AssertionError("fatal active clear failed")

            assertThrows(AssertionError::class.java) { attempt.releaseOwnedLease() }
            assertTrue(KeplerJobMetadata.isOperationOwner(directory, lease))
            assertTrue(lease.isProcessingAttemptOwner(attempt.id))
            assertEquals(attempt.id, KeplerJobMetadata.read(directory).getString(ACTIVE_OPERATION_ID))

            KeplerJobMetadata.atomicWriteFailureForTest = null
            val next = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.REPROCESS
            )
            next.release()
            assertFalse(KeplerJobMetadata.isOperationOwner(directory, lease))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            lease?.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun captureErrorSettlementRetainsOwnerOnOrdinaryAndFatalClearFailure() {
        val directory = Files.createTempDirectory("kepler-capture-error-owner-").toFile()
        var lease: JobOperationLease? = null
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "CAPTURING"))
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.PROCESSING_START
            )
            val ownerLease = requireNotNull(lease)
            val operationId = KeplerJobMetadata.beginActiveOperation(
                directory,
                kind = KeplerActiveOperationKind.CAPTURE_YUV,
                ownerLease = ownerLease
            )

            KeplerJobMetadata.atomicWriteFailureForTest = IllegalStateException("capture error clear failed")
            assertFalse(
                KeplerJobMetadata.settleCaptureOwnerAfterHandoffFailure(
                    directory,
                    operationId,
                    ownerLease
                )
            )
            assertTrue(KeplerJobMetadata.isOperationOwner(directory, ownerLease))
            KeplerJobMetadata.atomicWriteFailureForTest = IllegalStateException("capture retry clear failed")
            assertThrows(ProcessingAlreadyActiveException::class.java) {
                KeplerJobMetadata.acquireRecoveryCheckedOperation(
                    directory,
                    JobRecoveryMutationIntent.JOB_DELETE
                )
            }

            KeplerJobMetadata.atomicWriteFailureForTest = AssertionError("fatal capture error clear failed")
            assertThrows(AssertionError::class.java) {
                KeplerJobMetadata.settleCaptureOwnerAfterHandoffFailure(
                    directory,
                    operationId,
                    ownerLease
                )
            }
            assertTrue(KeplerJobMetadata.isOperationOwner(directory, ownerLease))

            KeplerJobMetadata.atomicWriteFailureForTest = null
            val next = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.REPROCESS
            )
            next.release()
            lease = null
            assertFalse(KeplerJobMetadata.read(directory).has(ACTIVE_OPERATION_ID))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            lease?.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun recoveryCheckedAcquisitionSerializesCompetingMutations() {
        val directory = Files.createTempDirectory("kepler-atomic-owner-").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "COMPLETE"))
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val winnerHeld = CountDownLatch(1)
            val bothAttempted = CountDownLatch(2)
            val releaseWinner = CountDownLatch(1)
            val futures = (0 until 2).map {
                executor.submit<Boolean> {
                    ready.countDown()
                    start.await()
                    val acquired = runCatching {
                        KeplerJobMetadata.acquireRecoveryCheckedOperation(
                            directory,
                            JobRecoveryMutationIntent.JOB_DELETE
                        )
                    }.getOrNull()
                    bothAttempted.countDown()
                    if (acquired != null) {
                        winnerHeld.countDown()
                        releaseWinner.await()
                        acquired.release()
                        true
                    } else false
                }
            }
            ready.await()
            start.countDown()
            winnerHeld.await()
            bothAttempted.await()
            releaseWinner.countDown()
            assertEquals(1, futures.count { it.get() })
        } finally {
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun historicalMalformedExportAllowsNonDestructiveWorkButBlocksDeletion() {
        val directory = Files.createTempDirectory("kepler-historical-invalid-export-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject()
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/current")
                .put("recoveryState", "STABLE"))
            File(directory, ".export_tx_corrupt.json").writeText("not-json")

            assertEquals(
                JobRecoveryMutationGateOutcome.ALLOWED,
                KeplerJobMetadata.inspectRecoveryMutationGate(directory, JobRecoveryMutationIntent.REPROCESS)
            )
            assertEquals(
                JobRecoveryMutationGateOutcome.BLOCKED_INVALID_EXPORT_JOURNAL,
                KeplerJobMetadata.inspectRecoveryMutationGate(directory, JobRecoveryMutationIntent.JOB_DELETE)
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun leaseCannotReleaseWhileExactPublicExportOperationRemainsActive() {
        val directory = Files.createTempDirectory("kepler-exact-public-owner-").toFile()
        var lease: JobOperationLease? = null
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("currentPipelineStage", "PROCESSING"))
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.PROCESSING_START
            )
            val operationId = KeplerJobMetadata.beginActiveOperation(
                directory,
                operationId = "public-export-E",
                kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
                ownerLease = lease
            )

            assertEquals("public-export-E", operationId)
            assertEquals(operationId, lease.currentDurableOperationId())
            assertTrue(KeplerJobMetadata.isOperationOwner(directory, lease))
            assertFalse(lease.releaseIfProcessingSettled())
            assertTrue(KeplerJobMetadata.isOperationOwner(directory, lease))

            recordNormalPreCommitTerminal(
                jobDir = directory,
                attemptStatus = "FAILED",
                pipelineStage = "FAILED",
                processStatus = "EXPORT_FAILED_KEEPING_CACHE",
                reason = "test terminal",
                operationId = "processing-P",
                operationLease = lease
            )
            assertEquals("public-export-E", KeplerJobMetadata.read(directory).getString(TERMINAL_OPERATION_ID))

            assertTrue(KeplerJobMetadata.clearActiveOperationKind(
                directory,
                KeplerActiveOperationKind.PUBLIC_EXPORT,
                lease
            ))
            assertEquals(null, lease.currentDurableOperationId())
            assertTrue(lease.releaseIfProcessingSettled())
            assertFalse(KeplerJobMetadata.isOperationActive(directory))
        } finally {
            lease?.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun ownedPublicExportSettlementPreservesVerifiedJournalBeforeLeaseRelease() {
        val directory = Files.createTempDirectory("kepler-public-export-settlement-").toFile()
        var lease: JobOperationLease? = null
        try {
            KeplerJobMetadata.write(directory, JSONObject()
                .put("currentPipelineStage", "PROCESSING")
                .put("exportUri", "content://media/old-uri")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true))
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.PROCESSING_START
            )
            val operationId = KeplerJobMetadata.beginActiveOperation(
                directory,
                kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
                ownerLease = lease
            )
            val journal = MediaStoreExportJournal.create(
                directory,
                MediaStoreExportRole.MAIN_IMAGE,
                null,
                "result.jpg",
                "Pictures/Kepler",
                "image/jpeg",
                Uri.parse("content://media/external/images/media"),
                ownerOperationId = operationId
            )
            journal.transition(directory, MediaStoreExportState.ROW_INSERTED, "content://media/42")
                .transition(directory, MediaStoreExportState.CONTENT_WRITTEN)
                .transition(directory, MediaStoreExportState.PUBLIC_COMMITTED)
                .transition(directory, MediaStoreExportState.VERIFIED)

            settleOwnedPublicExportInterruption(
                directory,
                lease!!,
                "test interruption"
            )

            val settled = KeplerJobMetadata.read(directory)
            assertFalse(settled.has(ACTIVE_OPERATION_ID))
            assertEquals("PARTIAL", settled.getString("currentPipelineStage"))
            assertTrue(settled.getBoolean("galleryExportCommitted"))
            assertTrue(settled.getBoolean("exportVerified"))
            assertEquals("content://media/42", settled.getString("exportUri"))
            assertEquals("content://media/42", settled.getString("galleryPublicExportLinkage"))
            assertTrue(MediaStoreExportJournal.list(directory).single().terminalMetadataPersisted)
            assertEquals(
                CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
                publicExportInterruptionTerminalKind(
                    OwnedPublicExportEvidence("verified-operation", committed = true, verified = true, uri = "content://media/42"),
                    cancellationRequested = true
                )
            )
        } finally {
            lease?.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun publicExportSettlementFailureRetainsExactLeaseAndLeavesJournalUnacknowledged() {
        val directory = Files.createTempDirectory("kepler-public-export-settlement-failure-").toFile()
        var lease: JobOperationLease? = null
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("currentPipelineStage", "PROCESSING"))
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.PROCESSING_START
            )
            val operationId = KeplerJobMetadata.beginActiveOperation(
                directory,
                kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
                ownerLease = lease
            )
            val journal = MediaStoreExportJournal.create(
                directory,
                MediaStoreExportRole.MAIN_IMAGE,
                null,
                "result.jpg",
                "Pictures/Kepler",
                "image/jpeg",
                Uri.parse("content://media/external/images/media"),
                ownerOperationId = operationId
            ).transition(directory, MediaStoreExportState.VERIFIED, "content://media/new-uri")
            KeplerJobMetadata.atomicWriteFailureForTest = IllegalStateException("terminal metadata write failed")

            assertThrows(Exception::class.java) {
                settleOwnedPublicExportInterruption(directory, lease!!, "injected failure")
            }
            assertTrue(KeplerJobMetadata.isOperationOwner(directory, lease!!))
            assertEquals(operationId, KeplerJobMetadata.read(directory).getString(ACTIVE_OPERATION_ID))
            assertFalse(MediaStoreExportJournal.read(directory, MediaStoreExportJournal.fileFor(directory, journal.exportAttemptId)).terminalMetadataPersisted)

            // The original worker scope has ended.  The next real acquisition
            // must retry the specialized PUBLIC_EXPORT protocol before it can
            // reserve a new mutation lease.
            KeplerJobMetadata.atomicWriteFailureForTest = null
            val oldLease = lease!!
            val nextLease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.REPROCESS
            )
            lease = nextLease
            val settled = KeplerJobMetadata.read(directory)
            assertFalse(KeplerJobMetadata.isOperationOwner(directory, oldLease))
            assertTrue(KeplerJobMetadata.isOperationOwner(directory, nextLease))
            assertFalse(settled.has(ACTIVE_OPERATION_ID))
            assertEquals("content://media/new-uri", settled.getString("exportUri"))
            assertTrue(settled.getBoolean("galleryExportCommitted"))
            assertTrue(settled.getBoolean("exportVerified"))
            assertTrue(MediaStoreExportJournal.read(directory, MediaStoreExportJournal.fileFor(directory, journal.exportAttemptId)).terminalMetadataPersisted)
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            lease?.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun preCommitPublicExportSettlementDebtConvergesThroughNextAcquire() {
        val directory = Files.createTempDirectory("kepler-public-export-precommit-debt-").toFile()
        var lease: JobOperationLease? = null
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            val historical = File(directory, ".export_tx_historical-corrupt.json").apply {
                writeText("not-json")
                setLastModified(1L)
            }
            KeplerJobMetadata.write(directory, JSONObject()
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/old-uri"))
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.REPROCESS
            )
            KeplerJobMetadata.beginActiveOperation(
                directory,
                kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
                ownerLease = lease
            )
            KeplerJobMetadata.atomicWriteFailureForTest = IllegalStateException("pre-commit settlement failed")

            assertThrows(Exception::class.java) {
                settleOwnedPublicExportInterruption(
                    directory,
                    lease!!,
                    "cancelled before first insert",
                    disposition = PublicExportInterruptionDisposition.CANCELLED
                )
            }
            KeplerJobMetadata.atomicWriteFailureForTest = null
            val oldLease = lease!!
            val nextLease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.REPROCESS
            )
            lease = nextLease
            val settled = KeplerJobMetadata.read(directory)
            assertFalse(KeplerJobMetadata.isOperationOwner(directory, oldLease))
            assertFalse(settled.has(ACTIVE_OPERATION_ID))
            assertEquals("CANCELLED", settled.getString("currentPipelineStage"))
            assertEquals("EXPORT_CANCELLED_BEFORE_COMMIT", settled.getString("processStatus"))
            assertEquals("content://media/old-uri", settled.getString("exportUri"))
            assertTrue(settled.getBoolean("galleryExportCommitted"))
            assertTrue(settled.getBoolean("exportVerified"))
            assertTrue(historical.exists())
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            KeplerJobMetadata.atomicWriteFailureSequenceForTest = null
            lease?.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun publicExportSettlementDebtConvergesAfterJournalAckFailure() {
        val directory = Files.createTempDirectory("kepler-public-export-journal-debt-").toFile()
        var lease: JobOperationLease? = null
        val previousSequence = KeplerJobMetadata.atomicWriteFailureSequenceForTest
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("currentPipelineStage", "PROCESSING"))
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.PROCESSING_START
            )
            val operationId = KeplerJobMetadata.beginActiveOperation(
                directory,
                kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
                ownerLease = lease
            )
            val journal = MediaStoreExportJournal.create(
                directory,
                MediaStoreExportRole.MAIN_IMAGE,
                null,
                "result.jpg",
                "Pictures/Kepler",
                "image/jpeg",
                Uri.parse("content://media/external/images/media"),
                ownerOperationId = operationId
            ).transition(directory, MediaStoreExportState.VERIFIED, "content://media/journal-debt")
            KeplerJobMetadata.atomicWriteFailureSequenceForTest = mutableListOf<Throwable?>(
                null,
                IllegalStateException("journal terminal acknowledgement failed")
            )

            assertThrows(Exception::class.java) {
                settleOwnedPublicExportInterruption(directory, lease!!, "journal acknowledgement failure")
            }
            assertEquals(operationId, KeplerJobMetadata.read(directory).getString(ACTIVE_OPERATION_ID))
            assertFalse(MediaStoreExportJournal.read(directory, MediaStoreExportJournal.fileFor(directory, journal.exportAttemptId)).terminalMetadataPersisted)

            KeplerJobMetadata.atomicWriteFailureSequenceForTest = null
            val oldLease = lease!!
            val nextLease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.REPROCESS
            )
            lease = nextLease
            assertFalse(KeplerJobMetadata.isOperationOwner(directory, oldLease))
            assertFalse(KeplerJobMetadata.read(directory).has(ACTIVE_OPERATION_ID))
            assertTrue(MediaStoreExportJournal.read(directory, MediaStoreExportJournal.fileFor(directory, journal.exportAttemptId)).terminalMetadataPersisted)
        } finally {
            KeplerJobMetadata.atomicWriteFailureSequenceForTest = previousSequence
            lease?.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun publicExportSettlementDebtConvergesAfterActiveClearFailure() {
        val directory = Files.createTempDirectory("kepler-public-export-clear-debt-").toFile()
        var lease: JobOperationLease? = null
        val previousSequence = KeplerJobMetadata.atomicWriteFailureSequenceForTest
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("currentPipelineStage", "PROCESSING"))
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.PROCESSING_START
            )
            val operationId = KeplerJobMetadata.beginActiveOperation(
                directory,
                kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
                ownerLease = lease
            )
            val journal = MediaStoreExportJournal.create(
                directory,
                MediaStoreExportRole.MAIN_IMAGE,
                null,
                "result.jpg",
                "Pictures/Kepler",
                "image/jpeg",
                Uri.parse("content://media/external/images/media"),
                ownerOperationId = operationId
            ).transition(directory, MediaStoreExportState.VERIFIED, "content://media/clear-debt")
            KeplerJobMetadata.atomicWriteFailureSequenceForTest = mutableListOf<Throwable?>(
                null,
                null,
                IllegalStateException("active owner clear failed")
            )

            assertThrows(Exception::class.java) {
                settleOwnedPublicExportInterruption(directory, lease!!, "active clear failure")
            }
            assertEquals(operationId, KeplerJobMetadata.read(directory).getString(ACTIVE_OPERATION_ID))
            assertTrue(MediaStoreExportJournal.read(directory, MediaStoreExportJournal.fileFor(directory, journal.exportAttemptId)).terminalMetadataPersisted)

            KeplerJobMetadata.atomicWriteFailureSequenceForTest = null
            val oldLease = lease!!
            val nextLease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.REPROCESS
            )
            lease = nextLease
            assertFalse(KeplerJobMetadata.isOperationOwner(directory, oldLease))
            assertFalse(KeplerJobMetadata.read(directory).has(ACTIVE_OPERATION_ID))
        } finally {
            KeplerJobMetadata.atomicWriteFailureSequenceForTest = previousSequence
            lease?.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun historicalMalformedExportDoesNotPoisonZeroJournalPreCommitSettlement() {
        val directory = Files.createTempDirectory("kepler-public-export-zero-journal-").toFile()
        var lease: JobOperationLease? = null
        try {
            KeplerJobMetadata.write(directory, JSONObject()
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/old-uri"))
            val historical = File(directory, ".export_tx_historical-corrupt.json").apply {
                writeText("not-json")
                setLastModified(1L)
            }
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.PROCESSING_START
            )
            KeplerJobMetadata.beginActiveOperation(
                directory,
                kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
                ownerLease = lease
            )

            assertTrue(settleOwnedPublicExportInterruption(directory, lease!!, "cancelled before insert"))
            val settled = KeplerJobMetadata.read(directory)
            assertEquals("content://media/old-uri", settled.getString("exportUri"))
            assertTrue(settled.getBoolean("galleryExportCommitted"))
            assertTrue(settled.getBoolean("exportVerified"))
            assertFalse(settled.has(ACTIVE_OPERATION_ID))
            assertTrue(historical.exists())
        } finally {
            lease?.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun preCommitCancellationPersistsCancelledTruthAndPreservesPreviousExport() {
        val directory = Files.createTempDirectory("kepler-public-export-cancelled-").toFile()
        var lease: JobOperationLease? = null
        try {
            KeplerJobMetadata.write(directory, JSONObject()
                .put("currentPipelineStage", "COMPLETE")
                .put("galleryExportCommitted", true)
                .put("exportVerified", true)
                .put("exportUri", "content://media/old-uri"))
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.PROCESSING_START
            )
            KeplerJobMetadata.beginActiveOperation(
                directory,
                kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
                ownerLease = lease
            )

            settleOwnedPublicExportInterruption(
                directory,
                lease!!,
                "cancelled before insert",
                disposition = PublicExportInterruptionDisposition.CANCELLED
            )

            val settled = KeplerJobMetadata.read(directory)
            assertEquals("CANCELLED", settled.getString("currentPipelineStage"))
            assertEquals("EXPORT_CANCELLED_BEFORE_COMMIT", settled.getString("processStatus"))
            assertEquals("content://media/old-uri", settled.getString("exportUri"))
            assertTrue(settled.getBoolean("galleryExportCommitted"))
            assertTrue(settled.getBoolean("exportVerified"))
            assertFalse(settled.has(ACTIVE_OPERATION_ID))
            assertEquals(
                CameraPipelineEvent.Terminal.Kind.CANCELLED,
                publicExportInterruptionTerminalKind(null, cancellationRequested = true)
            )
        } finally {
            lease?.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun committedButUnverifiedCancellationPersistsPartialTruth() {
        val directory = Files.createTempDirectory("kepler-public-export-committed-").toFile()
        var lease: JobOperationLease? = null
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("currentPipelineStage", "PROCESSING"))
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.PROCESSING_START
            )
            val operationId = KeplerJobMetadata.beginActiveOperation(
                directory,
                kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
                ownerLease = lease
            )
            MediaStoreExportJournal.create(
                directory,
                MediaStoreExportRole.MAIN_IMAGE,
                null,
                "result.jpg",
                "Pictures/Kepler",
                "image/jpeg",
                Uri.parse("content://media/external/images/media"),
                ownerOperationId = operationId
            ).transition(directory, MediaStoreExportState.PUBLIC_COMMITTED, "content://media/new-uri")

            settleOwnedPublicExportInterruption(
                directory,
                lease!!,
                "cancelled after public commit",
                disposition = PublicExportInterruptionDisposition.CANCELLED
            )

            val settled = KeplerJobMetadata.read(directory)
            assertEquals("PARTIAL", settled.getString("currentPipelineStage"))
            assertEquals("EXPORT_COMMITTED_PENDING_VERIFICATION", settled.getString("processStatus"))
            assertEquals("content://media/new-uri", settled.getString("exportUri"))
            assertTrue(settled.getBoolean("galleryExportCommitted"))
            assertFalse(settled.getBoolean("exportVerified"))
            assertEquals(
                CameraPipelineEvent.Terminal.Kind.COMPLETE_PARTIAL,
                publicExportInterruptionTerminalKind(
                    OwnedPublicExportEvidence(operationId, committed = true, verified = false, uri = "content://media/new-uri"),
                    cancellationRequested = true
                )
            )
        } finally {
            lease?.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun equalTimestampMalformedExportRemainsCurrentSettlementEvidence() {
        val directory = Files.createTempDirectory("kepler-public-export-equal-timestamp-").toFile()
        var lease: JobOperationLease? = null
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("currentPipelineStage", "PROCESSING"))
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.PROCESSING_START
            )
            KeplerJobMetadata.beginActiveOperation(
                directory,
                kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
                ownerLease = lease
            )
            val startedAt = KeplerJobMetadata.read(directory).getLong(ACTIVE_OPERATION_STARTED_AT)
            val malformed = File(directory, ".export_tx_equal-corrupt.json").apply {
                writeText("not-json")
                setLastModified(startedAt)
            }

            assertThrows(IllegalStateException::class.java) {
                settleOwnedPublicExportInterruption(directory, lease!!, "equal timestamp")
            }
            assertTrue(KeplerJobMetadata.isOperationOwner(directory, lease!!))
            assertEquals("PUBLIC_EXPORT", KeplerJobMetadata.read(directory).getString(ACTIVE_OPERATION_KIND))
            assertTrue(malformed.exists())
        } finally {
            lease?.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun fatalPublicExportSettlementFailureRetainsExactLeaseAndDurableOwner() {
        val directory = Files.createTempDirectory("kepler-public-export-fatal-").toFile()
        var lease: JobOperationLease? = null
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("currentPipelineStage", "PROCESSING"))
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.PROCESSING_START
            )
            val operationId = KeplerJobMetadata.beginActiveOperation(
                directory,
                kind = KeplerActiveOperationKind.PUBLIC_EXPORT,
                ownerLease = lease
            )
            MediaStoreExportJournal.create(
                directory,
                MediaStoreExportRole.MAIN_IMAGE,
                null,
                "result.jpg",
                "Pictures/Kepler",
                "image/jpeg",
                Uri.parse("content://media/external/images/media"),
                ownerOperationId = operationId
            ).transition(directory, MediaStoreExportState.INSERT_FAILED_NO_ROW)
            KeplerJobMetadata.atomicWriteFailureForTest = AssertionError("fatal settlement failure")

            assertThrows(AssertionError::class.java) {
                settleOwnedPublicExportInterruption(directory, lease!!, "fatal")
            }
            assertTrue(KeplerJobMetadata.isOperationOwner(directory, lease!!))
            assertEquals(operationId, KeplerJobMetadata.read(directory).getString(ACTIVE_OPERATION_ID))
            assertNotNull(lease!!.pendingPublicExportSettlement())

            KeplerJobMetadata.atomicWriteFailureForTest = null
            val oldLease = lease!!
            val nextLease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                directory,
                JobRecoveryMutationIntent.REPROCESS
            )
            lease = nextLease
            assertFalse(KeplerJobMetadata.isOperationOwner(directory, oldLease))
            assertFalse(KeplerJobMetadata.read(directory).has(ACTIVE_OPERATION_ID))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            lease?.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun fatalPublicExportActiveKindClearPropagatesAndRetainsOwner() {
        val directory = Files.createTempDirectory("kepler-public-export-clear-kind-fatal-").toFile()
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(directory, JSONObject()
                .put(ACTIVE_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(ACTIVE_OPERATION_ID, "public-export-clear-kind-fatal")
                .put(ACTIVE_OPERATION_KIND, KeplerActiveOperationKind.PUBLIC_EXPORT.name))
            KeplerJobMetadata.atomicWriteFailureForTest = AssertionError("fatal public export active-kind clear")
            assertThrows(AssertionError::class.java) {
                KeplerJobMetadata.clearActiveOperationKind(directory, KeplerActiveOperationKind.PUBLIC_EXPORT)
            }
            val retained = KeplerJobMetadata.read(directory)
            assertEquals(KeplerActiveOperationKind.PUBLIC_EXPORT.name, retained.getString(ACTIVE_OPERATION_KIND))
            assertEquals("public-export-clear-kind-fatal", retained.getString(ACTIVE_OPERATION_ID))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            directory.deleteRecursively()
        }
    }

    @Test
    fun consumeProcessingHandoffClearsOnlyCorrelatedCurrentRuntimeHandoff() {
        val directory = Files.createTempDirectory("kepler-consume-handoff-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            KeplerJobMetadata.update(directory) {
                it.put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                    .put(PROCESSING_HANDOFF_OPERATION_ID, "handoff-1")
                    .put(PROCESSING_HANDOFF_KIND, KeplerActiveOperationKind.PROCESSING_YUV.name)
                it.put(PROCESSING_HANDOFF_CREATED_AT, 123L)
            }

            assertTrue(KeplerJobMetadata.consumeProcessingHandoff(directory, KeplerActiveOperationKind.PROCESSING_YUV))
            val consumed = KeplerJobMetadata.read(directory)
            assertFalse(consumed.has(PROCESSING_HANDOFF_OPERATION_ID))
            assertFalse(consumed.has(PROCESSING_HANDOFF_RUNTIME_SESSION_ID))
            assertFalse(consumed.has(PROCESSING_HANDOFF_KIND))
            assertFalse(consumed.has(PROCESSING_HANDOFF_CREATED_AT))
            assertFalse(KeplerJobMetadata.consumeProcessingHandoff(directory, KeplerActiveOperationKind.PROCESSING_YUV))

            KeplerJobMetadata.update(directory) {
                it.put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                    .put(PROCESSING_HANDOFF_OPERATION_ID, "handoff-2")
                    .put(PROCESSING_HANDOFF_KIND, KeplerActiveOperationKind.PROCESSING_RAW.name)
            }
            assertFalse(KeplerJobMetadata.consumeProcessingHandoff(directory, KeplerActiveOperationKind.PROCESSING_YUV))
            assertEquals(
                KeplerActiveOperationKind.PROCESSING_RAW.name,
                KeplerJobMetadata.read(directory).getString(PROCESSING_HANDOFF_KIND)
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun consumeProcessingHandoffRefusesForeignRuntimeHandoff() {
        val directory = Files.createTempDirectory("kepler-consume-handoff-foreign-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject()
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, "dead-runtime")
                .put(PROCESSING_HANDOFF_OPERATION_ID, "foreign-handoff")
                .put(PROCESSING_HANDOFF_KIND, KeplerActiveOperationKind.PROCESSING_YUV.name))
            assertFalse(KeplerJobMetadata.consumeProcessingHandoff(directory, KeplerActiveOperationKind.PROCESSING_YUV))
            assertEquals(
                "foreign-handoff",
                KeplerJobMetadata.read(directory).getString(PROCESSING_HANDOFF_OPERATION_ID)
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun consumeProcessingHandoffWriteFaultRetriesIdempotently() {
        val directory = Files.createTempDirectory("kepler-consume-handoff-write-fault-").toFile()
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(directory, JSONObject()
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "handoff-1")
                .put(PROCESSING_HANDOFF_KIND, KeplerActiveOperationKind.PROCESSING_YUV.name))
            KeplerJobMetadata.atomicWriteFailureForTest = IllegalStateException("consume write failed")

            assertFalse(KeplerJobMetadata.consumeProcessingHandoff(directory, KeplerActiveOperationKind.PROCESSING_YUV))
            assertTrue(KeplerJobMetadata.read(directory).has(PROCESSING_HANDOFF_OPERATION_ID))

            assertTrue(KeplerJobMetadata.consumeProcessingHandoff(directory, KeplerActiveOperationKind.PROCESSING_YUV))
            assertFalse(KeplerJobMetadata.read(directory).has(PROCESSING_HANDOFF_OPERATION_ID))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            directory.deleteRecursively()
        }
    }

    @Test
    fun consumedSourceHandoffAllowsLaterReprocessAcquisition() {
        val directory = Files.createTempDirectory("kepler-consume-handoff-reprocess-").toFile()
        var lease: JobOperationLease? = null
        try {
            KeplerJobMetadata.write(directory, JSONObject()
                .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                .put(PROCESSING_HANDOFF_OPERATION_ID, "source-handoff")
                .put(PROCESSING_HANDOFF_KIND, KeplerActiveOperationKind.PROCESSING_YUV.name))
            assertThrows(JobRecoveryMutationBlockedException::class.java) {
                KeplerJobMetadata.acquireRecoveryCheckedOperation(directory, JobRecoveryMutationIntent.REPROCESS)
            }

            assertTrue(KeplerJobMetadata.consumeProcessingHandoff(directory, KeplerActiveOperationKind.PROCESSING_YUV))
            lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(directory, JobRecoveryMutationIntent.REPROCESS)
            assertFalse(KeplerJobMetadata.read(directory).has(PROCESSING_HANDOFF_OPERATION_ID))
        } finally {
            lease?.release()
            directory.deleteRecursively()
        }
    }
}
