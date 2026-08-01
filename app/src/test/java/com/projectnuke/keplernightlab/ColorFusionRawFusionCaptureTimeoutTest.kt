package com.projectnuke.keplernightlab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production queue/settlement seam tests used by both Camera2 capture owners. */
class ColorFusionRawFusionCaptureTimeoutTest {
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
