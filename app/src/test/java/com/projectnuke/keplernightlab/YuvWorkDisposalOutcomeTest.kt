package com.projectnuke.keplernightlab

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2A-P2 disposal outcome: [YuvPngWorkItem.dispose] reports every sub-settlement
 * (source release, reservation release, buffered accounting release, release observer)
 * independently — a failure in one never skips the others, and repeated dispose is
 * idempotent: the FIRST outcome is preserved and later calls return an already-settled
 * mirror ([YuvWorkDisposalOutcome.alreadySettled]) that keeps every failure visible.
 */
class YuvWorkDisposalOutcomeTest {

    private class ThrowingDirectSource : OwnedDirectYuvSource {
        override val timestampNs: Long = 0L
        override fun encodeTo(encoder: YuvPngEncoder, candidate: File, rotationDegrees: Int) =
            error("cannot encode")
        override fun release() = error("source release failed")
    }

    private class RecordingDirectSource : OwnedDirectYuvSource {
        val released = AtomicInteger(0)
        override val timestampNs: Long = 0L
        override fun encodeTo(encoder: YuvPngEncoder, candidate: File, rotationDegrees: Int) =
            error("cannot encode")
        override fun release() { released.incrementAndGet() }
    }

    /**
     * Deterministic release gating: release() signals entry and blocks until
     * [releaseGate], making the DISPOSING intermediate observable.
     */
    private class GatedDirectSource : OwnedDirectYuvSource {
        private val entered = CountDownLatch(1)
        private val releaseLatch = CountDownLatch(1)
        val releaseCount = AtomicInteger(0)
        override val timestampNs: Long = 0L
        override fun encodeTo(encoder: YuvPngEncoder, candidate: File, rotationDegrees: Int) =
            error("cannot encode")
        override fun release() {
            entered.countDown()
            releaseLatch.await(10, TimeUnit.SECONDS)
            releaseCount.incrementAndGet()
        }
        fun awaitEntered(timeoutSec: Long = 5) {
            assertTrue("release never entered", entered.await(timeoutSec, TimeUnit.SECONDS))
        }
        fun releaseGate() { releaseLatch.countDown() }
    }

    @Test
    fun bufferedDisposeReportsAllSubSettlementsCleanly() {
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val accounting = YuvCaptureAccounting()
        val observer = AtomicInteger(0)
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            observer.incrementAndGet()
        }

        val outcome = item.dispose(accounting)

        assertTrue(outcome.disposalAttempted)
        assertTrue(outcome.reservationReleaseAttempted)
        assertTrue(outcome.reservationReleased)
        assertNull(outcome.reservationReleaseFailure)
        assertTrue(outcome.bufferedAccountingReleased)
        assertNull(outcome.bufferedAccountingFailure)
        assertTrue(outcome.releaseObserverAttempted)
        assertTrue(outcome.releaseObserverCompleted)
        assertNull(outcome.releaseObserverFailure)
        assertTrue(outcome.isClean)
        assertFalse(outcome.failed)
        assertTrue(outcome.failures().isEmpty())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
        assertEquals(1, observer.get())
    }

    @Test
    fun sourceReleaseFailureDoesNotSkipObserver() {
        val observer = AtomicInteger(0)
        val item = YuvPngWorkItem.directOwned(0, 0L, ThrowingDirectSource()) {
            observer.incrementAndGet()
        }

        val outcome = item.dispose()

        assertTrue(outcome.disposalAttempted)
        assertTrue(outcome.sourceReleaseAttempted)
        assertFalse(outcome.sourceReleased)
        assertNotNull(outcome.sourceReleaseFailure)
        assertEquals("source release failed", outcome.sourceReleaseFailure!!.message)
        assertTrue(outcome.releaseObserverAttempted)
        assertTrue(outcome.releaseObserverCompleted)
        assertEquals(1, observer.get())
        assertTrue(outcome.failed)
        assertFalse(outcome.isClean)
        assertEquals(1, outcome.failures().size)
    }

    @Test
    fun observerFailureDoesNotSkipSourceRelease() {
        val source = RecordingDirectSource()
        val item = YuvPngWorkItem.directOwned(0, 0L, source) { error("observer failed") }

        val outcome = item.dispose()

        assertEquals(1, source.released.get())
        assertTrue(outcome.sourceReleased)
        assertNull(outcome.sourceReleaseFailure)
        assertTrue(outcome.releaseObserverAttempted)
        assertFalse(outcome.releaseObserverCompleted)
        assertNotNull(outcome.releaseObserverFailure)
        assertEquals("observer failed", outcome.releaseObserverFailure!!.message)
        assertTrue(outcome.failed)
        assertFalse(outcome.isClean)
        assertEquals(1, outcome.failures().size)
    }

    @Test
    fun accountingReleaseFailureDoesNotSkipReservationOrObserver() {
        val reservations = YuvBufferReservations(1024L)
        val reservations2 = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        assertTrue(reservations2.tryReserve(100L))
        val accounting = YuvCaptureAccounting()
        val observer = AtomicInteger(0)
        YuvPngWorkItem.bufferedForTest(1, 1L, 100L, reservations, accounting)
        // Release the first item's accounting count externally (the owner's
        // settleBufferedAccounting path), then do the same for the second item so
        // dispose()'s accounting release hits the double-release check().
        accounting.releasedBufferedFrame()
        val item = YuvPngWorkItem.bufferedForTest(2, 2L, 100L, reservations2, accounting) {
            observer.incrementAndGet()
        }
        accounting.releasedBufferedFrame()

        val outcome = item.dispose(accounting)

        assertTrue(outcome.reservationReleaseAttempted)
        assertTrue(outcome.reservationReleased)
        assertNull(outcome.reservationReleaseFailure)
        assertFalse(outcome.bufferedAccountingReleased)
        assertNotNull(outcome.bufferedAccountingFailure)
        assertTrue(outcome.bufferedAccountingFailure!! is IllegalStateException)
        assertTrue(outcome.releaseObserverCompleted)
        assertEquals(1, observer.get())
        assertTrue(outcome.failed)
        assertFalse(outcome.isClean)
        assertEquals(1, outcome.failures().size)
        assertEquals(0L, reservations2.currentBytes())
    }

    @Test
    fun repeatedDisposeReturnsTruthfulAlreadySettledMirror() {
        val source = RecordingDirectSource()
        val observer = AtomicInteger(0)
        val item = YuvPngWorkItem.directOwned(0, 0L, source) { observer.incrementAndGet() }

        val first = item.dispose()
        val second = item.dispose()

        assertTrue(first.disposalAttempted)
        assertFalse(second.disposalAttempted)
        assertTrue(second.alreadySettled)
        assertSame(first, second.originalOutcome)
        assertFalse(second.sourceReleaseAttempted)
        assertNull(second.sourceReleaseFailure)
        assertTrue(second.failures().isEmpty())
        // The mirror delegates cleanliness to the ORIGINAL outcome: no failure occurred.
        assertTrue(second.isClean)
        assertEquals(1, source.released.get())
        assertEquals(1, observer.get())
    }

    @Test
    fun repeatedDisposePreservesOriginalFailureDiagnostics() {
        val source = RecordingDirectSource()
        val observer = AtomicInteger(0)
        val item = YuvPngWorkItem.directOwned(0, 0L, ThrowingDirectSource()) {
            observer.incrementAndGet()
        }

        val first = item.dispose()
        val second = item.dispose()

        assertTrue(first.failed)
        assertFalse(first.isClean)
        assertTrue(second.alreadySettled)
        assertSame(first, second.originalOutcome)
        // The original failure is preserved in the mirror: never an empty outcome.
        assertEquals(listOf("source release failed"), second.failures().map { it.message })
        assertFalse(second.isClean)
        assertEquals(1, observer.get())
    }

    @Test
    fun bufferedDisposeWithoutAccountingIsTruthfullyNotClean() {
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val accounting = YuvCaptureAccounting()
        val observer = AtomicInteger(0)
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            observer.incrementAndGet()
        }

        val outcome = item.dispose(accounting = null)

        // The reservation was REQUIRED but could never settle without the accounting
        // handle: the outcome says so instead of pretending to be clean.
        assertTrue(outcome.disposalAttempted)
        assertTrue(outcome.reservationReleaseRequired)
        assertFalse(outcome.reservationReleaseAttempted)
        assertFalse(outcome.reservationReleased)
        assertFalse(outcome.isClean)
        assertTrue(outcome.failures().isEmpty())
        // The reservation/accounting debt stays with the caller; the observer still ran.
        assertEquals(100L, reservations.currentBytes())
        assertEquals(1, accounting.snapshot().bufferedFrames)
        assertEquals(1, observer.get())
        // The caller can settle the debt through the normal path.
        item.settleBufferedAccounting(accounting)
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
    }

    @Test
    fun secondDisposeDuringInFlightDisposingIsTruthfullyNotClean() {
        val source = GatedDirectSource()
        val item = YuvPngWorkItem.directOwned(0, 0L, source) {}

        // The first caller wins the DISPOSING transition and blocks inside release();
        // the second caller must observe IN_PROGRESS and never touch the resources.
        val disposeThread = Thread { item.dispose() }
        disposeThread.start()
        source.awaitEntered()

        val second = item.dispose()

        assertTrue(second.disposalInProgress)
        assertTrue(second.alreadyDisposedByAnother)
        assertFalse(second.isClean)
        assertTrue(second.failures().isEmpty())
        assertEquals(0, source.releaseCount.get())

        source.releaseGate()
        disposeThread.join(5_000)
        assertFalse("dispose thread still alive", disposeThread.isAlive)
        // The FIRST caller's settlement completed exactly once and cleanly.
        assertEquals(1, source.releaseCount.get())
        val first = item.disposalOutcome()
        assertNotNull(first)
        assertTrue(first!!.isClean)
        assertFalse(first.disposalInProgress)
    }
}
