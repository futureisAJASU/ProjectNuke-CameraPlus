package com.projectnuke.keplernightlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class RawFusionCaptureOwnerExitTest {

    @Test
    fun rawCaptureFatalHandoffPublication_retainsExactReadyLease() {
        val directory = captureJobDirectory()
        try {
            val (operationId, lease) = captureOwner(directory)
            val publicationFailure = AssertionError("handoff publication failed")

            assertThrows(AssertionError::class.java) {
                settleRawCaptureHandoffOwnerExit(
                    lease,
                    publishHandoff = {
                        assertTrue(
                            KeplerJobMetadata.publishProcessingHandoff(
                                directory,
                                operationId,
                                KeplerActiveOperationKind.PROCESSING_RAW
                            )
                        )
                        throw publicationFailure
                    },
                    settleOwner = {
                        KeplerJobMetadata.settleCaptureOwnerAfterHandoffFailure(
                            directory,
                            operationId,
                            lease
                        )
                    }
                )
            }

            assertSame(lease, KeplerJobMetadata.findOperationLease(directory))
            assertTrue(lease.hasPendingProcessingHandoffSettlement())
            assertTrue(lease.isReconciliationReady())
            assertFalse(KeplerJobMetadata.isCurrentActiveOperation(directory, operationId))
            convergeProcessingHandoff(directory, lease)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rawCaptureFatalHandoffPublication_ownerClearSucceeds_doesNotDirectRelease() {
        val directory = captureJobDirectory()
        try {
            val (operationId, lease) = captureOwner(directory)
            val publicationFailure = AssertionError("uncertain handoff publication")
            var ownerSettlementCalled = false

            assertThrows(AssertionError::class.java) {
                settleRawCaptureHandoffOwnerExit(
                    lease,
                    publishHandoff = {
                        assertTrue(
                            KeplerJobMetadata.publishProcessingHandoff(
                                directory,
                                operationId,
                                KeplerActiveOperationKind.PROCESSING_RAW
                            )
                        )
                        throw publicationFailure
                    },
                    settleOwner = {
                        ownerSettlementCalled = true
                        KeplerJobMetadata.settleCaptureOwnerAfterHandoffFailure(
                            directory,
                            operationId,
                            lease
                        )
                    }
                )
            }

            assertTrue(ownerSettlementCalled)
            assertSame(lease, KeplerJobMetadata.findOperationLease(directory))
            assertTrue(lease.hasPendingProcessingHandoffSettlement())
            assertTrue(lease.isReconciliationReady())
            convergeProcessingHandoff(directory, lease)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rawCaptureFatalHandoffAndOwnerClearFailure_combinesFailuresAndRetainsReadyLease() {
        val directory = captureJobDirectory()
        try {
            val (operationId, lease) = captureOwner(directory)
            val publicationFailure = AssertionError("handoff publication failed")
            val ownerFailure = AssertionError("capture owner clear failed")

            val thrown = assertThrows(AssertionError::class.java) {
                settleRawCaptureHandoffOwnerExit(
                    lease,
                    publishHandoff = {
                        assertTrue(
                            KeplerJobMetadata.publishProcessingHandoff(
                                directory,
                                operationId,
                                KeplerActiveOperationKind.PROCESSING_RAW
                            )
                        )
                        throw publicationFailure
                    },
                    settleOwner = { throw ownerFailure }
                )
            }

            assertSame(publicationFailure, thrown)
            assertTrue(thrown.suppressed.any { it === ownerFailure })
            assertSame(lease, KeplerJobMetadata.findOperationLease(directory))
            assertTrue(lease.hasPendingProcessingHandoffSettlement())
            assertTrue(lease.isReconciliationReady())
            convergeProcessingHandoff(directory, lease)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun captureJobDirectory(): File = Files.createTempDirectory("raw-capture-owner-exit-").toFile().also {
        KeplerJobMetadata.write(it, JSONObject().put("status", "COMPLETE"))
    }

    private fun captureOwner(directory: File): Pair<String, JobOperationLease> {
        val lease = KeplerJobMetadata.acquireRecoveryCheckedOperation(
            directory,
            JobRecoveryMutationIntent.PROCESSING_START
        )
        val operationId = UUID.randomUUID().toString()
        KeplerJobMetadata.beginActiveOperation(
            directory,
            operationId = operationId,
            kind = KeplerActiveOperationKind.CAPTURE_RAW,
            ownerLease = lease
        )
        return operationId to lease
    }

    private fun convergeProcessingHandoff(directory: File, originalLease: JobOperationLease) {
        val recovered = KeplerJobMetadata.acquireRecoveryCheckedOperation(
            directory,
            JobRecoveryMutationIntent.PROCESSING_START,
            consumesProcessingHandoff = true
        )
        assertNotSame(originalLease, recovered)
        assertSame(recovered, KeplerJobMetadata.findOperationLease(directory))
        assertEquals(false, originalLease.hasPendingReconciliationDebt())
        recovered.release()
    }
}
