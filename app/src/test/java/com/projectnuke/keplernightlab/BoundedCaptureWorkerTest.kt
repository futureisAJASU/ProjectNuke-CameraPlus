package com.projectnuke.keplernightlab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test
    fun queueSaturationDisposalThrowsNotificationStillRuns() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val notified = AtomicInteger(0)
        val disposable = object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() { error("disposal failed") }
        }
        val worker = BoundedCaptureWorker("sat-dispose", capacity = 1,
            onTaskDisposalFailure = { _, _ -> },
            onRejectionNotificationFailure = { _, _ -> },
            onRejected = { _ -> notified.incrementAndGet() })
        try {
            assertTrue(worker.submit(Runnable {
                started.countDown(); release.await(2, TimeUnit.SECONDS)
            }))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(worker.submit(Runnable {}))
            assertFalse(worker.submit(disposable))
            assertEquals(1, notified.get())
            release.countDown()
        } finally {
            worker.close()
        }
    }

    @Test
    fun closedWorkerDisposalThrowsNotificationStillRuns() {
        val notified = AtomicInteger(0)
        val disposable = object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() { error("disposal failed") }
        }
        val worker = BoundedCaptureWorker("closed-dispose", capacity = 1,
            onTaskDisposalFailure = { _, _ -> },
            onRejectionNotificationFailure = { _, _ -> },
            onRejected = { _ -> notified.incrementAndGet() })
        worker.close()
        assertFalse(worker.submit(disposable))
        assertEquals(1, notified.get())
    }

    @Test
    fun twoQueuedDisposableTasksFirstDisposalThrowsSecondStillDisposes() {
        val started = CountDownLatch(1)
        val block = CountDownLatch(1)
        val secondDisposed = AtomicInteger(0)
        val worker = BoundedCaptureWorker("worker-two-fail", capacity = 3,
            onTaskDisposalFailure = { _, _ -> },
            onRejectionNotificationFailure = { _, _ -> },
            { })

        assertTrue(worker.submit(Runnable {
            started.countDown(); block.await(5, TimeUnit.SECONDS)
        }))
        assertTrue(started.await(2, TimeUnit.SECONDS))

        val task1 = object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() { error("disposal A failed") }
        }
        val task2 = object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() { secondDisposed.incrementAndGet() }
        }
        worker.submit(task1)
        worker.submit(task2)
        val r = worker.shutdownNow()
        assertEquals(1, secondDisposed.get())
        assertEquals(1, r.taskDisposalFailures.size)
        assertEquals(2, r.queuedDisposableTasksDisposalAttempted)
        assertEquals(1, r.queuedDisposableTasksDisposedSuccessfully)
        assertEquals(2, r.queuedTasksRemoved)
        assertEquals(0, r.queuedNonDisposableTasksRemoved)
        assertEquals(1, r.activeWorkersAtStart)
        block.countDown()
        assertTrue(worker.awaitTermination(5_000))
    }

    @Test
    fun rejectionNotificationThrowsLaterNotificationsStillRun() {
        val count = AtomicInteger(0)
        var first = true
        val worker = BoundedCaptureWorker("worker-notif-fail", capacity = 1,
            onTaskDisposalFailure = { _, _ -> },
            onRejectionNotificationFailure = { _, _ -> },
            onRejected = { _ ->
                count.incrementAndGet()
                if (first) { first = false; error("notification failed") }
            })
        worker.close()
        assertFalse(worker.submit(object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() {}
        }))
        assertFalse(worker.submit(object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() {}
        }))
        assertEquals(2, count.get())
    }

    @Test
    fun oneDisposableOneNonDisposableTasksReportExact() {
        val started = CountDownLatch(1)
        val block = CountDownLatch(1)
        val worker = BoundedCaptureWorker("worker-count", capacity = 3,
            onTaskDisposalFailure = { _, _ -> },
            onRejectionNotificationFailure = { _, _ -> },
            onRejected = {})
        assertTrue(worker.submit(Runnable {
            started.countDown(); block.await(5, TimeUnit.SECONDS)
        }))
        assertTrue(started.await(2, TimeUnit.SECONDS))
        worker.submit(object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() {}
        })
        worker.submit(Runnable {})
        val r = worker.shutdownNow()
        assertEquals(2, r.queuedTasksRemoved)
        assertEquals(1, r.queuedDisposableTasksDisposalAttempted)
        assertEquals(1, r.queuedNonDisposableTasksRemoved)
        block.countDown()
    }

    @Test
    fun runningTaskNotDisposedByShutdownNow() {
        val started = CountDownLatch(1)
        val block = CountDownLatch(1)
        val disposed = AtomicInteger(0)
        val worker = BoundedCaptureWorker("worker-running", capacity = 1,
            onTaskDisposalFailure = { _, _ -> },
            onRejectionNotificationFailure = { _, _ -> },
            {})
        val task = object : DisposableCaptureTask {
            override fun run() { started.countDown(); block.await(5, TimeUnit.SECONDS) }
            override fun dispose() { disposed.incrementAndGet() }
        }
        assertTrue(worker.submit(task))
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val r = worker.shutdownNow()
        assertEquals(0, disposed.get())
        assertEquals(0, r.queuedTasksRemoved)
        assertEquals(0, r.queuedDisposableTasksDisposalAttempted)
        assertEquals(0, r.queuedDisposableTasksDisposedSuccessfully)
        assertEquals(1, r.activeWorkersAtStart)
        block.countDown()
        assertTrue(worker.awaitTermination(5_000))
    }

    // ── Observability: rejection path reports correct task identity and throwable ─

    @Test
    fun normalRejectionNotifiesDisposalFailureWithTaskIdentity() {
        val disposalFailures = mutableListOf<Pair<Runnable, Throwable>>()
        val notified = AtomicInteger(0)
        val worker = BoundedCaptureWorker("reject-obs", capacity = 1,
            onTaskDisposalFailure = { task, t -> disposalFailures += task to t },
            onRejectionNotificationFailure = { _, _ -> },
            onRejected = { _ -> notified.incrementAndGet() })

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        assertTrue(worker.submit(Runnable {
            started.countDown(); release.await(2, TimeUnit.SECONDS)
        }))
        assertTrue(started.await(2, TimeUnit.SECONDS))

        // Fill the queue (capacity 1)
        assertTrue(worker.submit(Runnable {}))
        // Next submit is rejected
        val rejectedTask = object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() { error("disposal failed for observability") }
        }
        assertFalse(worker.submit(rejectedTask))

        assertEquals(1, disposalFailures.size)
        assertSame(rejectedTask, disposalFailures[0].first)
        assertEquals("disposal failed for observability", disposalFailures[0].second.message)
        assertEquals(1, notified.get())

        release.countDown()
        worker.close()
        assertTrue(worker.awaitTermination(5_000))
    }

    @Test
    fun notificationFailureNotifiesWithTaskIdentityAndSubmitReturnsFalse() {
        val notifFailures = mutableListOf<Pair<Runnable, Throwable>>()
        val worker = BoundedCaptureWorker("notif-obs", capacity = 1,
            onTaskDisposalFailure = { _, _ -> },
            onRejectionNotificationFailure = { task, t -> notifFailures += task to t },
            onRejected = { _ -> error("notification failed") })

        worker.close()

        val rejectedTask = object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() {}
        }
        assertFalse(worker.submit(rejectedTask))

        assertEquals(1, notifFailures.size)
        assertSame(rejectedTask, notifFailures[0].first)
        assertEquals("notification failed", notifFailures[0].second.message)
    }

    @Test
    fun shutdownFailureSinkContainsExactCountsAndFailures() {
        val started = CountDownLatch(1)
        val block = CountDownLatch(1)
        val secondDisposed = AtomicInteger(0)
        val disposalFailures = mutableListOf<Pair<Runnable, Throwable>>()
        val worker = BoundedCaptureWorker("shutdown-report", capacity = 3,
            onTaskDisposalFailure = { task, t -> disposalFailures += task to t },
            onRejectionNotificationFailure = { _, _ -> },
            onRejected = { _ -> })

        assertTrue(worker.submit(Runnable {
            started.countDown(); block.await(5, TimeUnit.SECONDS)
        }))
        assertTrue(started.await(2, TimeUnit.SECONDS))

        val failingTask = object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() { error("shutdown disposal failure") }
        }
        val goodTask = object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() { secondDisposed.incrementAndGet() }
        }
        worker.submit(failingTask)
        worker.submit(goodTask)

        val r = worker.shutdownNow()
        assertEquals(1, secondDisposed.get())
        assertEquals(1, r.taskDisposalFailures.size)
        assertEquals(2, r.queuedDisposableTasksDisposalAttempted)
        assertEquals(1, r.queuedDisposableTasksDisposedSuccessfully)
        assertEquals(2, r.queuedTasksRemoved)
        assertEquals(0, r.queuedNonDisposableTasksRemoved)
        assertEquals(1, r.activeWorkersAtStart)
        assertFalse(r.shutdownAlreadyRequested)
        assertEquals(1, disposalFailures.size)
        assertEquals("shutdown disposal failure", disposalFailures[0].second.message)

        block.countDown()
        assertTrue(worker.awaitTermination(5_000))
    }
}
