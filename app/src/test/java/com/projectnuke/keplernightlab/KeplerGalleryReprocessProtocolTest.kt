package com.projectnuke.keplernightlab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class KeplerGalleryReprocessProtocolTest {
    @Test
    fun backupTransactionKeepsJobJsonOnlyOnce() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            val frameFile = File(directory, "frame_0001.png")
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            frameFile.writeText("frame")

            val backups = backupReprocessTransaction(
                directory,
                listOf(File(directory, JOB_JSON_FILE_NAME), File(directory, JOB_JSON_FILE_NAME), frameFile, File(directory, JOB_JSON_FILE_NAME))
            ).getOrThrow()

            assertEquals(1, backups.count { it.original.name == JOB_JSON_FILE_NAME })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rollbackWaitsForWorkerCompletionBeforeStarting() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source = File(directory, "final.png")
            source.writeText("original")
            val backups = backupReprocessTransaction(directory, listOf(source)).getOrThrow()

            val workerCompletion = CompletableDeferred<ReprocessWorkerOutcome>()
            val cancelRequested = CompletableDeferred<Unit>()
            val rollbackStarted = CompletableDeferred<Unit>()

            val result = async {
                cancelWorkerAndRollbackAfterCompletion(
                    ReprocessWorkerRun(
                        terminal = workerCompletion,
                        cancel = { cancelRequested.complete(Unit) }
                    )
                ) {
                    rollbackStarted.complete(Unit)
                    restoreBackups(directory, backups)
                }
            }

            yield()
            assertTrue(cancelRequested.isCompleted)
            assertFalse(rollbackStarted.isCompleted)

            workerCompletion.complete(ReprocessWorkerOutcome(Result.failure(IllegalStateException("cancelled")), false))
            assertTrue(result.await().isSuccess)
            assertTrue(rollbackStarted.isCompleted)
            assertEquals("original", source.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun lateWorkerWriteIsRolledBackAfterCompletionSignal() = runBlocking {
        val directory = Files.createTempDirectory("kepler-reprocess-").toFile()
        try {
            KeplerJobMetadata.write(directory, JSONObject().put("status", "PROCESSING"))
            val source = File(directory, "final.png")
            source.writeText("original")
            val backups = backupReprocessTransaction(directory, listOf(source)).getOrThrow()

            val workerCompletion = CompletableDeferred<ReprocessWorkerOutcome>()
            val cancelRequested = CompletableDeferred<Unit>()

            val result = async {
                cancelWorkerAndRollbackAfterCompletion(
                    ReprocessWorkerRun(
                        terminal = workerCompletion,
                        cancel = { cancelRequested.complete(Unit) }
                    )
                ) {
                    restoreBackups(directory, backups)
                }
            }

            yield()
            assertTrue(cancelRequested.isCompleted)

            source.writeText("late")
            assertEquals("late", source.readText())
            assertFalse(result.isCompleted)

            workerCompletion.complete(ReprocessWorkerOutcome(Result.failure(IllegalStateException("cancelled")), false))
            assertTrue(result.await().isSuccess)
            assertEquals("original", source.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
