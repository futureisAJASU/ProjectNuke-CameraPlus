package com.projectnuke.keplernightlab

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun bufferedEncodeTaskNormalRunUncleanOutcomeReachesWorkDisposalDebt() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val issues = CopyOnWriteArrayList<YuvBufferedLifecycle.EncodingSettlementOutcome>()
        val debts = CopyOnWriteArrayList<Pair<YuvPngWorkItem, YuvWorkDisposalOutcome>>()
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            error("onRelease threw")
        }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        BufferedEncodeTask(
            item = item,
            accounting = accounting,
            lifecycle = lifecycle,
            candidateFilesystem = RealYuvCandidateFilesystem,
            encode = {
                YuvWorkerCompletion.Success(
                    0, 1L, YuvCandidateHandle(0, File("candidate.tmp")), "frame_00_color.png", 0L
                )
            },
            postCompletion = { _ -> },
            onSettlementIssue = { _, outcome -> issues.add(outcome) },
            onWorkDisposalDebt = { workItem, outcome -> debts.add(workItem to outcome) }
        ).run()

        // The task's NORMAL run settled with an unclean work-item disposal (the
        // release observer threw): the debt hook receives the item and its truthful
        // outcome instead of the failure being silently passed.
        assertEquals(1, debts.size)
        assertEquals(item, debts[0].first)
        assertFalse(debts[0].second.isClean)
        assertEquals("onRelease threw", debts[0].second.releaseObserverFailure?.message)
        assertEquals(1, issues.size)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.SETTLED, issues[0].status)
        assertEquals("onRelease threw", issues[0].failure?.message)
    }

    @Test
    fun repeatedBufferedEncodeTaskDisposalPreservesFirstFailedOutcome() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val debtCount = AtomicInteger(0)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            error("onRelease threw")
        }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))
        val task = BufferedEncodeTask(
            item = item,
            accounting = accounting,
            lifecycle = lifecycle,
            candidateFilesystem = RealYuvCandidateFilesystem,
            encode = {
                YuvWorkerCompletion.Success(
                    0, 1L, YuvCandidateHandle(0, File("candidate.tmp")), "frame_00_color.png", 0L
                )
            },
            postCompletion = { _ -> },
            onWorkDisposalDebt = { _, _ -> debtCount.incrementAndGet() }
        )

        val first = task.disposeWithOutcome()
        val second = task.disposeWithOutcome()

        assertTrue(first is CaptureTaskDisposalOutcome.Unclean)
        assertTrue(second is CaptureTaskDisposalOutcome.Unclean)
        // The repeated disposal preserves the FIRST failed outcome (same description,
        // never an empty clean result), and the debt hook fired exactly once.
        assertEquals(
            (first as CaptureTaskDisposalOutcome.Unclean).description,
            (second as CaptureTaskDisposalOutcome.Unclean).description
        )
        assertEquals(1, debtCount.get())
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
        assertEquals(YuvBufferedLifecycle.State.DRAINING, claim.lifecycleState())
        assertEquals(YuvDrainClaim.State.CLAIMED, claim.claimState())

        // DRAINING ownership visible while disposal blocked
        assertEquals(1, lifecycle.drainingCount())
        assertEquals(0, lifecycle.retainedCount())
        assertEquals(0, lifecycle.encodingCount())
        assertEquals(1, lifecycle.trackedCount())
        assertEquals(100L, reservations.currentBytes())
        assertEquals(1, accounting.snapshot().bufferedFrames)

        val disposalThread = Thread {
            claim.disposeAndFinish(accounting)
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
        // Exactly-once: after a successful settlement the claim can never settle again.
        assertEquals(YuvDrainClaim.State.SETTLED, claim.claimState())
        assertNull(claim.lifecycleState())
        val again = claim.disposeAndFinish(accounting)
        assertEquals(DrainSettlementStatus.ALREADY_SETTLED, again.status)
        assertEquals(0L, reservations.currentBytes())
    }

    @Test
    fun disposeAndFinishSettlesClaimExactlyOnce() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val disposeCount = AtomicInteger(0)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            disposeCount.incrementAndGet()
        }
        assertTrue(lifecycle.tryRegister(item))

        val claim = lifecycle.claimRetainedForDrain().single()
        val outcome = claim.disposeAndFinish(accounting)

        assertEquals(DrainSettlementStatus.SETTLED, outcome.status)
        assertTrue(outcome.lifecycleReleased)
        assertNull(outcome.lifecycleReleaseFailure)
        assertTrue(outcome.disposal.isClean)
        assertEquals(1, disposeCount.get())
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0, lifecycle.drainingCount())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)

        val again = claim.disposeAndFinish(accounting)
        assertEquals(DrainSettlementStatus.ALREADY_SETTLED, again.status)
        assertFalse(again.disposal.disposalAttempted)
        assertEquals(1, disposeCount.get())
    }

    @Test
    fun concurrentDisposeAndFinishPerformsDisposalExactlyOnce() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val disposeCount = AtomicInteger(0)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            disposeCount.incrementAndGet()
        }
        assertTrue(lifecycle.tryRegister(item))

        val claim = lifecycle.claimRetainedForDrain().single()
        val start = CountDownLatch(2)
        val done = CountDownLatch(2)
        val results = CopyOnWriteArrayList<DrainSettlementOutcome>()
        val threads = listOf(
            Thread {
                start.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS))
                results.add(claim.disposeAndFinish(accounting))
                done.countDown()
            },
            Thread {
                start.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS))
                results.add(claim.disposeAndFinish(accounting))
                done.countDown()
            }
        )
        threads.forEach { it.start() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        threads.forEach {
            it.join(5_000)
            assertFalse("${it.name} still alive", it.isAlive)
        }

        assertEquals(1, results.count { it.status == DrainSettlementStatus.SETTLED })
        assertEquals(1, disposeCount.get())
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
    }

    @Test
    fun disposeAndFinishFailureKeepsDrainDebtObservable() {
        val lifecycle = object : YuvBufferedLifecycle() {
            override fun finishDrain(item: YuvPngWorkItem): Boolean = false
        }
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))

        val claim = lifecycle.claimRetainedForDrain().single()
        val outcome = claim.disposeAndFinish(accounting)

        assertEquals(DrainSettlementStatus.FAILED, outcome.status)
        assertFalse(outcome.lifecycleReleased)
        assertTrue(outcome.lifecycleReleaseFailure != null)
        assertTrue(outcome.lifecycleReleaseFailure!!.message!!.contains("finishDrain returned false"))
        // Disposal still ran (independent boundary)...
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
        // ...but the lifecycle debt stays observable.
        assertEquals(1, lifecycle.drainingCount())
        assertEquals(1, lifecycle.trackedCount())
        assertEquals(YuvDrainClaim.State.FAILED, claim.claimState())
        assertEquals(DrainSettlementStatus.ALREADY_FAILED, claim.disposeAndFinish(accounting).status)
    }

    @Test
    fun disposeAndFinishThrowKeepsDrainDebtObservable() {
        val lifecycle = object : YuvBufferedLifecycle() {
            override fun finishDrain(item: YuvPngWorkItem): Boolean =
                error("injected finishDrain failure")
        }
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))

        val claim = lifecycle.claimRetainedForDrain().single()
        val outcome = claim.disposeAndFinish(accounting)

        assertEquals(DrainSettlementStatus.FAILED, outcome.status)
        assertEquals("injected finishDrain failure", outcome.lifecycleReleaseFailure?.message)
        assertEquals(1, lifecycle.drainingCount())
        assertEquals(1, lifecycle.trackedCount())
        assertEquals(0L, reservations.currentBytes())
    }

    @Test
    fun disposeAndFinishWithUncleanDisposalFailsWithoutFinishingDrain() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            error("onRelease threw")
        }
        assertTrue(lifecycle.tryRegister(item))

        val claim = lifecycle.claimRetainedForDrain().single()
        val outcome = claim.disposeAndFinish(accounting)

        // Unclean disposal: finishDrain must NOT run, the claim fails and the item
        // remains DRAINING; the disposal failure stays observable, never settled over.
        assertEquals(DrainSettlementStatus.FAILED, outcome.status)
        assertFalse(outcome.lifecycleReleased)
        assertNull(outcome.lifecycleReleaseFailure)
        assertFalse(outcome.disposal.isClean)
        assertEquals(listOf("onRelease threw"), outcome.disposal.failures().map { it.message })
        assertEquals(1, lifecycle.drainingCount())
        assertEquals(1, lifecycle.trackedCount())
        assertEquals(YuvDrainClaim.State.FAILED, claim.claimState())
        // The reservation/accounting were settled by dispose before the observer threw.
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
        // A repeated settlement mirrors the original failure diagnostics.
        val again = claim.disposeAndFinish(accounting)
        assertEquals(DrainSettlementStatus.ALREADY_FAILED, again.status)
        assertEquals(listOf("onRelease threw"), again.disposal.failures().map { it.message })
        assertTrue(again.disposal.alreadySettled)
    }

    @Test
    fun disposeAndFinishWithoutAccountingFailsBeforeDisposing() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val disposeCount = AtomicInteger(0)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            disposeCount.incrementAndGet()
        }
        assertTrue(lifecycle.tryRegister(item))

        val claim = lifecycle.claimRetainedForDrain().single()
        val outcome = claim.disposeAndFinish(accounting = null)

        // A coordinated buffered claim requires accounting: the claim fails BEFORE any
        // disposal runs, and the item remains DRAINING with the debt observable.
        assertEquals(DrainSettlementStatus.FAILED, outcome.status)
        assertEquals(0, disposeCount.get())
        assertFalse(outcome.disposal.disposalAttempted)
        assertFalse(outcome.lifecycleReleased)
        assertNotNull(outcome.lifecycleReleaseFailure)
        assertTrue(outcome.lifecycleReleaseFailure!!.message!!.contains("accounting"))
        assertEquals(1, lifecycle.drainingCount())
        assertEquals(1, lifecycle.trackedCount())
        assertEquals(YuvDrainClaim.State.FAILED, claim.claimState())
        assertEquals(100L, reservations.currentBytes())
        assertEquals(1, accounting.snapshot().bufferedFrames)
        // Repeats report the same failed settlement without disposing.
        assertEquals(DrainSettlementStatus.ALREADY_FAILED, claim.disposeAndFinish(null).status)
        assertEquals(0, disposeCount.get())
        // The claim can be recovered by settling the item through the normal path.
        item.settleBufferedAccounting(accounting)
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
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
            candidateFilesystem = RealYuvCandidateFilesystem,
            encode = {
                YuvWorkerCompletion.Success(
                    item.frameIndex, item.timestampNs,
                    YuvCandidateHandle(item.frameIndex, File("candidate.tmp")),
                    "frame_00_color.png", 0L
                )
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

    // ── Item 7: settlement issue + disposal failure exact debt counts ─────

    @Test
    fun settlementIssuePlusDisposalFailureRecordsExactDebtCount() {
        val lifecycle = ThrowingFinishSettlingLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val issues = CopyOnWriteArrayList<YuvBufferedLifecycle.EncodingSettlementOutcome>()
        val debtDescriptions = CopyOnWriteArrayList<String>()
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            error("onRelease threw")
        }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        BufferedEncodeTask(
            item = item,
            accounting = accounting,
            lifecycle = lifecycle,
            candidateFilesystem = RealYuvCandidateFilesystem,
            encode = {
                YuvWorkerCompletion.Success(
                    0, 1L, YuvCandidateHandle(0, File("candidate.tmp")), "frame_00_color.png", 0L
                )
            },
            postCompletion = { _ -> },
            onSettlementIssue = { _, outcome -> issues.add(outcome) },
            onWorkDisposalDebt = { workItem, outcome ->
                debtDescriptions.add(disposalDescription(outcome, workItem.frameIndex))
            }
        ).run()

        // Item 7: disposal debt recorded exactly once (by onWorkDisposalDebt), not
        // duplicated by onSettlementIssue.  Settlement issue recorded exactly once.
        assertEquals(1, debtDescriptions.size)
        assertTrue(debtDescriptions[0].contains("work-item disposal unclean frame=0"))
        assertEquals(1, issues.size)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.SETTLED, issues[0].status)
        assertEquals("onRelease threw", issues[0].failure?.message)
        assertEquals("injected finishSettling failure", issues[0].lifecycleReleaseFailure?.message)
        assertEquals(1, lifecycle.settlingCount())
        assertEquals(1, lifecycle.trackedCount())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
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
            candidateFilesystem = RealYuvCandidateFilesystem,
            encode = {
                YuvWorkerCompletion.Success(
                    item.frameIndex, item.timestampNs,
                    YuvCandidateHandle(item.frameIndex, File("candidate.tmp")),
                    "frame_00_color.png", 0L
                )
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

    // ── Step 3+4: BufferedEncodeTask state machine + publication state ─────

    @Test
    fun bufferedEncodeTaskStateTransitionsToSettledWithOutcomePublished() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val issues = CopyOnWriteArrayList<YuvBufferedLifecycle.EncodingSettlementOutcome>()
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) { }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        val task = encodeTask(item, lifecycle, accounting, issues)

        assertEquals(BufferedEncodeTask.TaskSettlementState.NOT_STARTED, task.taskState())
        assertNull(task.settledOutcome())

        task.run()

        assertEquals(BufferedEncodeTask.TaskSettlementState.SETTLED, task.taskState())
        assertNotNull(task.settledOutcome())
        assertTrue(task.settledOutcome() is CaptureTaskDisposalOutcome.Clean)
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0L, reservations.currentBytes())
    }

    @Test
    fun bufferedEncodeTaskSettledOutcomeIsMirroredOnRepeatDispose() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            error("onRelease threw")
        }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))
        val task = BufferedEncodeTask(
            item = item,
            accounting = accounting,
            lifecycle = lifecycle,
            candidateFilesystem = RealYuvCandidateFilesystem,
            encode = {
                YuvWorkerCompletion.Success(
                    item.frameIndex, item.timestampNs,
                    YuvCandidateHandle(item.frameIndex, File("candidate.tmp")),
                    "frame_00_color.png", 0L
                )
            },
            postCompletion = { _ -> },
            onWorkDisposalDebt = { _, _ -> }
        )

        val first = task.disposeWithOutcome()
        val second = task.disposeWithOutcome()

        assertEquals(BufferedEncodeTask.TaskSettlementState.SETTLED, task.taskState())
        assertTrue(first is CaptureTaskDisposalOutcome.Unclean)
        assertTrue(second is CaptureTaskDisposalOutcome.Unclean)
        assertEquals(
            (first as CaptureTaskDisposalOutcome.Unclean).description,
            (second as CaptureTaskDisposalOutcome.Unclean).description
        )
    }

    @Test
    fun bufferedEncodeTaskInvalidStatePublishesSettlementIssueAndDebt() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val issues = CopyOnWriteArrayList<YuvBufferedLifecycle.EncodingSettlementOutcome>()
        val debtDescriptions = CopyOnWriteArrayList<String>()
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) { }
        // Registered but never began encoding -> RETAINED -> INVALID_STATE.
        assertTrue(lifecycle.tryRegister(item))

        val task = BufferedEncodeTask(
            item = item,
            accounting = accounting,
            lifecycle = lifecycle,
            candidateFilesystem = RealYuvCandidateFilesystem,
            encode = {
                YuvWorkerCompletion.Success(
                    item.frameIndex, item.timestampNs,
                    YuvCandidateHandle(item.frameIndex, File("candidate.tmp")),
                    "frame_00_color.png", 0L
                )
            },
            postCompletion = { _ -> },
            onSettlementIssue = { _, outcome ->
                issues.add(outcome)
                // Step 4: onSettlementIssue always records debt for non-clean.
                val settledCleanly = outcome.status == YuvBufferedLifecycle.EncodingSettlementStatus.SETTLED &&
                    outcome.failure == null && outcome.lifecycleReleaseFailure == null
                assertFalse("INVALID_STATE must be non-clean", settledCleanly)
                debtDescriptions.add(
                    "bufferedTaskSettlementIssue frame=${item.frameIndex}: ${outcome.status}"
                )
            }
        )

        task.run()

        assertEquals(1, issues.size)
        assertEquals(YuvBufferedLifecycle.EncodingSettlementStatus.INVALID_STATE, issues[0].status)
        assertEquals(1, debtDescriptions.size)
        assertTrue(debtDescriptions[0].contains("INVALID_STATE"))
        assertEquals(BufferedEncodeTask.TaskSettlementState.SETTLED, task.taskState())

        lifecycle.closeAndDrainRetained().forEach { it.dispose(accounting) }
    }
}
