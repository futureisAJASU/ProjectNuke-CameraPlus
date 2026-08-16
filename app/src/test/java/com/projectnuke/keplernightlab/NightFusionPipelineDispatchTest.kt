package com.projectnuke.keplernightlab

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NightFusionPipelineDispatchTest {
    @Test
    fun fatalLatestNightFusionWorkerDispatchFailurePropagates() {
        assertThrows(AssertionError::class.java) {
            processLatestNightFusionV02(
                context = RuntimeEnvironment.getApplication(),
                onStatus = {},
                workerPostOperation = { throw AssertionError("fatal worker dispatch") }
            )
        }
    }

    @Test
    fun rejectedInitialWorkerPostCompletesTerminalWithoutHanging() = runBlocking {
        val dir = Files.createTempDirectory("yuv-reprocess-dispatch").toFile()
        try {
            val run = reprocessYuvJob(
                context = RuntimeEnvironment.getApplication(),
                jobDir = dir,
                finalOutputFormat = FinalOutputFormat.JPEG,
                workerPostOperation = { false },
                onStatus = {}
            )
            val outcome = run.terminal.await()
            assertFalse(outcome.result.isSuccess)
            assertTrue(outcome.terminalError is IllegalStateException)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun yuvSetupFailureConsumesPublishedHandoffAndConverges() {
        val dir = Files.createTempDirectory("yuv-setup-failure-consumption").toFile()
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "YUV_NIGHT_FUSION"))
            KeplerJobMetadata.update(dir) {
                it.put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                    .put(PROCESSING_HANDOFF_OPERATION_ID, "capture-handoff")
                    .put(PROCESSING_HANDOFF_KIND, KeplerActiveOperationKind.PROCESSING_YUV.name)
            }

            persistYuvCaptureSetupFailure(
                jobDir = dir,
                source = "test.setup",
                failure = IllegalStateException("camera setup failed")
            )

            val terminal = KeplerJobMetadata.read(dir)
            assertEquals("FAILED", terminal.getString("currentPipelineStage"))
            assertEquals("PIPELINE_FAILED", terminal.getString("processStatus"))
            assertTrue(terminal.getBoolean("pipelineFailed"))
            assertTrue(terminal.getString(TERMINAL_OPERATION_ID).isNotBlank())
            assertFalse(terminal.has(ACTIVE_OPERATION_ID))
            assertFalse(terminal.has(PROCESSING_HANDOFF_OPERATION_ID))
            assertFalse(terminal.has(PROCESSING_HANDOFF_RUNTIME_SESSION_ID))
            assertFalse(KeplerJobMetadata.isOperationActive(dir))

            val next = KeplerJobMetadata.acquireRecoveryCheckedOperation(
                dir,
                JobRecoveryMutationIntent.REPROCESS
            )
            next.release()
            assertFalse(KeplerJobMetadata.read(dir).has(PROCESSING_HANDOFF_OPERATION_ID))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun yuvSetupFailureOperationWriteFaultConsumesNothingAndConvergesOnRetry() {
        val dir = Files.createTempDirectory("yuv-setup-failure-oper-write").toFile()
        val previousFailure = KeplerJobMetadata.atomicWriteFailureForTest
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "YUV_NIGHT_FUSION"))
            KeplerJobMetadata.update(dir) {
                it.put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                    .put(PROCESSING_HANDOFF_OPERATION_ID, "capture-handoff")
                    .put(PROCESSING_HANDOFF_KIND, KeplerActiveOperationKind.PROCESSING_YUV.name)
            }
            KeplerJobMetadata.atomicWriteFailureForTest =
                IllegalStateException("setup operation write failed")

            assertThrows(IllegalStateException::class.java) {
                persistYuvCaptureSetupFailure(
                    jobDir = dir,
                    source = "test.setup",
                    failure = IllegalStateException("camera setup failed")
                )
            }
            assertFalse(KeplerJobMetadata.isOperationActive(dir))
            assertFalse(KeplerJobMetadata.read(dir).has(ACTIVE_OPERATION_ID))
            assertTrue(KeplerJobMetadata.read(dir).has(PROCESSING_HANDOFF_OPERATION_ID))

            persistYuvCaptureSetupFailure(
                jobDir = dir,
                source = "test.setup",
                failure = IllegalStateException("camera setup failed")
            )
            val terminal = KeplerJobMetadata.read(dir)
            assertFalse(terminal.has(PROCESSING_HANDOFF_OPERATION_ID))
            assertEquals("FAILED", terminal.getString("currentPipelineStage"))
            assertFalse(KeplerJobMetadata.isOperationActive(dir))
        } finally {
            KeplerJobMetadata.atomicWriteFailureForTest = previousFailure
            dir.deleteRecursively()
        }
    }

    @Test
    fun yuvSetupFailureTerminalWriteFaultRetainsDebtAndConvergesThroughReconcile() {
        val dir = Files.createTempDirectory("yuv-setup-failure-terminal-write").toFile()
        val previousSequence = KeplerJobMetadata.atomicWriteFailureSequenceForTest
        try {
            KeplerJobMetadata.write(dir, JSONObject().put("jobType", "YUV_NIGHT_FUSION"))
            KeplerJobMetadata.update(dir) {
                it.put(PROCESSING_HANDOFF_RUNTIME_SESSION_ID, KeplerRuntimeSession.id)
                    .put(PROCESSING_HANDOFF_OPERATION_ID, "capture-handoff")
                    .put(PROCESSING_HANDOFF_KIND, KeplerActiveOperationKind.PROCESSING_YUV.name)
            }
            KeplerJobMetadata.atomicWriteFailureSequenceForTest =
                mutableListOf(null, IllegalStateException("terminal write failed"))

            assertThrows(IllegalStateException::class.java) {
                persistYuvCaptureSetupFailure(
                    jobDir = dir,
                    source = "test.setup",
                    failure = IllegalStateException("camera setup failed")
                )
            }
            assertTrue(KeplerJobMetadata.isOperationActive(dir))
            val mid = KeplerJobMetadata.read(dir)
            assertFalse(mid.has(PROCESSING_HANDOFF_OPERATION_ID))
            assertTrue(mid.has(ACTIVE_OPERATION_ID))
            assertEquals(KeplerActiveOperationKind.PROCESSING_YUV.name, mid.getString(ACTIVE_OPERATION_KIND))

            persistYuvCaptureSetupFailure(
                jobDir = dir,
                source = "test.setup",
                failure = IllegalStateException("camera setup failed")
            )
            val terminal = KeplerJobMetadata.read(dir)
            assertFalse(terminal.has(ACTIVE_OPERATION_ID))
            assertFalse(terminal.has(PROCESSING_HANDOFF_OPERATION_ID))
            assertEquals("FAILED", terminal.getString("currentPipelineStage"))
            assertFalse(KeplerJobMetadata.isOperationActive(dir))
        } finally {
            KeplerJobMetadata.atomicWriteFailureSequenceForTest = previousSequence
            dir.deleteRecursively()
        }
    }
}
