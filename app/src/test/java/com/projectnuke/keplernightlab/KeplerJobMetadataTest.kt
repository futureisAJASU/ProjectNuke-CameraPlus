package com.projectnuke.keplernightlab

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            lease?.release()
            directory.deleteRecursively()
        }
    }
}
