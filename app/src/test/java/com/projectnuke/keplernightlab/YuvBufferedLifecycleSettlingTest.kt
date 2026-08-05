package com.projectnuke.keplernightlab

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvBufferedLifecycleSettlingTest {

    @Test
    fun settleEncodingOnRetainedItemIsInvalidState() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))

        assertEquals(YuvBufferedLifecycle.SettlementResult.INVALID_STATE, lifecycle.startSettling(item))
        assertEquals(0, lifecycle.settlingCount())
        assertEquals(1, lifecycle.retainedCount())
        assertEquals(1, accounting.snapshot().bufferedFrames)
        assertEquals(100L, reservations.currentBytes())

        // settleEncoding on RETAINED: startSettling returns INVALID_STATE, no disposal
        lifecycle.settleEncoding(item, accounting)
        assertEquals(1, lifecycle.retainedCount())
        assertEquals(100L, reservations.currentBytes())
        lifecycle.closeAndDrainRetained().forEach { it.dispose(accounting) }
    }

    @Test
    fun settlingKeepsOwnershipVisibleWhileDisposalBlocked() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val disposalStarted = CountDownLatch(1)
        val disposalBlock = CountDownLatch(1)

        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            disposalStarted.countDown()
            disposalBlock.await(5, TimeUnit.SECONDS)
        }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        val settlingResult = lifecycle.startSettling(item)
        assertEquals(YuvBufferedLifecycle.SettlementResult.STARTED, settlingResult)

        // State is SETTLING: item remains tracked, ownership visible
        assertEquals(1, lifecycle.settlingCount())
        assertEquals(1, lifecycle.activeEncodingOwnershipCount())
        assertEquals(1, lifecycle.trackedCount())
        assertEquals(100L, reservations.currentBytes())
        assertEquals(1, accounting.snapshot().bufferedFrames)

        // Run disposal on a separate thread so it blocks independently
        val disposalThread = Thread {
            item.settleBufferedAccounting(accounting)
            item.dispose(accounting)
            lifecycle.finishSettling(item)
        }
        disposalThread.start()
        assertTrue(disposalStarted.await(2, TimeUnit.SECONDS))

        // While disposal blocked, ownership still visible
        assertEquals(1, lifecycle.settlingCount())
        assertEquals(1, lifecycle.activeEncodingOwnershipCount())
        assertEquals(1, lifecycle.trackedCount())

        disposalBlock.countDown()
        disposalThread.join(5_000)

        assertEquals(0, lifecycle.settlingCount())
        assertEquals(0, lifecycle.activeEncodingOwnershipCount())
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
    }

    @Test
    fun concurrentSettleEncodingDoesNotDoubleDispose() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val disposeCount = AtomicInteger(0)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            disposeCount.incrementAndGet()
        }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        val start = CountDownLatch(2)
        val done = CountDownLatch(2)
        val threads = listOf(
            Thread {
                start.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS))
                lifecycle.settleEncoding(item, accounting)
                done.countDown()
            },
            Thread {
                start.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS))
                lifecycle.settleEncoding(item, accounting)
                done.countDown()
            }
        )
        threads.forEach { it.start() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        threads.forEach { it.join(5_000) }

        // Only one settlement attempt won; only one disposal
        assertEquals(1, disposeCount.get())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
        assertEquals(0, lifecycle.trackedCount())
    }

    @Test
    fun closeDuringSettlingDoesNotDrainItem() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)

        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) { }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        val result = lifecycle.startSettling(item)
        assertEquals(YuvBufferedLifecycle.SettlementResult.STARTED, result)

        val drained = lifecycle.closeAndDrainRetained()
        assertTrue(drained.isEmpty())
        assertEquals(1, lifecycle.settlingCount())
        assertEquals(1, lifecycle.trackedCount())

        lifecycle.finishSettling(item)
        item.dispose(accounting)
        assertEquals(0, lifecycle.trackedCount())
    }

    @Test
    fun settlementThrowInDisposeContainmentFinishesTracking() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val throwCount = AtomicInteger(0)

        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            throwCount.incrementAndGet()
            error("onRelease threw")
        }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        // settleEncoding propagates disposal error to caller; finishSettling still runs
        val thrown = try {
            lifecycle.settleEncoding(item, accounting)
            null
        } catch (t: Throwable) {
            t
        }
        assertEquals(1, throwCount.get())
        assertTrue(thrown != null)
        assertEquals("onRelease threw", thrown!!.message)

        // Item removed from tracking despite disposal failure (finally ran)
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0, lifecycle.settlingCount())
    }

    @Test
    fun unknownItemStartSettlingReturnsUnknown() {
        val lifecycle = YuvBufferedLifecycle()
        val item = YuvPngWorkItem.ownedForTest { }
        assertEquals(YuvBufferedLifecycle.SettlementResult.UNKNOWN, lifecycle.startSettling(item))
        assertEquals(0, lifecycle.trackedCount())
    }

    @Test
    fun alreadyReleasedItemStartSettlingReturnsAlreadyReleased() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))
        lifecycle.settleEncoding(item, accounting)
        assertEquals(0, lifecycle.trackedCount())

        assertEquals(YuvBufferedLifecycle.SettlementResult.ALREADY_RELEASED, lifecycle.startSettling(item))
    }

    @Test
    fun repeatedStartSettlingDoesNotDisposeAgain() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val disposeCount = AtomicInteger(0)

        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            disposeCount.incrementAndGet()
        }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        // First startSettling wins
        assertEquals(YuvBufferedLifecycle.SettlementResult.STARTED, lifecycle.startSettling(item))
        // Second returns ALREADY_SETTLING (item is now SETTLING)
        assertEquals(YuvBufferedLifecycle.SettlementResult.ALREADY_SETTLING, lifecycle.startSettling(item))

        // Only one disposal happens: perform the actual disposal and finish
        item.dispose(accounting)
        lifecycle.finishSettling(item)

        assertEquals(1, disposeCount.get())
        assertEquals(0, lifecycle.trackedCount())
    }
}