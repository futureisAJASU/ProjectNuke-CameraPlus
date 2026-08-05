package com.projectnuke.keplernightlab

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
        assertTrue(result1.cleanupStarted)
        assertTrue(result1.ownerCloseRequested)
        assertTrue(result1.workerShutdownRequested)

        val result2 = coordinator.perform()
        assertTrue(result2.cleanupStarted)
        assertTrue(result2.ownerCloseRequested)
        assertTrue(result2.workerShutdownRequested)
        assertEquals(1, result2.cleanupInitiationCount)
        // Historical drained total preserved even on repeat call
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
        assertEquals(1, result.totalQueuedDisposableTasksDisposed)
        assertEquals(1, disposeCount.get())
        assertEquals(0L, reservations.currentBytes())

        release.countDown()
        worker.awaitTermination(5_000)
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
        assertEquals(100L, result.currentReservedBytes)
        assertEquals(1, result.currentBufferedFrames)

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

        // Inject disposal failure via onRelease throwing
        val onReleaseCount = AtomicInteger(0)
        val throwingItem = YuvPngWorkItem.bufferedForTest(1, 2L, 100, reservations, accounting) {
            onReleaseCount.incrementAndGet()
            error("injected onRelease failure")
        }
        assertTrue(lifecycle.tryRegister(throwingItem))

        val result = coordinator.perform()
        assertTrue(result.ownerCloseRequested)
        assertTrue(result.workerShutdownRequested)
        assertTrue(result.cleanupStarted)
        assertEquals(2, result.totalDrainedRetainedItems)
        // One failure from the throwing item, good item still disposed
        assertTrue(result.cleanupFailures.any { it.contains("onRelease") })
        assertEquals(0, lifecycle.retainedCount())
        assertEquals(0L, reservations.currentBytes())
    }

    @Test
    fun workerQueuedTaskDisposalThrowsLaterTaskStillDisposes() {
        val stateOwner = CaptureStateOwner(dispatch = { true })
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024)
        val worker = BoundedCaptureWorker("cleanup-worker-fail", 2)
        val coordinator = YuvCleanupCoordinator(stateOwner, lifecycle, accounting, reservations, worker)

        val task2Disposed = AtomicInteger(0)
        val task1 = object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() { error("task1 disposal failed") }
        }
        val task2 = object : DisposableCaptureTask {
            override fun run() {}
            override fun dispose() { task2Disposed.incrementAndGet() }
        }
        worker.submit(task1)
        worker.submit(task2)

        val result = coordinator.perform()
        assertTrue(result.ownerCloseRequested)
        assertEquals(1, task2Disposed.get())
    }

    @Test
    fun concurrentPerformDoesNotDoubleInitiate() {
        val stateOwner = CaptureStateOwner(dispatch = { true })
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024)
        val worker = BoundedCaptureWorker("cleanup-concurrent", 1)
        val coordinator = YuvCleanupCoordinator(stateOwner, lifecycle, accounting, reservations, worker)

        val latch = CountDownLatch(2)
        val results = mutableListOf<YuvCleanupResult>()
        val t1 = Thread { results.add(coordinator.perform()); latch.countDown() }
        val t2 = Thread { results.add(coordinator.perform()); latch.countDown() }
        t1.start(); t2.start()
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        t1.join(2_000); t2.join(2_000)

        assertTrue(results.all { it.cleanupInitiationCount == 1 })
    }
}