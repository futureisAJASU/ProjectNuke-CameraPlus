package com.projectnuke.keplernightlab

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RawProcessingOperationTest {
    @Test
    fun workerDispatchFailureRetainsOuterOperationThroughTerminalMetadata() {
        val dir = Files.createTempDirectory("raw-worker-dispatch-failure").toFile()
        KeplerJobMetadata.write(dir, JSONObject().put("jobType", "RAW_CAPTURE"))
        val operation = acquireRawProcessingOperation(dir)
        assertNotNull(operation)
        try {
            var callbackPublished = false
            recordRawOuterTerminalFailureWhileOwned(
                jobDir = dir,
                operation = operation!!,
                reason = "RAW processing worker could not start",
                beforeMetadata = {
                    val competing = KeplerJobMetadata.acquireOperation(dir)
                    assertNull("new RAW operation started before terminal metadata", competing)
                    competing?.release()
                }
            ) {
                callbackPublished = true
            }
            assertTrue(callbackPublished)
            val terminal = KeplerJobMetadata.read(dir)
            assertEquals("FAILED", terminal.getString("rawPublicExportAttemptStatus"))
            assertEquals("FAILED", terminal.getString("currentPipelineStage"))
            assertEquals("EXPORT_FAILED_KEEPING_CACHE", terminal.getString("processStatus"))
        } finally {
            operation!!.release()
        }

        val next = KeplerJobMetadata.acquireOperation(dir)
        assertNotNull("next RAW operation must start after terminal settlement", next)
        try {
            KeplerJobMetadata.update(dir) { it.put("nextOperationMarker", "B") }
            assertEquals("B", KeplerJobMetadata.read(dir).getString("nextOperationMarker"))
        } finally {
            next!!.release()
            dir.deleteRecursively()
        }
    }

    @Test
    fun borrowedRawWrapperScopeDoesNotReleaseOuterOperation() {
        val dir = Files.createTempDirectory("raw-processing-operation").toFile()
        val outer = KeplerJobMetadata.acquireOperation(dir)
        assertNotNull(outer)
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "RAW_REPROCESS"))
            val nested = acquireRawProcessingOperation(dir, outer)
            assertNotNull(nested)
            val processingId = KeplerJobMetadata.read(dir).getString(ACTIVE_OPERATION_ID)
            assertEquals(KeplerActiveOperationKind.PROCESSING_RAW.name,
                KeplerJobMetadata.read(dir).getString(ACTIVE_OPERATION_KIND))
            nested!!.release()

            assertTrue(KeplerJobMetadata.isOperationOwner(dir, outer!!))
            assertEquals(processingId, KeplerJobMetadata.read(dir).getString(ACTIVE_OPERATION_ID))
            val competing = KeplerJobMetadata.acquireOperation(dir)
            assertFalse("outer RAW reprocess operation was released too early", competing != null)
            competing?.release()
        } finally {
            outer!!.release()
            dir.deleteRecursively()
        }
    }

    @Test
    fun rawPublicExportReassertsTheSameDurableOperationId() {
        val dir = Files.createTempDirectory("raw-processing-public-export-id").toFile()
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "RAW_CAPTURE"))
            val operation = requireNotNull(acquireRawProcessingOperation(dir))
            val processingId = KeplerJobMetadata.read(dir).getString(ACTIVE_OPERATION_ID)
            assertEquals(processingId, operation.lease.currentDurableOperationId())

            operation.reassertActiveOperation(KeplerActiveOperationKind.PUBLIC_EXPORT)

            val publicExportMetadata = KeplerJobMetadata.read(dir)
            assertEquals(processingId, publicExportMetadata.getString(ACTIVE_OPERATION_ID))
            assertEquals(
                KeplerActiveOperationKind.PUBLIC_EXPORT.name,
                publicExportMetadata.getString(ACTIVE_OPERATION_KIND)
            )
            assertEquals(processingId, operation.lease.currentDurableOperationId())
            operation.release()
            assertFalse(KeplerJobMetadata.read(dir).has(ACTIVE_OPERATION_ID))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ownedRawWrapperScopeReleasesExactlyOnce() {
        val dir = Files.createTempDirectory("raw-processing-operation-owned").toFile()
        try {
            val scope = acquireRawProcessingOperation(dir)
            assertNotNull(scope)
            scope!!.release()
            scope.release()
            assertNotNull(KeplerJobMetadata.acquireOperation(dir)?.also { it.release() })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rawWrapperRetainsDurableOwnerWhenClearFailsAndRetries() {
        val dir = Files.createTempDirectory("raw-processing-owner-clear-failure").toFile()
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "RAW_CAPTURE"))
            val scope = requireNotNull(acquireRawProcessingOperation(dir))
            KeplerJobMetadata.atomicWriteFailureForTest = IllegalStateException("RAW owner clear failed")

            scope.release()

            assertTrue(KeplerJobMetadata.isOperationOwner(dir, scope.lease))
            assertNotNull(KeplerJobMetadata.read(dir).optString(ACTIVE_OPERATION_ID).takeIf { it.isNotBlank() })
            KeplerJobMetadata.atomicWriteFailureForTest = IllegalStateException("RAW retry clear failed")
            assertThrows(ProcessingAlreadyActiveException::class.java) {
                KeplerJobMetadata.acquireRecoveryCheckedOperation(
                    dir,
                    JobRecoveryMutationIntent.REPROCESS
                )
             }

            KeplerJobMetadata.atomicWriteFailureForTest = null
            val next = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                dir,
                JobRecoveryMutationIntent.REPROCESS
            )
            next.release()
            assertFalse(KeplerJobMetadata.isOperationOwner(dir, scope.lease))
            assertFalse(KeplerJobMetadata.read(dir).has(ACTIVE_OPERATION_ID))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            dir.deleteRecursively()
        }
    }

    @Test
    fun rawProcessingAcquisitionConsumesCorrelatedCaptureHandoffExactlyOnce() {
        val dir = Files.createTempDirectory("raw-acquire-consumes-handoff").toFile()
        var captureLease: JobOperationLease? = null
        var processingOperation: RawProcessingOperation? = null
        var retry: RawProcessingOperation? = null
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "RAW_CAPTURE"))
            captureLease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                dir,
                JobRecoveryMutationIntent.PROCESSING_START
            )
            val captureOperationId = KeplerJobMetadata.beginActiveOperation(
                dir,
                kind = KeplerActiveOperationKind.CAPTURE_RAW,
                ownerLease = captureLease
            )
            assertTrue(
                KeplerJobMetadata.publishProcessingHandoff(
                    dir,
                    captureOperationId,
                    KeplerActiveOperationKind.PROCESSING_RAW
                )
            )
            assertTrue(KeplerJobMetadata.clearActiveOperation(dir, captureOperationId))
            assertFalse(KeplerJobMetadata.read(dir).has(ACTIVE_OPERATION_ID))
            assertTrue(KeplerJobMetadata.read(dir).has(PROCESSING_HANDOFF_OPERATION_ID))
            captureLease!!.release()
            captureLease = null

            processingOperation = acquireRawProcessingOperation(dir)
            assertNotNull(processingOperation)
            val processingId = KeplerJobMetadata.read(dir).getString(ACTIVE_OPERATION_ID)
            assertEquals(
                KeplerActiveOperationKind.PROCESSING_RAW.name,
                KeplerJobMetadata.read(dir).getString(ACTIVE_OPERATION_KIND)
            )
            assertFalse(KeplerJobMetadata.read(dir).has(PROCESSING_HANDOFF_OPERATION_ID))
            assertFalse(KeplerJobMetadata.read(dir).has(PROCESSING_HANDOFF_RUNTIME_SESSION_ID))
            assertFalse(KeplerJobMetadata.read(dir).has(PROCESSING_HANDOFF_CREATED_AT))
            assertEquals(processingId, processingOperation!!.operationId)
            assertEquals(processingId, processingOperation!!.lease.currentDurableOperationId())
            assertTrue(KeplerJobMetadata.isOperationOwner(dir, processingOperation!!.lease))

            processingOperation!!.release()
            assertFalse(KeplerJobMetadata.isOperationActive(dir))
            assertFalse(KeplerJobMetadata.read(dir).has(ACTIVE_OPERATION_ID))

            retry = acquireRawProcessingOperation(dir)
            assertNotNull("a later RAW operation must start after handoff consumption", retry)
            assertFalse(KeplerJobMetadata.read(dir).has(PROCESSING_HANDOFF_OPERATION_ID))
        } finally {
            retry?.release()
            processingOperation?.release()
            captureLease?.release()
            dir.deleteRecursively()
        }
    }

    @Test
    fun rawProcessingAcquisitionPreservesMismatchedKindHandoffForItsConsumer() {
        val dir = Files.createTempDirectory("raw-acquire-mismatched-handoff").toFile()
        var processingOperation: RawProcessingOperation? = null
        try {
            KeplerJobMetadata.write(
                dir,
                JSONObject()
                    .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                    .put(PROCESSING_HANDOFF_OPERATION_ID, "yuv-handoff")
                    .put(PROCESSING_HANDOFF_KIND, KeplerActiveOperationKind.PROCESSING_YUV.name)
            )
            processingOperation = acquireRawProcessingOperation(dir)
            assertNotNull(processingOperation)
            assertEquals(
                KeplerActiveOperationKind.PROCESSING_RAW.name,
                KeplerJobMetadata.read(dir).getString(ACTIVE_OPERATION_KIND)
            )
            assertEquals(
                "yuv-handoff",
                KeplerJobMetadata.read(dir).getString(PROCESSING_HANDOFF_OPERATION_ID)
            )
            assertEquals(
                KeplerActiveOperationKind.PROCESSING_YUV.name,
                KeplerJobMetadata.read(dir).getString(PROCESSING_HANDOFF_KIND)
            )
        } finally {
            processingOperation?.release()
            dir.deleteRecursively()
        }
    }

    @Test
    fun rawProcessingAcquisitionBlocksOnForeignRuntimeHandoff() {
        val dir = Files.createTempDirectory("raw-acquire-foreign-handoff").toFile()
        try {
            KeplerJobMetadata.write(
                dir,
                JSONObject()
                    .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, "dead-runtime")
                    .put(PROCESSING_HANDOFF_OPERATION_ID, "foreign-handoff")
                    .put(PROCESSING_HANDOFF_KIND, KeplerActiveOperationKind.PROCESSING_RAW.name)
            )
            assertThrows(JobRecoveryMutationBlockedException::class.java) {
                acquireRawProcessingOperation(dir)
            }
            assertFalse(KeplerJobMetadata.isOperationActive(dir))
            assertEquals(
                "foreign-handoff",
                KeplerJobMetadata.read(dir).getString(PROCESSING_HANDOFF_OPERATION_ID)
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rawProcessingAcquisitionWriteFailureConsumesNothingAndRetriesAfterRecovery() {
        val dir = Files.createTempDirectory("raw-acquire-handoff-write-failure").toFile()
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        var processingOperation: RawProcessingOperation? = null
        try {
            KeplerJobMetadata.write(
                dir,
                JSONObject()
                    .put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                    .put(PROCESSING_HANDOFF_OPERATION_ID, "handoff-operation")
                    .put(PROCESSING_HANDOFF_KIND, KeplerActiveOperationKind.PROCESSING_RAW.name)
            )
            KeplerJobMetadata.atomicWriteFailureForTest =
                IllegalStateException("RAW handoff consumption write failed")

            assertThrows(IllegalStateException::class.java) {
                acquireRawProcessingOperation(dir)
            }
            assertFalse(KeplerJobMetadata.isOperationActive(dir))
            assertFalse(KeplerJobMetadata.read(dir).has(ACTIVE_OPERATION_ID))
            assertTrue(KeplerJobMetadata.read(dir).has(PROCESSING_HANDOFF_OPERATION_ID))

            processingOperation = acquireRawProcessingOperation(dir)
            assertNotNull(processingOperation)
            assertFalse(KeplerJobMetadata.read(dir).has(PROCESSING_HANDOFF_OPERATION_ID))
            assertEquals(
                KeplerActiveOperationKind.PROCESSING_RAW.name,
                KeplerJobMetadata.read(dir).getString(ACTIVE_OPERATION_KIND)
            )
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            processingOperation?.release()
            dir.deleteRecursively()
        }
    }

    @Test
    fun rawWrapperFatalOwnerClearFailurePropagatesAndRetainsOwner() {
        val dir = Files.createTempDirectory("raw-processing-owner-clear-fatal").toFile()
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "RAW_CAPTURE"))
            val scope = requireNotNull(acquireRawProcessingOperation(dir))
            KeplerJobMetadata.atomicWriteFailureForTest = AssertionError("fatal RAW owner clear failed")

            assertThrows(AssertionError::class.java) { scope.release() }
            assertTrue(KeplerJobMetadata.isOperationOwner(dir, scope.lease))
            assertNotNull(KeplerJobMetadata.read(dir).optString(ACTIVE_OPERATION_ID).takeIf { it.isNotBlank() })

            KeplerJobMetadata.atomicWriteFailureForTest = null
            // Mark reconciliation ready: the original owner has finished its work
            scope.lease.markReconciliationReady()
            val next = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                dir,
                JobRecoveryMutationIntent.REPROCESS
            )
            next.release()
            assertFalse(KeplerJobMetadata.isOperationOwner(dir, scope.lease))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            dir.deleteRecursively()
        }
    }
}
