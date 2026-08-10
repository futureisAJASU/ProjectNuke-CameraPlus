package com.projectnuke.keplernightlab

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StreamingPngTileSinkTest {
    @Test
    fun finishCommitsAndSecondFinishIsRejected() {
        val dir = Files.createTempDirectory("streaming-png").toFile()
        try {
            val output = dir.resolve("output.png")
            val sink = StreamingPngTileSink(output)
            sink.begin(2, 2)
            sink.writeTile(0, 0, 2, 2, intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt()))
            assertTrue(sink.finish().isFile)
            var rejected = false
            try {
                sink.finish()
            } catch (_: IllegalStateException) {
                rejected = true
            }
            assertTrue(rejected)
            assertTrue(output.isFile)
            assertTrue(sink.settlementRecords().any {
                it.role == ProcessingArtifactResourceRole.ADOPTED_FINAL &&
                    it.status == ProcessingArtifactSettlementStatus.ADOPTED
            })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun abortMidStreamRemovesTemporaryAndDoesNotCreateFinal() {
        val dir = Files.createTempDirectory("streaming-png-abort").toFile()
        try {
            val output = dir.resolve("output.png")
            val sink = StreamingPngTileSink(output)
            sink.begin(2, 2)
            sink.writeTile(0, 0, 2, 1, intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()))
            sink.abort()
            assertFalse(output.exists())
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
            sink.abort()
        } finally {
            dir.deleteRecursively()
        }
    }
}
