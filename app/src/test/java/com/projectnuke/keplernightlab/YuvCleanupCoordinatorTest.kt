package com.projectnuke.keplernightlab

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvCleanupCoordinatorTest {

    @Test
    fun initiationOnceAcrossOwnerTerminalAndSessionClose() {
        val stateOwner = CaptureStateOwner(dispatch = { true })
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024 * 1024)
        val worker = BoundedCaptureWorker("cleanup-init", 4)
        val coordinator = YuvCleanupCoordinator(stateOwner, lifecycle, accounting, reservations, worker)

        val result1 = coordinator.perform()
        assertEquals(CleanupPhase.COMPLETED, result1.phase)
        assertTrue(result1.cleanupStarted)
        assertTrue(result1.ownerCloseRequested)
        assertTrue(result1.workerShutdownRequested)
        assertEquals(1, result1.cleanupInitiationCount)

        val result2 = coordinator.perform()
        assertEquals(CleanupPhase.COMPLETED, result2.phase)
        assertTrue(result2.cleanupStarted)
        assertTrue(result2.ownerCloseRequested)
        assertTrue(result2.workerShutdownRequested)
        assertEquals(1, result2.cleanupInitiationCount)
        assertEquals(0, result2.totalDrainedRetainedItems)
    }

    @Test
    fun retainedItemDrainsAndReleasesReservationOnce() {
        val stateOwner = CaptureStateOwner(dispatch = { true })
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024 * 100)
        val worker = BoundedCaptureWorker("cleanup-drain", 1)
        val coordinator = YuvCleanupCoordinator(stateOwner, lifecycle, accounting, reservations, worker)

        assertTrue(reservations.tryReserve(100))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))
        assertEquals(1, accounting.snapshot().bufferedFrames)
        assertEquals(100L, reservations.currentBytes())

        val result = coordinator.perform()
        assertEquals(1, result.totalDrainedRetainedItems)
        assertEquals(0, accounting.snapshot().bufferedFrames)
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, lifecycle.retainedCount())
    }

    @Test
    fun queuedBufferedTaskIsDisposedOnce() {
        val latch = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stateOwner = CaptureStateOwner(dispatch = { true })
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024 * 100)
        val worker = BoundedCaptureWorker("cleanup-blqd", 2)
        val coordinator = YuvCleanupCoordinator(stateOwner, lifecycle, accounting, reservations, worker)

        val disposeCount = AtomicInteger(0)
        assertTrue(reservations.tryReserve(100))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100, reservations, accounting) {
            disposeCount.incrementAndGet()
        }
        val task = DisposableYuvTask(item, accounting) {}

        assertTrue(worker.submit(Runnable {
            latch.countDown(); release.await(2, TimeUnit.SECONDS)
        }))
        assertTrue(latch.await(2, TimeUnit.SECONDS))

        assertTrue(worker.submit(task))
        assertEquals(1, worker.queuedCount())

        val result = coordinator.perform()
        assertEquals(1, result.totalQueuedTasksRemoved)
        assertEquals(1, result.totalQueuedDisposableDisposalAttempts)
        assertEquals(1, result.totalQueuedDisposableDisposalsSucceeded)
        assertEquals(1, disposeCount.get())
        assertEquals(0L, reservations.currentBytes())

        release.countDown()
        assertTrue(worker.awaitTermination(5_000))
    }

    @Test
    fun blockedEncodingItemRemainsAccountedAfterCleanupInitiation() {
        val stateOwner = CaptureStateOwner(dispatch = { true })
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024 * 100)
        val worker = BoundedCaptureWorker("cleanup-blocked", 1)
        val coordinator = YuvCleanupCoordinator(stateOwner, lifecycle, accounting, reservations, worker)

        assertTrue(reservations.tryReserve(100))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        val result = coordinator.perform()
        assertTrue(result.ownerCloseRequested)
        assertEquals(1, result.currentEncodingItems)
        assertEquals(1, result.currentBufferedFrames)
        assertEquals(100L, result.currentReservedBytes)

        lifecycle.settleEncoding(item, accounting)
        val after = coordinator.snapshot()
        assertEquals(0, after.currentEncodingItems)
        assertEquals(0L, after.currentReservedBytes)
        assertEquals(0, after.currentBufferedFrames)
    }

    @Test
    fun cleanupSnapshotReportsRetainedEncodingBytesTruthfully() {
        val stateOwner = CaptureStateOwner(dispatch = { true })
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024 * 100)
        val worker = BoundedCaptureWorker("cleanup-snap", 1)
        val coordinator = YuvCleanupCoordinator(stateOwner, lifecycle, accounting, reservations, worker)

        assertTrue(reservations.tryReserve(80))
        val rItem = YuvPngWorkItem.bufferedForTest(0, 1L, 40, reservations, accounting)
        val eItem = YuvPngWorkItem.bufferedForTest(1, 2L, 40, reservations, accounting)
        assertTrue(lifecycle.tryRegister(rItem))
        assertTrue(lifecycle.tryRegister(eItem))
        assertTrue(lifecycle.beginEncoding(eItem))

        val snap = coordinator.snapshot()
        assertEquals(1, snap.currentRetainedItems)
        assertEquals(1, snap.currentEncodingItems)
        assertEquals(2, snap.currentBufferedFrames)
        assertEquals(80L, snap.currentReservedBytes)
        assertFalse(snap.ownerCloseRequested)
    }

    @Test
    fun firstRetainedItemDisposalFailureSecondItemStillDisposes() {
        val stateOwner = CaptureStateOwner(dispatch = { true })
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024)
        val worker = BoundedCaptureWorker("cleanup-fail-per-item", 2)
        val coordinator = YuvCleanupCoordinator(stateOwner, lifecycle, accounting, reservations, worker)

        assertTrue(reservations.tryReserve(200))
        val goodItem = YuvPngWorkItem.bufferedForTest(0, 1L, 100, reservations, accounting)
        assertTrue(lifecycle.tryRegister(goodItem))

        val throwingItem = YuvPngWorkItem.bufferedForTest(1, 2L, 100, reservations, accounting) {
            error("injected onRelease failure")
        }
        assertTrue(lifecycle.tryRegister(throwingItem))

        val result = coordinator.perform()
        assertTrue(result.ownerCloseRequested)
        assertTrue(result.workerShutdownRequested)
        assertTrue(result.cleanupStarted)
        assertEquals(2, result.totalDrainedRetainedItems)
        assertTrue(result.cleanupFailures.any { it.contains("onRelease") })
        assertEquals(0, lifecycle.retainedCount())
        assertEquals(0L, reservations.currentBytes())
    }

    @Test
    fun workerQueuedTaskDisposalThrowsLaterTaskStillDisposes() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stateOwner = CaptureStateOwner(dispatch = { true })
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024)
        val worker = BoundedCaptureWorker("cleanup-worker-fail", 2,
            onTaskDisposalFailure = { _, _ -> },
            onRejectionNotificationFailure = { _, _ -> },
            onRejected = { _ -> })
        val coordinator = YuvCleanupCoordinator(stateOwner, lifecycle, accounting, reservations, worker)

        val task2Disposed = AtomicInteger(0)

        assertTrue(worker.submit(Runnable {
            started.countDown(); release.await(2, TimeUnit.SECONDS)
        }))
        assertTrue(started.await(2, TimeUnit.SECONDS))

        val task1 = object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() { error("task1 disposal failed") }
        }
        val task2 = object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() { task2Disposed.incrementAndGet() }
        }
        assertTrue(worker.submit(task1))
        assertTrue(worker.submit(task2))
        assertEquals(2, worker.queuedCount())

        val result = coordinator.perform()
        assertTrue(result.ownerCloseRequested)
        assertEquals(1, task2Disposed.get())
        assertTrue(result.cleanupFailures.any { it.contains("taskDispose") })
        assertEquals(2, result.totalQueuedTasksRemoved)
        assertEquals(2, result.totalQueuedDisposableDisposalAttempts)
        assertEquals(1, result.totalQueuedDisposableDisposalsSucceeded)
        assertEquals(1, result.workerTaskDisposalFailures.size)
        assertEquals(0, result.workerRejectionNotificationFailures.size)

        release.countDown()
        assertTrue(worker.awaitTermination(5_000))
    }

    @Test
    fun concurrentPerformDoesNotDoubleInitiate() {
        val stateOwner = CaptureStateOwner(dispatch = { true })
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024)
        val worker = BoundedCaptureWorker("cleanup-concurrent", 4,
            onTaskDisposalFailure = { _, _ -> },
            onRejectionNotificationFailure = { _, _ -> },
            onRejected = { _ -> })
        val coordinator = YuvCleanupCoordinator(stateOwner, lifecycle, accounting, reservations, worker)

        // Add a retained item whose disposal blocks, keeping the first
        // perform() in cleanup's IN_PROGRESS phase while the second caller
        // invokes perform() and observes the in-progress snapshot.
        val disposalStarted = CountDownLatch(1)
        val disposalBlock = CountDownLatch(1)
        assertTrue(reservations.tryReserve(100))
        val blockingItem = YuvPngWorkItem.bufferedForTest(0, 1L, 100, reservations, accounting) {
            disposalStarted.countDown()
            disposalBlock.await(5, TimeUnit.SECONDS)
        }
        assertTrue(lifecycle.tryRegister(blockingItem))

        val barrier = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = ConcurrentLinkedQueue<YuvCleanupResult>()
        val t1 = Thread {
            assertTrue(barrier.await(5, TimeUnit.SECONDS))
            results.add(coordinator.perform())
            done.countDown()
        }
        val t2 = Thread {
            assertTrue(barrier.await(5, TimeUnit.SECONDS))
            results.add(coordinator.perform())
            done.countDown()
        }
        t1.start(); t2.start()
        barrier.countDown()
        // Wait for first caller's perform to enter IN_PROGRESS and start draining
        assertTrue(disposalStarted.await(2, TimeUnit.SECONDS))
        // Second caller should observe IN_PROGRESS
        disposalBlock.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        t1.join(2_000); t2.join(2_000)
        assertFalse(t1.isAlive); assertFalse(t2.isAlive)

        assertEquals(2, results.size)
        assertTrue(results.all { it.cleanupInitiationCount == 1 })

        val completed = results.filter { it.phase == CleanupPhase.COMPLETED }
        val inProgress = results.filter { it.phase == CleanupPhase.IN_PROGRESS }
        assertTrue("at least one caller observed IN_PROGRESS", inProgress.isNotEmpty())
        assertTrue("at least one caller observed COMPLETED", completed.isNotEmpty())
    }

    @Test
    fun performAfterCompletionReturnsHistoricalResult() {
        val stateOwner = CaptureStateOwner(dispatch = { true })
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024)
        val worker = BoundedCaptureWorker("cleanup-after", 2,
            onTaskDisposalFailure = { _, _ -> },
            onRejectionNotificationFailure = { _, _ -> },
            onRejected = { _ -> })
        val coordinator = YuvCleanupCoordinator(stateOwner, lifecycle, accounting, reservations, worker)

        val first = coordinator.perform()
        assertEquals(CleanupPhase.COMPLETED, first.phase)
        assertEquals(1, first.cleanupInitiationCount)

        val second = coordinator.perform()
        assertEquals(CleanupPhase.COMPLETED, second.phase)
        assertEquals(1, second.cleanupInitiationCount)
        assertTrue(second.ownerCloseRequested)
        assertTrue(second.workerShutdownRequested)
    }

    @Test
    fun cleanupResultPreservesDisposalAttemptAndSuccessCountsAcrossRepeatedPerform() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stateOwner = CaptureStateOwner(dispatch = { true })
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024)
        val worker = BoundedCaptureWorker("cleanup-attempts", 3,
            onTaskDisposalFailure = { _, _ -> },
            onRejectionNotificationFailure = { _, _ -> },
            onRejected = { _ -> })
        val coordinator = YuvCleanupCoordinator(stateOwner, lifecycle, accounting, reservations, worker)

        assertTrue(worker.submit(Runnable {
            started.countDown(); release.await(5, TimeUnit.SECONDS)
        }))
        assertTrue(started.await(2, TimeUnit.SECONDS))

        val failingTask = object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() { error("injected queued disposal failure") }
        }
        val goodTask = object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() {}
        }
        assertTrue(worker.submit(failingTask))
        assertTrue(worker.submit(goodTask))

        val result = coordinator.perform()
        assertEquals(2, result.totalQueuedTasksRemoved)
        assertEquals(2, result.totalQueuedDisposableDisposalAttempts)
        assertEquals(1, result.totalQueuedDisposableDisposalsSucceeded)
        assertEquals(1, result.workerTaskDisposalFailures.size)
        assertEquals(0, result.totalQueuedNonDisposableTasksRemoved)
        assertEquals(1, result.activeWorkersAtCleanupStart)
        assertTrue(result.cleanupFailures.any { it.contains("taskDispose") })

        // A repeated perform() after completion must retain all historical totals.
        val again = coordinator.perform()
        assertEquals(2, again.totalQueuedTasksRemoved)
        assertEquals(2, again.totalQueuedDisposableDisposalAttempts)
        assertEquals(1, again.totalQueuedDisposableDisposalsSucceeded)
        assertEquals(1, again.workerTaskDisposalFailures.size)

        release.countDown()
        assertTrue(worker.awaitTermination(5_000))
    }

    @Test
    fun cleanupResultPreservesNonDisposableRemovedTaskCount() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stateOwner = CaptureStateOwner(dispatch = { true })
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024)
        val worker = BoundedCaptureWorker("cleanup-nondisp", 3,
            onTaskDisposalFailure = { _, _ -> },
            onRejectionNotificationFailure = { _, _ -> },
            onRejected = { _ -> })
        val coordinator = YuvCleanupCoordinator(stateOwner, lifecycle, accounting, reservations, worker)

        assertTrue(worker.submit(Runnable {
            started.countDown(); release.await(5, TimeUnit.SECONDS)
        }))
        assertTrue(started.await(2, TimeUnit.SECONDS))

        assertTrue(worker.submit(object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() {}
        }))
        assertTrue(worker.submit(Runnable {}))

        val result = coordinator.perform()
        assertEquals(2, result.totalQueuedTasksRemoved)
        assertEquals(1, result.totalQueuedDisposableDisposalAttempts)
        assertEquals(1, result.totalQueuedDisposableDisposalsSucceeded)
        assertEquals(1, result.totalQueuedNonDisposableTasksRemoved)

        release.countDown()
        assertTrue(worker.awaitTermination(5_000))
    }

    @Test
    fun closeAndDrainRetainedFailureStillRequestsWorkerShutdown() {
        val stateOwner = CaptureStateOwner(dispatch = { true })
        val lifecycle = object : YuvBufferedLifecycle() {
            override fun closeAndDrainRetained(): List<YuvPngWorkItem> =
                error("injected closeAndDrainRetained failure")
        }
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024)
        val worker = BoundedCaptureWorker("cleanup-cadr-fail", 1,
            onTaskDisposalFailure = { _, _ -> },
            onRejectionNotificationFailure = { _, _ -> },
            onRejected = { _ -> })
        val coordinator = YuvCleanupCoordinator(stateOwner, lifecycle, accounting, reservations, worker)

        val result = coordinator.perform()
        assertEquals(CleanupPhase.COMPLETED, result.phase)
        assertTrue(result.ownerCloseRequested)
        assertTrue(result.workerShutdownRequested)
        assertEquals(0, result.totalDrainedRetainedItems)
        assertTrue(result.cleanupFailures.any { it.contains("closeAndDrainRetained") })

        assertTrue(worker.awaitTermination(5_000))
    }

    @Test
    fun finishDrainFailureDoesNotSkipLaterDrainedItems() {
        val stateOwner = CaptureStateOwner(dispatch = { true })
        val lifecycle = object : YuvBufferedLifecycle() {
            override fun finishDrain(item: YuvPngWorkItem): Boolean {
                if (item.frameIndex == 0) error("injected finishDrain failure")
                return super.finishDrain(item)
            }
        }
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024)
        val worker = BoundedCaptureWorker("cleanup-finishfail", 1,
            onTaskDisposalFailure = { _, _ -> },
            onRejectionNotificationFailure = { _, _ -> },
            onRejected = { _ -> })
        val coordinator = YuvCleanupCoordinator(stateOwner, lifecycle, accounting, reservations, worker)

        assertTrue(reservations.tryReserve(200))
        val first = YuvPngWorkItem.bufferedForTest(0, 1L, 100, reservations, accounting)
        val second = YuvPngWorkItem.bufferedForTest(1, 2L, 100, reservations, accounting)
        assertTrue(lifecycle.tryRegister(first))
        assertTrue(lifecycle.tryRegister(second))

        val result = coordinator.perform()
        assertEquals(2, result.totalDrainedRetainedItems)
        assertTrue(result.cleanupFailures.any { it.contains("drainFinish[0]") })
        assertTrue(result.workerShutdownRequested)
        assertEquals(CleanupPhase.COMPLETED, result.phase)
        // Both items were disposed (independent disposal boundaries)...
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
        // ...but only the second item was finished; the first remains truthfully DRAINING.
        assertEquals(1, lifecycle.drainingCount())
        assertEquals(1, lifecycle.trackedCount())

        assertTrue(worker.awaitTermination(5_000))
    }

    @Test
    fun ownerCloseContainedDisposalFailureDoesNotSkipLifecycleDrain() {
        val disposalFailures = mutableListOf<String>()
        val stateOwner = CaptureStateOwner(
            dispatch = { true },
            onDisposalFailure = { _, t -> disposalFailures.add(t.message ?: "?") }
        )
        // A PENDING event whose disposeWithoutMutation throws: close() must contain the
        // failure (it never escapes close) and the drain stage must still run.
        val throwingEvent = object : CaptureOwnerEvent {
            override fun execute() {}
            override fun disposeWithoutMutation() = error("injected pending-event disposal failure")
        }
        assertTrue(stateOwner.post(throwingEvent))
        assertEquals(1, stateOwner.pendingCount())

        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024)
        val worker = BoundedCaptureWorker("cleanup-ownfail", 1,
            onTaskDisposalFailure = { _, _ -> },
            onRejectionNotificationFailure = { _, _ -> },
            onRejected = { _ -> })
        val coordinator = YuvCleanupCoordinator(stateOwner, lifecycle, accounting, reservations, worker)

        assertTrue(reservations.tryReserve(100))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))

        val result = coordinator.perform()
        assertTrue(result.ownerCloseRequested)
        assertTrue(result.workerShutdownRequested)
        assertEquals(1, result.totalDrainedRetainedItems)
        assertEquals(0, lifecycle.retainedCount())
        assertEquals(0, lifecycle.drainingCount())
        assertEquals(0L, reservations.currentBytes())
        // The injected disposal failure was contained by the owner (not thrown into cleanup).
        assertEquals(1, disposalFailures.size)
        assertEquals("injected pending-event disposal failure", disposalFailures[0])
        assertTrue(result.cleanupFailures.none { it.contains("ownerClose") })
    }

    @Test
    fun workerShutdownFailureDoesNotPreventStatePublication() {
        val stateOwner = CaptureStateOwner(dispatch = { true })
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024)
        val worker = object : BoundedCaptureWorker("cleanup-wfail", 1,
            onTaskDisposalFailure = { _, _ -> },
            onRejectionNotificationFailure = { _, _ -> },
            onRejected = { _ -> }) {
            override fun shutdownNow(): CleanupReport =
                error("injected worker shutdown failure")
        }
        val coordinator = YuvCleanupCoordinator(stateOwner, lifecycle, accounting, reservations, worker)

        val result = coordinator.perform()
        assertEquals(CleanupPhase.COMPLETED, result.phase)
        assertTrue(result.ownerCloseRequested)
        assertTrue(result.workerShutdownRequested)
        assertTrue(result.cleanupFailures.any { it.contains("workerShutdown") })
        assertEquals(0, result.totalQueuedTasksRemoved)
    }
}
