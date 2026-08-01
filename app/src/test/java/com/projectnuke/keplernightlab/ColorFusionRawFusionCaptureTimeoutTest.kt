package com.projectnuke.keplernightlab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production queue/settlement seam tests used by both Camera2 capture owners. */
class ColorFusionRawFusionCaptureTimeoutTest {
    @Test
    fun productionYuvOwnerDiscardsBlockedPngCompletionAfterTimeout() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val terminal = CaptureTerminalState()
        val adopted = AtomicBoolean(false)
        val worker = BoundedCaptureWorker("production-yuv-owner", 1)
        try {
            assertTrue(worker.submit(Runnable {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                if (terminal.status() == CaptureTerminalStatus.ACTIVE) adopted.set(true)
            }))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(terminal.claim(CaptureTerminalStatus.TIMED_OUT))
            release.countDown()
            worker.awaitTermination(2_000L)
            assertFalse(adopted.get())
        } finally {
            worker.close()
        }
    }

    @Test
    fun productionRawOwnerDiscardsBlockedDngCompletionAfterCancellation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val terminal = CaptureTerminalState()
        val adopted = AtomicBoolean(false)
        val worker = BoundedCaptureWorker("production-raw-owner", 1)
        try {
            assertTrue(worker.submit(Runnable {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                if (terminal.status() == CaptureTerminalStatus.ACTIVE) adopted.set(true)
            }))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(terminal.claim(CaptureTerminalStatus.CANCELLED))
            release.countDown()
            worker.awaitTermination(2_000L)
            assertFalse(adopted.get())
        } finally {
            worker.close()
        }
    }

    @Test
    fun bufferedFramesReceiveUniqueOwnerIdentitiesAndFiles() {
        val dir = Files.createTempDirectory("yuv-identities").toFile()
        try {
            val owner = CaptureFrameIdentityOwner(3)
            val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
            val files = (0 until 3).map {
                val index = owner.nextIdentity() ?: error("identity allocation failed")
                val file = dir.resolve("frame_${index.toString().padStart(2, '0')}_color.png")
                file.writeBytes(png)
                file
            }
            assertEquals(listOf(0, 1, 2), files.map { it.name.substringAfter("frame_").substringBefore("_").toInt() })
            assertEquals(3, files.map { it.name }.toSet().size)
            assertEquals(3, files.count { it.isFile && it.length() > 0L })
            assertEquals(3, owner.allocatedCount())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun blockedEncoderDoesNotPreventTimeoutClaim() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val terminal = AtomicBoolean(false)
        val worker = BoundedCaptureWorker("capture-timeout", 1)
        try {
            assertTrue(worker.submit(Runnable {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(terminal.compareAndSet(false, true))
            assertFalse(terminal.compareAndSet(false, true))
            release.countDown()
        } finally {
            worker.close()
        }
    }

    @Test
    fun cancelledQueuedWorkIsRejectedAndCannotCompleteAfterTerminal() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val adopted = AtomicBoolean(false)
        val worker = BoundedCaptureWorker("capture-cancel", 1)
        try {
            assertTrue(worker.submit(Runnable {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            worker.submit(Runnable { adopted.set(true) })
            worker.close()
            release.countDown()
            assertFalse(adopted.get())
        } finally {
            worker.close()
        }
    }
}
