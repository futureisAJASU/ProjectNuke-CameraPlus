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
        val operation = acquireRawProcessingOperation(dir)
        assertNotNull(operation)
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "RAW_CAPTURE"))
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
            nested!!.release()

            assertTrue(KeplerJobMetadata.isOperationOwner(dir, outer!!))
            val competing = KeplerJobMetadata.acquireOperation(dir)
            assertFalse("outer RAW reprocess operation was released too early", competing != null)
            competing?.release()
        } finally {
            outer!!.release()
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
            assertThrows(JobRecoveryMutationBlockedException::class.java) {
                KeplerJobMetadata.acquireRecoveryCheckedOperation(
                    dir,
                    JobRecoveryMutationIntent.REPROCESS
                )
            }

            KeplerJobMetadata.atomicWriteFailureForTest = null
            scope.release()
            assertFalse(KeplerJobMetadata.isOperationOwner(dir, scope.lease))
            assertFalse(KeplerJobMetadata.read(dir).has(ACTIVE_OPERATION_ID))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
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
            scope.release()
            assertFalse(KeplerJobMetadata.isOperationOwner(dir, scope.lease))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            dir.deleteRecursively()
        }
    }
}
