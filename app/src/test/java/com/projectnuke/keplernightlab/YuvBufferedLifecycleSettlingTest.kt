package com.projectnuke.keplernightlab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvBufferedLifecycleSettlingTest {

    // ── settleEncoding: outcome and resource correctness ────────────────

    @Test
    fun settleEncodingOnRetainedItemIsInvalidState() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))

        val outcome = lifecycle.settleEncoding(item, accounting)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.INVALID_STATE, outcome.status)
        assertFalse(outcome.itemDisposed)
        assertFalse(outcome.lifecycleReleased)
        assertEquals(0, lifecycle.settlingCount())
        assertEquals(1, lifecycle.retainedCount())
        assertEquals(1, accounting.snapshot().bufferedFrames)
        assertEquals(100L, reservations.currentBytes())

        lifecycle.closeAndDrainRetained().forEach { it.dispose(accounting) }
    }

    @Test
    fun settleEncodingOnEncodingItemSucceedsAndRemovesFromRegistry() {
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

        val outcome = lifecycle.settleEncoding(item, accounting)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.SETTLED, outcome.status)
        assertTrue(outcome.itemDisposed)
        assertTrue(outcome.lifecycleReleased)
        assertNull(outcome.failure)
        assertEquals(1, disposeCount.get())
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0, lifecycle.settlingCount())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
    }

    @Test
    fun settleEncodingOnUnknownItemReturnsUnknown() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val item = YuvPngWorkItem.ownedForTest { }

        val outcome = lifecycle.settleEncoding(item, accounting)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.UNKNOWN, outcome.status)
        assertFalse(outcome.itemDisposed)
        assertFalse(outcome.lifecycleReleased)
        assertNull(outcome.failure)
    }

    @Test
    fun settledItemReturnsAlreadyReleasedOnSecondSettle() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))
        lifecycle.settleEncoding(item, accounting)
        assertEquals(0, lifecycle.trackedCount())

        val outcome = lifecycle.settleEncoding(item, accounting)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.UNKNOWN, outcome.status)
        assertFalse(outcome.itemDisposed)
        assertFalse(outcome.lifecycleReleased)
        assertNull(outcome.failure)
    }

    @Test
    fun concurrentSettleEncodingProducesOneSTARTED() {
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
        val results = CopyOnWriteArrayList<YuvBufferedLifecycle.EncodingSettlementOutcome>()
        val threads = listOf(
            Thread {
                start.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS))
                results.add(lifecycle.settleEncoding(item, accounting))
                done.countDown()
            },
            Thread {
                start.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS))
                results.add(lifecycle.settleEncoding(item, accounting))
                done.countDown()
            }
        )
        threads.forEach { it.start() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        threads.forEach {
            it.join(5_000)
            assertFalse("${it.name} still alive", it.isAlive)
        }

        assertEquals(1, results.count { it.status == YuvBufferedLifecycle.EncodingSettlementStatus.SETTLED })
        assertEquals(1, disposeCount.get())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
        assertEquals(0, lifecycle.trackedCount())
    }

    @Test
    fun settingKeepsOwnershipVisibleWhileDisposalBlocked() {
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

        val startResult = lifecycle.startSettling(item)
        assertEquals(YuvBufferedLifecycle.SettlementResult.STARTED, startResult)

        // State is SETTLING: ownership visible
        assertEquals(1, lifecycle.settlingCount())
        assertEquals(1, lifecycle.activeEncodingOwnershipCount())
        assertEquals(1, lifecycle.trackedCount())
        assertEquals(100L, reservations.currentBytes())
        assertEquals(1, accounting.snapshot().bufferedFrames)

        // Run disposal on a separate thread so it blocks independently
        val disposalThread = Thread {
            item.dispose(accounting)
            lifecycle.finishSettling(item)
        }
        disposalThread.start()
        assertTrue(disposalStarted.await(2, TimeUnit.SECONDS))

        // While disposal blocked, ownership still visible
        assertEquals(1, lifecycle.settlingCount())
        assertEquals(1, lifecycle.activeEncodingOwnershipCount())
        assertEquals(1, lifecycle.trackedCount())
        assertTrue(disposalThread.isAlive)

        disposalBlock.countDown()
        disposalThread.join(5_000)
        assertFalse(disposalThread.isAlive)

        assertEquals(0, lifecycle.settlingCount())
        assertEquals(0, lifecycle.activeEncodingOwnershipCount())
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
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

        // settleEncoding catches disposal failure, still calls finishSettling (releasing lifecycle)
        val outcome = lifecycle.settleEncoding(item, accounting)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.SETTLED, outcome.status)
        assertFalse(outcome.itemDisposed)
        assertTrue(outcome.lifecycleReleased)
        assertTrue(outcome.failure != null)
        assertEquals("onRelease threw", outcome.failure!!.message)
        assertEquals(1, throwCount.get())

        // Item removed from tracking despite disposal failure (finally ran)
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0, lifecycle.settlingCount())
    }

    @Test
    fun settleBufferedAccountingFailureDoesNotSkipItemDisposal() {
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

        val outcome = lifecycle.settleEncoding(item, accounting)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.SETTLED, outcome.status)
        assertTrue(outcome.lifecycleReleased)
        assertEquals(1, disposeCount.get())
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0L, reservations.currentBytes())
    }

    @Test
    fun invalidStateIsExplicitlyReturned() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))

        val outcome = lifecycle.settleEncoding(item, accounting)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.INVALID_STATE, outcome.status)
        assertEquals(YuvBufferedLifecycle.State.RETAINED, outcome.previousState)
    }

    @Test
    fun unknownIsExplicitlyReturned() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val untracked = YuvPngWorkItem.ownedForTest { }

        val outcome = lifecycle.settleEncoding(untracked, accounting)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.UNKNOWN, outcome.status)
        assertEquals(YuvBufferedLifecycle.State.RELEASED, outcome.previousState)
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

        // Dispose first, then finish settling (per spec: disposal then finishSettling)
        item.dispose(accounting)
        assertEquals(1, lifecycle.settlingCount())
        lifecycle.finishSettling(item)
        assertEquals(0, lifecycle.trackedCount())
    }

    // ── Drain lifecycle ─────────────────────────────────────────────────

    @Test
    fun drainRetainedItemRemainsVisibleWhileDisposalBlocked() {
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

        val drained = lifecycle.closeAndDrainRetained()
        assertEquals(listOf(item), drained)

        // DRAINING ownership visible while disposal blocked
        assertEquals(1, lifecycle.drainingCount())
        assertEquals(0, lifecycle.retainedCount())
        assertEquals(0, lifecycle.encodingCount())
        assertEquals(1, lifecycle.trackedCount())
        assertEquals(100L, reservations.currentBytes())
        assertEquals(1, accounting.snapshot().bufferedFrames)

        val disposalThread = Thread {
            item.dispose(accounting)
            lifecycle.finishDrain(item)
        }
        disposalThread.start()
        assertTrue(disposalStarted.await(2, TimeUnit.SECONDS))
        assertTrue(disposalThread.isAlive)

        // While disposal blocked, DRAINING ownership still visible (item not yet finished)
        assertEquals(1, lifecycle.drainingCount())
        assertEquals(1, lifecycle.trackedCount())

        disposalBlock.countDown()
        disposalThread.join(5_000)
        assertFalse(disposalThread.isAlive)

        assertEquals(0, lifecycle.drainingCount())
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
    }
}
