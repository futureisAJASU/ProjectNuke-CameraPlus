package com.projectnuke.keplernightlab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedCaptureWorkerTest {
    @Test
    fun queueSaturationRejectsWithoutUnboundedRetention() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rejected = AtomicInteger(0)
        val worker = BoundedCaptureWorker("test-capture", capacity = 1) { rejected.incrementAndGet() }
        try {
            assertTrue(worker.submit(Runnable {
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
            }))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(worker.submit(Runnable { }))
            assertFalse(worker.submit(Runnable { }))
            assertEquals(1, rejected.get())
            release.countDown()
        } finally {
            worker.close()
        }
    }

    @Test
    fun closeRejectsQueuedWorkAndDoesNotRunItLater() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ran = AtomicInteger(0)
        val rejected = AtomicInteger(0)
        val worker = BoundedCaptureWorker("test-capture-close", capacity = 1) {
            rejected.incrementAndGet()
        }
        worker.submit(Runnable {
            started.countDown()
            release.await(2, TimeUnit.SECONDS)
        })
        assertTrue(started.await(2, TimeUnit.SECONDS))
        worker.submit(Runnable { ran.incrementAndGet() })
        worker.close()
        release.countDown()
        assertEquals(0, ran.get())
        assertTrue(rejected.get() >= 1)
    }
}
