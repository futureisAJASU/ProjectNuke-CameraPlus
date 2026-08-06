package com.projectnuke.keplernightlab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    // ── Truthful FAILED states (spec: COMMITTED only after mutation, ROLLED_BACK
    // only after both releases, FAILED = neither success claimed + failure visible)

    @Test
    fun commitFailurePublishesFailedWithObservableFailure() {
        val boom = IllegalStateException("commit boom")
        val a = object : YuvCaptureAccounting() {
            override fun commitAdoption(token: AdoptionToken): Boolean = throw boom
        }
        val token = a.tryReserveAdoption(entry(0))!!
        assertFalse(token.commit())
        assertEquals(AdoptionTokenState.FAILED, token.state())
        assertSame(boom, token.failure)
        val snap = a.snapshot()
        assertEquals(0, snap.persistedFrames)
        assertTrue(snap.manifest.isEmpty())
        // The reservations were never mutated by the failed commit: both still held,
        // symmetric, and recoverable via releaseReservations.
        assertEquals(1, snap.reservedIndexCount)
        assertEquals(1, snap.reservedFilenameCount)
        assertFalse(token.rollback())
        assertEquals(AdoptionTokenState.FAILED, token.state())
        a.releaseReservations(entry(0))
        assertEquals(0, a.snapshot().reservedIndexCount)
        assertEquals(0, a.snapshot().reservedFilenameCount)
    }

    @Test
    fun rollbackFailurePublishesFailedWithObservableFailure() {
        val boom = RuntimeException("rollback boom")
        val a = object : YuvCaptureAccounting() {
            override fun rollbackAdoption(token: AdoptionToken): Boolean = throw boom
        }
        val token = a.tryReserveAdoption(entry(0))!!
        assertFalse(token.rollback())
        assertEquals(AdoptionTokenState.FAILED, token.state())
        assertSame(boom, token.failure)
        assertFalse(token.commit())
        assertEquals(AdoptionTokenState.FAILED, token.state())
        assertEquals(1, a.snapshot().reservedIndexCount)
        assertEquals(1, a.snapshot().reservedFilenameCount)
    }

    @Test
    fun committingIntermediateIsObservableDuringBlockedCommit() {
        val entered = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val a = object : YuvCaptureAccounting() {
            override fun commitAdoption(token: AdoptionToken): Boolean {
                entered.countDown()
                assertTrue(releaseLatch.await(10, TimeUnit.SECONDS))
                return super.commitAdoption(token)
            }
        }
        val token = a.tryReserveAdoption(entry(0))!!
        val committer = Thread { token.commit() }
        committer.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertEquals(AdoptionTokenState.COMMITTING, token.state())
        releaseLatch.countDown()
        committer.join(5_000)
        assertFalse(committer.isAlive)
        assertEquals(AdoptionTokenState.COMMITTED, token.state())
        assertEquals(1, a.snapshot().persistedFrames)
    }

    @Test
    fun rollingBackIntermediateIsObservableDuringBlockedRollback() {
        val entered = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val a = object : YuvCaptureAccounting() {
            override fun rollbackAdoption(token: AdoptionToken): Boolean {
                entered.countDown()
                assertTrue(releaseLatch.await(10, TimeUnit.SECONDS))
                return super.rollbackAdoption(token)
            }
        }
        val token = a.tryReserveAdoption(entry(0))!!
        val roller = Thread { token.rollback() }
        roller.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertEquals(AdoptionTokenState.ROLLING_BACK, token.state())
        releaseLatch.countDown()
        roller.join(5_000)
        assertFalse(roller.isAlive)
        assertEquals(AdoptionTokenState.ROLLED_BACK, token.state())
        assertEquals(0, a.snapshot().reservedIndexCount)
        assertEquals(0, a.snapshot().reservedFilenameCount)
    }

    // ── Symmetric reservation counts + invariant-failure injection (spec: both
    // reservations verified before either is mutated; no one-sided removal)

    @Test
    fun reservationCountsStaySymmetricAcrossEveryPath() {
        val a = accounting()
        val t0 = a.tryReserveAdoption(entry(0))!!
        val t1 = a.tryReserveAdoption(entry(1))!!
        val t2 = a.tryReserveAdoption(entry(2))!!
        var snap = a.snapshot()
        assertEquals(snap.reservedIndexCount, snap.reservedFilenameCount)
        assertTrue(t1.commit())
        snap = a.snapshot()
        assertEquals(snap.reservedIndexCount, snap.reservedFilenameCount)
        assertTrue(t2.rollback())
        snap = a.snapshot()
        assertEquals(1, snap.reservedIndexCount)
        assertEquals(1, snap.reservedFilenameCount)
        assertTrue(t0.commit())
        snap = a.snapshot()
        assertEquals(0, snap.reservedIndexCount)
        assertEquals(0, snap.reservedFilenameCount)
        assertEquals(2, snap.persistedFrames)
    }

    @Test
    fun commitRejectedWhenEitherReservationMissingLeavesSetsUntouched() {
        // Injection: a subclass that removes ONLY the filename side.  The base
        // commitAdoption verifies BOTH reservations and must refuse to mutate
        // anything further (no `remove(index) || remove(filename)` behavior).
        val corrupted = object : YuvCaptureAccounting() {
            fun removeOnlyFilename(entry: YuvFrameManifestEntry) = synchronized(lock) {
                reservedFilenames.remove(entry.filename)
            }
        }
        val token = corrupted.tryReserveAdoption(entry(0))!!
        corrupted.removeOnlyFilename(entry(0))
        assertFalse("commit must refuse when either reservation is missing", token.commit())
        assertEquals(AdoptionTokenState.FAILED, token.state())
        val snap = corrupted.snapshot()
        assertEquals(0, snap.persistedFrames)
        assertTrue(snap.manifest.isEmpty())
        assertEquals(1, snap.reservedIndexCount)
        assertEquals(0, snap.reservedFilenameCount)
    }

    @Test
    fun oneSidedRemovalCorruptionIsObservableInSnapshot() {
        val corrupted = object : YuvCaptureAccounting() {
            fun removeOnlyIndex(entry: YuvFrameManifestEntry) = synchronized(lock) {
                reservedIndices.remove(entry.frameIndex)
            }
        }
        val token = corrupted.tryReserveAdoption(entry(0))!!
        corrupted.removeOnlyIndex(entry(0))
        val snap = corrupted.snapshot()
        // The asymmetry is observable: the invariant check can detect corruption.
        assertEquals(0, snap.reservedIndexCount)
        assertEquals(1, snap.reservedFilenameCount)
    }
}
