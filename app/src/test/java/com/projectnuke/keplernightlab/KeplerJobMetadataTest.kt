package com.projectnuke.keplernightlab

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
}
