package com.projectnuke.keplernightlab

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2A-P2 disposal outcome: [YuvPngWorkItem.dispose] reports every sub-settlement
 * (source release, reservation release, buffered accounting release, release observer)
 * independently — a failure in one never skips the others, and repeated dispose is
 * idempotent (later calls return notAttempted()).
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
    fun repeatedDisposeIsIdempotentAndReportsNotAttempted() {
        val source = RecordingDirectSource()
        val observer = AtomicInteger(0)
        val item = YuvPngWorkItem.directOwned(0, 0L, source) { observer.incrementAndGet() }

        val first = item.dispose()
        val second = item.dispose()

        assertTrue(first.disposalAttempted)
        assertFalse(second.disposalAttempted)
        assertFalse(second.sourceReleaseAttempted)
        assertNull(second.sourceReleaseFailure)
        assertTrue(second.failures().isEmpty())
        // notAttempted(): no sub-settlement was required, so nothing failed — isClean.
        assertTrue(second.isClean)
        assertEquals(1, source.released.get())
        assertEquals(1, observer.get())
    }
}
