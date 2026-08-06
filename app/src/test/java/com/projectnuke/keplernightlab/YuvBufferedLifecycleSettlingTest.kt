package com.projectnuke.keplernightlab

<<<<<<< HEAD
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
=======
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
>>>>>>> be11772742d7dc65106ec7fa4b18531fad76e07f
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
<<<<<<< HEAD
import org.junit.Assert.assertNull
=======
>>>>>>> be11772742d7dc65106ec7fa4b18531fad76e07f
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvBufferedLifecycleSettlingTest {

<<<<<<< HEAD
    // ── settleEncoding: outcome and resource correctness ────────────────

=======
>>>>>>> be11772742d7dc65106ec7fa4b18531fad76e07f
    @Test
    fun settleEncodingOnRetainedItemIsInvalidState() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))

<<<<<<< HEAD
        val outcome = lifecycle.settleEncoding(item, accounting)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.INVALID_STATE, outcome.status)
        assertFalse(outcome.itemDisposed)
        assertFalse(outcome.lifecycleReleased)
=======
        assertEquals(YuvBufferedLifecycle.SettlementResult.INVALID_STATE, lifecycle.startSettling(item))
>>>>>>> be11772742d7dc65106ec7fa4b18531fad76e07f
        assertEquals(0, lifecycle.settlingCount())
        assertEquals(1, lifecycle.retainedCount())
        assertEquals(1, accounting.snapshot().bufferedFrames)
        assertEquals(100L, reservations.currentBytes())

<<<<<<< HEAD
=======
        // settleEncoding on RETAINED: startSettling returns INVALID_STATE, no disposal
        lifecycle.settleEncoding(item, accounting)
        assertEquals(1, lifecycle.retainedCount())
        assertEquals(100L, reservations.currentBytes())
>>>>>>> be11772742d7dc65106ec7fa4b18531fad76e07f
        lifecycle.closeAndDrainRetained().forEach { it.dispose(accounting) }
    }

    @Test
<<<<<<< HEAD
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
=======
    fun settlingKeepsOwnershipVisibleWhileDisposalBlocked() {
>>>>>>> be11772742d7dc65106ec7fa4b18531fad76e07f
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

<<<<<<< HEAD
        val startResult = lifecycle.startSettling(item)
        assertEquals(YuvBufferedLifecycle.SettlementResult.STARTED, startResult)

        // State is SETTLING: ownership visible
=======
        val settlingResult = lifecycle.startSettling(item)
        assertEquals(YuvBufferedLifecycle.SettlementResult.STARTED, settlingResult)

        // State is SETTLING: item remains tracked, ownership visible
>>>>>>> be11772742d7dc65106ec7fa4b18531fad76e07f
        assertEquals(1, lifecycle.settlingCount())
        assertEquals(1, lifecycle.activeEncodingOwnershipCount())
        assertEquals(1, lifecycle.trackedCount())
        assertEquals(100L, reservations.currentBytes())
        assertEquals(1, accounting.snapshot().bufferedFrames)

        // Run disposal on a separate thread so it blocks independently
        val disposalThread = Thread {
<<<<<<< HEAD
=======
            item.settleBufferedAccounting(accounting)
>>>>>>> be11772742d7dc65106ec7fa4b18531fad76e07f
            item.dispose(accounting)
            lifecycle.finishSettling(item)
        }
        disposalThread.start()
        assertTrue(disposalStarted.await(2, TimeUnit.SECONDS))

        // While disposal blocked, ownership still visible
        assertEquals(1, lifecycle.settlingCount())
        assertEquals(1, lifecycle.activeEncodingOwnershipCount())
        assertEquals(1, lifecycle.trackedCount())
<<<<<<< HEAD
        assertTrue(disposalThread.isAlive)

        disposalBlock.countDown()
        disposalThread.join(5_000)
        assertFalse(disposalThread.isAlive)
=======

        disposalBlock.countDown()
        disposalThread.join(5_000)
>>>>>>> be11772742d7dc65106ec7fa4b18531fad76e07f

        assertEquals(0, lifecycle.settlingCount())
        assertEquals(0, lifecycle.activeEncodingOwnershipCount())
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
    }

    @Test
<<<<<<< HEAD
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
=======
    fun concurrentSettleEncodingDoesNotDoubleDispose() {
>>>>>>> be11772742d7dc65106ec7fa4b18531fad76e07f
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val disposeCount = AtomicInteger(0)
<<<<<<< HEAD

=======
>>>>>>> be11772742d7dc65106ec7fa4b18531fad76e07f
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            disposeCount.incrementAndGet()
        }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

<<<<<<< HEAD
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
=======
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
>>>>>>> be11772742d7dc65106ec7fa4b18531fad76e07f
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

<<<<<<< HEAD
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
=======
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
>>>>>>> be11772742d7dc65106ec7fa4b18531fad76e07f
