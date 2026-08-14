package com.projectnuke.keplernightlab

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
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
}
