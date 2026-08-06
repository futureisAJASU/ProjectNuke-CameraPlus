package com.projectnuke.keplernightlab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2A-P2 adoption token: stateful exactly-once RESERVED -> COMMITTED |
 * ROLLED_BACK transitions.  Reservation alone never touches the manifest or
 * persistedFrames; commit appends the manifest entry + increments persistedFrames;
 * rollback releases the reservation without any manifest mutation.  The
 * persistedFrames == manifest.size invariant is observable after every path.
 */
class YuvAdoptionTokenTest {

    private fun entry(frame: Int, filename: String = "frame_%02d_color.png".format(frame)) =
        YuvFrameManifestEntry(frame, filename, 4321L + frame, true)

    private fun accounting() = YuvCaptureAccounting()

    @Test
    fun reservationDoesNotTouchManifestOrPersistedFrames() {
        val a = accounting()
        val token = a.tryReserveAdoption(entry(0))
        assertNotNull(token)
        assertEquals(AdoptionTokenState.RESERVED, token!!.state())
        val snap = a.snapshot()
        assertEquals(0, snap.persistedFrames)
        assertTrue(snap.manifest.isEmpty())
        assertEquals(1, snap.reservedCount)
    }

    @Test
    fun reservationRejectsDuplicateFrameIndex() {
        val a = accounting()
        a.tryReserveAdoption(entry(0))
        assertNull(a.tryReserveAdoption(entry(0, "other.png")))
        assertEquals(1, a.snapshot().reservedCount)
    }

    @Test
    fun reservationRejectsDuplicateFilename() {
        val a = accounting()
        a.tryReserveAdoption(entry(0))
        assertNull(a.tryReserveAdoption(entry(1, "frame_00_color.png")))
        assertEquals(1, a.snapshot().reservedCount)
    }

    @Test
    fun reservationRejectsCommittedFrame() {
        val a = accounting()
        a.tryReserveAdoption(entry(0))!!.commit()
        assertNull(a.tryReserveAdoption(entry(0)))
        assertEquals(0, a.snapshot().reservedCount)
    }

    @Test
    fun commitAppendsManifestAndIncrementsPersistedFrames() {
        val a = accounting()
        val token = a.tryReserveAdoption(entry(0))!!
        assertTrue(token.commit())
        assertEquals(AdoptionTokenState.COMMITTED, token.state())
        val snap = a.snapshot()
        assertEquals(1, snap.persistedFrames)
        assertEquals(1, snap.manifest.size)
        assertEquals(0, snap.reservedCount)
        assertEquals(0, snap.manifest[0].frameIndex)
        assertEquals("frame_00_color.png", snap.manifest[0].filename)
    }

    @Test
    fun commitIsExactlyOnce() {
        val a = accounting()
        val token = a.tryReserveAdoption(entry(0))!!
        assertTrue(token.commit())
        assertFalse(token.commit())
        val snap = a.snapshot()
        assertEquals(1, snap.persistedFrames)
        assertEquals(1, snap.manifest.size)
    }

    @Test
    fun rollbackReleasesReservationWithoutManifestMutation() {
        val a = accounting()
        val token = a.tryReserveAdoption(entry(0))!!
        assertTrue(token.rollback())
        assertEquals(AdoptionTokenState.ROLLED_BACK, token.state())
        val snap = a.snapshot()
        assertEquals(0, snap.reservedCount)
        assertEquals(0, snap.persistedFrames)
        assertTrue(snap.manifest.isEmpty())
    }

    @Test
    fun rollbackIsExactlyOnce() {
        val a = accounting()
        val token = a.tryReserveAdoption(entry(0))!!
        assertTrue(token.rollback())
        assertFalse(token.rollback())
        assertEquals(0, a.snapshot().reservedCount)
    }

    @Test
    fun commitAfterRollbackIsRejected() {
        val a = accounting()
        val token = a.tryReserveAdoption(entry(0))!!
        assertTrue(token.rollback())
        assertFalse(token.commit())
        val snap = a.snapshot()
        assertEquals(0, snap.persistedFrames)
        assertTrue(snap.manifest.isEmpty())
        assertEquals(0, snap.reservedCount)
    }

    @Test
    fun rollbackAfterCommitIsRejected() {
        val a = accounting()
        val token = a.tryReserveAdoption(entry(0))!!
        assertTrue(token.commit())
        assertFalse(token.rollback())
        val snap = a.snapshot()
        assertEquals(1, snap.persistedFrames)
        assertEquals(1, snap.manifest.size)
        assertEquals(0, snap.reservedCount)
    }

    @Test
    fun concurrentCommitAndRollbackHasExactlyOneWinner() {
        val a = accounting()
        val token = a.tryReserveAdoption(entry(0))!!
        val start = CountDownLatch(2)
        val done = CountDownLatch(2)
        val commitResult = AtomicReference<Boolean>()
        val rollbackResult = AtomicReference<Boolean>()
        val committer = Thread {
            start.countDown()
            assertTrue(start.await(5, TimeUnit.SECONDS))
            commitResult.set(token.commit())
            done.countDown()
        }
        val roller = Thread {
            start.countDown()
            assertTrue(start.await(5, TimeUnit.SECONDS))
            rollbackResult.set(token.rollback())
            done.countDown()
        }
        committer.start()
        roller.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        committer.join(5_000)
        roller.join(5_000)
        assertFalse(committer.isAlive)
        assertFalse(roller.isAlive)

        assertTrue(commitResult.get()!! != rollbackResult.get()!!)
        val snap = a.snapshot()
        assertEquals(0, snap.reservedCount)
        if (commitResult.get()!!) {
            assertEquals(1, snap.persistedFrames)
            assertEquals(1, snap.manifest.size)
        } else {
            assertEquals(0, snap.persistedFrames)
            assertTrue(snap.manifest.isEmpty())
        }
    }

    @Test
    fun rollbackAfterRejectedDuplicateKeepsInvariants() {
        val a = accounting()
        assertNotNull(a.tryReserveAdoption(entry(0)))
        assertNull(a.tryReserveAdoption(entry(0, "dup.png")))
        // Invariant holds mid-flight: persistedFrames == manifest.size.
        a.snapshot()
        val second = a.tryReserveAdoption(entry(1))
        assertNotNull(second)
        assertTrue(second!!.rollback())
        val snap = a.snapshot()
        // Only the rolled-back reservation is released; the first is still reserved.
        assertEquals(1, snap.reservedCount)
        assertEquals(0, snap.persistedFrames)
        assertTrue(snap.manifest.isEmpty())
    }

    @Test
    fun multiCommitKeepsPersistedManifestSizeInvariant() {
        val a = accounting()
        val token0 = a.tryReserveAdoption(entry(0))!!
        val token1 = a.tryReserveAdoption(entry(1))!!
        assertTrue(token0.commit())
        assertTrue(token1.commit())
        val snap = a.snapshot()
        assertEquals(2, snap.persistedFrames)
        assertEquals(2, snap.manifest.size)
        assertEquals(0, snap.reservedCount)
        assertEquals(0, snap.manifest[0].frameIndex)
        assertEquals(1, snap.manifest[1].frameIndex)
    }
}
