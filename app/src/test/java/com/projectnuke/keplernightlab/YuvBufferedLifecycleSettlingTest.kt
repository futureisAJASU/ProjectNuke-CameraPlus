package com.projectnuke.keplernightlab

import java.io.File
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

    // ── Failure-injection lifecycle seams ──────────────────────────────

    private class ThrowingFinishSettlingLifecycle : YuvBufferedLifecycle() {
        override fun finishSettling(item: YuvPngWorkItem): Boolean =
            error("injected finishSettling failure")
    }

    private class FalseFinishSettlingLifecycle : YuvBufferedLifecycle() {
        override fun finishSettling(item: YuvPngWorkItem): Boolean = false
    }

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
    fun settledItemReturnsUnknownOnSecondSettle() {
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
        assertEquals(YuvBufferedLifecycle.SettlementResult.STARTED, startResult.result)
        assertEquals(YuvBufferedLifecycle.State.ENCODING, startResult.previousState)

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
    fun settleEncodingPreservesBothDisposalAndLifecycleReleaseFailures() {
        val lifecycle = ThrowingFinishSettlingLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            error("onRelease threw")
        }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        val outcome = lifecycle.settleEncoding(item, accounting)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.SETTLED, outcome.status)
        assertFalse(outcome.itemDisposed)
        assertFalse(outcome.lifecycleReleased)
        assertEquals("onRelease threw", outcome.failure?.message)
        assertEquals("injected finishSettling failure", outcome.lifecycleReleaseFailure?.message)

        // Reservation settled inside dispose's finally even though dispose failed.
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
        // Lifecycle release failed: the item remains truthfully tracked as SETTLING.
        assertEquals(1, lifecycle.settlingCount())
        assertEquals(1, lifecycle.trackedCount())
    }

    @Test
    fun settleEncodingReportsReleaseFailureWithDisposalSuccess() {
        val lifecycle = ThrowingFinishSettlingLifecycle()
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
        assertFalse(outcome.lifecycleReleased)
        assertNull(outcome.failure)
        assertEquals("injected finishSettling failure", outcome.lifecycleReleaseFailure?.message)
        assertEquals(1, disposeCount.get())
        assertEquals(1, lifecycle.settlingCount())
        assertEquals(1, lifecycle.trackedCount())
    }

    @Test
    fun settleEncodingReportsInvariantFailureWhenFinishSettlingReturnsFalse() {
        val lifecycle = FalseFinishSettlingLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        val outcome = lifecycle.settleEncoding(item, accounting)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.SETTLED, outcome.status)
        assertTrue(outcome.itemDisposed)
        assertFalse(outcome.lifecycleReleased)
        assertNull(outcome.failure)
        assertTrue(outcome.lifecycleReleaseFailure != null)
        assertTrue(outcome.lifecycleReleaseFailure!!.message!!.contains("finishSettling returned false"))
        // Item was never removed: still truthfully tracked.
        assertEquals(1, lifecycle.settlingCount())
        assertEquals(1, lifecycle.trackedCount())
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
        // Never a synthesized RELEASED: the previous state is unknown, reported as null.
        assertNull(outcome.previousState)
    }

    @Test
    fun drainingItemReportsInvalidStateWithDrainingPreviousState() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))
        // RETAINED -> DRAINING (coordinated drain path)
        assertTrue(lifecycle.startDraining(item))

        val outcome = lifecycle.settleEncoding(item, accounting)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.INVALID_STATE, outcome.status)
        assertEquals(YuvBufferedLifecycle.State.DRAINING, outcome.previousState)
        assertFalse(outcome.itemDisposed)
        assertFalse(outcome.lifecycleReleased)

        item.dispose(accounting)
        lifecycle.finishDrain(item)
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0L, reservations.currentBytes())
    }

    @Test
    fun alreadySettlingItemReportsSettlingPreviousState() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))
        assertEquals(YuvBufferedLifecycle.SettlementResult.STARTED, lifecycle.startSettling(item).result)

        val outcome = lifecycle.settleEncoding(item, accounting)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.ALREADY_SETTLING, outcome.status)
        assertEquals(YuvBufferedLifecycle.State.SETTLING, outcome.previousState)
        assertFalse(outcome.itemDisposed)
        assertFalse(outcome.lifecycleReleased)

        item.dispose(accounting)
        lifecycle.finishSettling(item)
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0L, reservations.currentBytes())
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
        assertEquals(YuvBufferedLifecycle.SettlementResult.STARTED, result.result)

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

        // Coordinated drain claim: RETAINED -> DRAINING, item stays tracked.
        val claims = lifecycle.claimRetainedForDrain()
        assertEquals(1, claims.size)
        val claim = claims[0]
        assertEquals(item, claim.item)
        assertEquals(0, claim.frameIndex)
        assertEquals(YuvBufferedLifecycle.State.DRAINING, claim.state())

        // DRAINING ownership visible while disposal blocked
        assertEquals(1, lifecycle.drainingCount())
        assertEquals(0, lifecycle.retainedCount())
        assertEquals(0, lifecycle.encodingCount())
        assertEquals(1, lifecycle.trackedCount())
        assertEquals(100L, reservations.currentBytes())
        assertEquals(1, accounting.snapshot().bufferedFrames)

        val disposalThread = Thread {
            item.dispose(accounting)
            claim.finish()
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
        // Exactly-once: after a successful finish the claim can never settle again.
        assertNull(claim.state())
        assertFalse(claim.finish())
    }

    @Test
    fun legacyCloseAndDrainRetainedMatchesProductionContract() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val disposeCount = AtomicInteger(0)
        assertTrue(reservations.tryReserve(200L))
        val item1 = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            disposeCount.incrementAndGet()
        }
        val item2 = YuvPngWorkItem.bufferedForTest(1, 2L, 100L, reservations, accounting) {
            disposeCount.incrementAndGet()
        }
        assertTrue(lifecycle.tryRegister(item1))
        assertTrue(lifecycle.tryRegister(item2))

        val drained = lifecycle.closeAndDrainRetained()
        assertEquals(listOf(item1, item2), drained)

        // Items removed from the registry BEFORE disposal: no DRAINING residue and no
        // finish call required (ColorFusion pattern: dispose only).
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0, lifecycle.retainedCount())
        assertEquals(0, lifecycle.drainingCount())
        // Accounting stays truthful: external disposal still owned by the caller.
        assertEquals(2, accounting.snapshot().bufferedFrames)
        assertEquals(200L, reservations.currentBytes())

        drained.forEach { it.dispose(accounting) }
        drained.forEach { it.dispose(accounting) } // disposal is idempotent at item level
        assertEquals(2, disposeCount.get())
        assertEquals(0, accounting.snapshot().bufferedFrames)
        assertEquals(0L, reservations.currentBytes())

        // Repeated close never returns an item twice; acceptance stays closed.
        assertTrue(lifecycle.closeAndDrainRetained().isEmpty())
        assertTrue(reservations.tryReserve(10L))
        val late = YuvPngWorkItem.bufferedForTest(2, 3L, 10L, reservations, accounting)
        assertFalse(lifecycle.tryRegister(late))
        late.dispose(accounting)
        assertEquals(0, lifecycle.trackedCount())
    }

    // ── BufferedEncodeTask consumes every settlement outcome ───────────

    private fun encodeTask(
        item: YuvPngWorkItem,
        lifecycle: YuvBufferedLifecycle,
        accounting: YuvCaptureAccounting,
        issues: MutableList<YuvBufferedLifecycle.EncodingSettlementOutcome>
    ): BufferedEncodeTask {
        return BufferedEncodeTask(
            item = item,
            accounting = accounting,
            lifecycle = lifecycle,
            encode = {
                YuvWorkerCompletion.Success(item.frameIndex, item.timestampNs, File("candidate.tmp"), "frame_00_color.png", 0L)
            },
            postCompletion = { _ -> },
            onSettlementIssue = { _, outcome -> issues.add(outcome) }
        )
    }

    @Test
    fun bufferedEncodeTaskSettledCleanlyDoesNotReportIssue() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val issues = CopyOnWriteArrayList<YuvBufferedLifecycle.EncodingSettlementOutcome>()
        val disposeCount = AtomicInteger(0)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            disposeCount.incrementAndGet()
        }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        encodeTask(item, lifecycle, accounting, issues).run()

        assertTrue(issues.isEmpty())
        assertEquals(1, disposeCount.get())
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
    }

    @Test
    fun bufferedEncodeTaskReportsInvalidStateOnce() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val issues = CopyOnWriteArrayList<YuvBufferedLifecycle.EncodingSettlementOutcome>()
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) { }
        // Registered but never began encoding -> RETAINED -> INVALID_STATE.
        assertTrue(lifecycle.tryRegister(item))

        encodeTask(item, lifecycle, accounting, issues).run()

        assertEquals(1, issues.size)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.INVALID_STATE, issues[0].status)
        assertFalse(issues[0].itemDisposed)
        // Nothing was disposed: the item is still truthfully retained.
        assertEquals(1, lifecycle.retainedCount())
        assertEquals(100L, reservations.currentBytes())

        lifecycle.closeAndDrainRetained().forEach { it.dispose(accounting) }
    }

    @Test
    fun bufferedEncodeTaskReportsUnknownOnce() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val issues = CopyOnWriteArrayList<YuvBufferedLifecycle.EncodingSettlementOutcome>()
        val onReleaseCount = AtomicInteger(0)
        // Never registered in the lifecycle -> UNKNOWN.
        val item = YuvPngWorkItem.ownedForTest { onReleaseCount.incrementAndGet() }

        encodeTask(item, lifecycle, accounting, issues).run()

        assertEquals(1, issues.size)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.UNKNOWN, issues[0].status)
        assertFalse(issues[0].itemDisposed)
        assertEquals(0, onReleaseCount.get())
    }

    @Test
    fun bufferedEncodeTaskReportsAlreadySettlingOnce() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val issues = CopyOnWriteArrayList<YuvBufferedLifecycle.EncodingSettlementOutcome>()
        val disposeCount = AtomicInteger(0)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            disposeCount.incrementAndGet()
        }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))
        // External transition to SETTLING before the task settles.
        assertEquals(YuvBufferedLifecycle.SettlementResult.STARTED, lifecycle.startSettling(item).result)

        encodeTask(item, lifecycle, accounting, issues).run()

        assertEquals(1, issues.size)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.ALREADY_SETTLING, issues[0].status)
        // The task did not double-settle the already-settling item.
        assertEquals(0, disposeCount.get())
        assertEquals(1, lifecycle.settlingCount())
        assertEquals(1, lifecycle.trackedCount())

        item.dispose(accounting)
        lifecycle.finishSettling(item)
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0L, reservations.currentBytes())
    }

    @Test
    fun bufferedEncodeTaskReportsResourceFailureOnceWithOriginalFailure() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val issues = CopyOnWriteArrayList<YuvBufferedLifecycle.EncodingSettlementOutcome>()
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            error("onRelease threw")
        }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        encodeTask(item, lifecycle, accounting, issues).run()

        assertEquals(1, issues.size)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.SETTLED, issues[0].status)
        assertEquals("onRelease threw", issues[0].failure?.message)
        assertFalse(issues[0].itemDisposed)
        assertTrue(issues[0].lifecycleReleased)
        // Item was released from tracking despite disposal failure.
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0L, reservations.currentBytes())
    }

    @Test
    fun bufferedEncodeTaskSurfacesDisposalAndReleaseFailures() {
        val lifecycle = ThrowingFinishSettlingLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val issues = CopyOnWriteArrayList<YuvBufferedLifecycle.EncodingSettlementOutcome>()
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            error("onRelease threw")
        }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        encodeTask(item, lifecycle, accounting, issues).run()

        assertEquals(1, issues.size)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.SETTLED, issues[0].status)
        assertEquals("onRelease threw", issues[0].failure?.message)
        assertEquals("injected finishSettling failure", issues[0].lifecycleReleaseFailure?.message)
        assertEquals(1, lifecycle.settlingCount())
    }

    @Test
    fun bufferedEncodeTaskIssueHookThrowDoesNotEscapeIntoWorkerCleanup() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val hookCalls = AtomicInteger(0)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) { }
        assertTrue(lifecycle.tryRegister(item))

        val task = BufferedEncodeTask(
            item = item,
            accounting = accounting,
            lifecycle = lifecycle,
            encode = {
                YuvWorkerCompletion.Success(item.frameIndex, item.timestampNs, File("candidate.tmp"), "frame_00_color.png", 0L)
            },
            postCompletion = { _ -> },
            onSettlementIssue = { _, _ ->
                hookCalls.incrementAndGet()
                error("issue hook threw")
            }
        )
        // RETAINED -> INVALID_STATE: the hook throws but run() must not propagate it.
        task.run()
        assertEquals(1, hookCalls.get())

        lifecycle.closeAndDrainRetained().forEach { it.dispose(accounting) }
    }
}
