package com.projectnuke.keplernightlab

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
class ProcessingArtifactTransactionTest {
    private class TestCancellation(var cancelled: Boolean = false) : KeplerPipelineCancellation {
        override val isCancelled: Boolean get() = cancelled
        override fun throwIfCancelled() {
            if (cancelled) throw CancellationException("cancelled")
        }
    }

    @Test
    fun textArtifactCommitsThroughTempAndVerifiesFinal() {
        val dir = Files.createTempDirectory("processing-artifact").toFile()
        try {
            val finalFile = File(dir, "fusion_debug.json")
            val result = writeVerifiedJsonArtifact(finalFile, "{\"ok\":true}")
            assertEquals(ProcessingArtifactState.ADOPTED, result.state)
            assertEquals("{\"ok\":true}", NoFollowFileSystem.readTextVerified(finalFile))
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun failedVerificationDoesNotAdvertiseAnAdoptedFinal() {
        val dir = Files.createTempDirectory("processing-artifact-failure").toFile()
        try {
            val finalFile = File(dir, "bad.bin")
            var thrown: ProcessingArtifactException? = null
            try {
                commitProcessingArtifact(
                    finalFile,
                    writeTemp = { it.writeBytes(byteArrayOf(1, 2, 3)) },
                    verifyFinal = { error("verification failed") }
                )
            } catch (failure: ProcessingArtifactException) {
                thrown = failure
            }
            assertTrue(thrown != null)
            assertEquals(finalFile.absolutePath, thrown!!.finalFile.absolutePath)
            assertFalse(finalFile.exists())
            assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun copiedArtifactUsesVerifiedSourceAndAtomicFinal() {
        val dir = Files.createTempDirectory("processing-copy").toFile()
        try {
            val source = File(dir, "source.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val final = File(dir, "copy.bin")
            val result = copyVerifiedArtifact(source, final)
            assertEquals(ProcessingArtifactState.ADOPTED, result.state)
            assertEquals(source.readBytes().toList(), final.readBytes().toList())
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun textArtifactRejectsInvalidJsonBeforeAdoption() {
        val dir = Files.createTempDirectory("processing-json").toFile()
        try {
            val final = File(dir, "debug.json")
            var rejected = false
            try {
                writeVerifiedJsonArtifact(final, "{not-json")
            } catch (_: ProcessingArtifactException) {
                rejected = true
            }
            assertTrue(rejected)
            assertFalse(final.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun failedReplacementRestoresVerifiedPriorFinal() {
        val dir = Files.createTempDirectory("processing-artifact-restore").toFile()
        try {
            val finalFile = File(dir, "result.bin").apply { writeBytes("prior".toByteArray()) }
            var thrown: ProcessingArtifactException? = null
            try {
                commitProcessingArtifact(
                    finalFile,
                    writeTemp = { it.writeBytes("new".toByteArray()) },
                    verifyFinal = { committed ->
                        if (committed.readText() == "new") error("new verification failed")
                    }
                )
            } catch (failure: ProcessingArtifactException) {
                thrown = failure
            }
            assertTrue(thrown != null)
            assertEquals("prior", finalFile.readText())
            assertTrue(thrown!!.settlements.any {
                it.role == ProcessingArtifactResourceRole.PRIOR_BACKUP &&
                    it.status == ProcessingArtifactSettlementStatus.RESTORED
            })
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") || it.name.endsWith(".prior") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun writerFailureLeavesPriorFinalUntouched() {
        val dir = Files.createTempDirectory("processing-artifact-writer-failure").toFile()
        try {
            val finalFile = File(dir, "result.bin").apply { writeBytes("prior".toByteArray()) }
            var rejected = false
            try {
                commitProcessingArtifact(
                    finalFile,
                    writeTemp = { error("writer failed") },
                    verifyFinal = { error("unreachable") }
                )
            } catch (_: ProcessingArtifactException) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals("prior", finalFile.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cancellationBeforeCommitRestoresPriorFinal() {
        val dir = Files.createTempDirectory("processing-artifact-cancel").toFile()
        try {
            val finalFile = File(dir, "result.bin").apply { writeBytes("prior".toByteArray()) }
            val cancellation = TestCancellation(cancelled = true)
            var cancelled = false
            try {
                commitProcessingArtifact(
                    finalFile,
                    writeTemp = { it.writeBytes("new".toByteArray()) },
                    verifyFinal = {},
                    cancellation = cancellation
                )
            } catch (_: CancellationException) {
                cancelled = true
            } catch (_: ProcessingArtifactException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertEquals("prior", finalFile.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun truncatedPngAndJpegHeadersAreRejected() {
        val dir = Files.createTempDirectory("processing-corrupt-images").toFile()
        try {
            val png = File(dir, "bad.png").apply {
                writeBytes(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0))
            }
            val jpeg = File(dir, "bad.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)) }
            var pngRejected = false
            var jpegRejected = false
            try { verifyPngArtifact(png) } catch (_: Throwable) { pngRejected = true }
            try { verifyJpegArtifact(jpeg) } catch (_: Throwable) { jpegRejected = true }
            assertTrue(pngRejected)
            assertTrue(jpegRejected)
        } finally {
            dir.deleteRecursively()
        }
    }
}
