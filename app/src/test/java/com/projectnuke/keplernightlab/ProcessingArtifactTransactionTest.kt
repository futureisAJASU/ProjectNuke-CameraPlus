package com.projectnuke.keplernightlab

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingArtifactTransactionTest {
    @Test
    fun textArtifactCommitsThroughTempAndVerifiesFinal() {
        val dir = Files.createTempDirectory("processing-artifact").toFile()
        try {
            val finalFile = File(dir, "fusion_debug.json")
            val result = writeVerifiedTextArtifact(finalFile, "{\"ok\":true}")
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
            var thrown = false
            try {
                commitProcessingArtifact(
                    finalFile,
                    writeTemp = { it.writeBytes(byteArrayOf(1, 2, 3)) },
                    verifyFinal = { error("verification failed") }
                )
            } catch (_: IllegalStateException) {
                thrown = true
            }
            assertTrue(thrown)
            assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }
}
