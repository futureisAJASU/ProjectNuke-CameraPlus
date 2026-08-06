package com.projectnuke.keplernightlab

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2A-P2 candidate ownership: atomic UNSETTLED -> ADOPTING -> ADOPTED |
 * UNSETTLED -> DISCARDING -> DISCARDED | QUARANTINED state machine through the
 * injectable [YuvCandidateFilesystem] seam.  A DISCARDING candidate can never
 * become ADOPTED; aborts settle exactly once; filesystem throws are contained
 * with the throwable preserved; repeated settlement is idempotent.
 */
class YuvCandidateOwnershipTest {

    private class RecordingFilesystem(
        private val deleteResult: CandidateFileOperationResult,
        private val quarantineResult: CandidateFileOperationResult = CandidateFileOperationResult.QUARANTINED
    ) : YuvCandidateFilesystem {
        val deleteCalls = AtomicInteger(0)
        val quarantineCalls = AtomicInteger(0)

        override fun delete(candidate: File): CandidateFileOperationResult {
            deleteCalls.incrementAndGet()
            return deleteResult
        }

        override fun quarantine(candidate: File): CandidateFileOperationResult {
            quarantineCalls.incrementAndGet()
            return quarantineResult
        }
    }

    /**
     * Deterministic discard gating: delete() blocks until [release], making the
     * DISCARDING intermediate observable without any sleep or polling.
     */
    private class GatedFilesystem : YuvCandidateFilesystem {
        private val entered = CountDownLatch(1)
        private val releaseLatch = CountDownLatch(1)

        fun awaitEntered(timeoutSec: Long = 5) {
            assertTrue("delete never entered", entered.await(timeoutSec, TimeUnit.SECONDS))
        }

        fun release() { releaseLatch.countDown() }

        override fun delete(candidate: File): CandidateFileOperationResult {
            entered.countDown()
            releaseLatch.await(10, TimeUnit.SECONDS)
            return CandidateFileOperationResult.DELETED
        }

        override fun quarantine(candidate: File): CandidateFileOperationResult =
            CandidateFileOperationResult.QUARANTINED
    }

    @Test
    fun handleStartsUnsettled() {
        val handle = YuvCandidateHandle(0, File("candidate.tmp"))
        assertEquals(CandidateOwnership.UNSETTLED, handle.state())
    }

    @Test
    fun tryBeginAdoptionIsExactlyOnceAndExposesAdoptingIntermediate() {
        val handle = YuvCandidateHandle(0, File("candidate.tmp"))
        val claim = handle.tryBeginAdoption()
        assertNotNull(claim)
        assertEquals(CandidateOwnership.ADOPTING, handle.state())
        assertNull("second claim must lose", handle.tryBeginAdoption())
        assertEquals(CandidateOwnership.ADOPTING, handle.state())
    }

    @Test
    fun completeAdoptionSettlesAdoptedForClaimHolderOnly() {
        val handle = YuvCandidateHandle(0, File("candidate.tmp"))
        val otherHandle = YuvCandidateHandle(0, File("other.tmp"))
        val claim = handle.tryBeginAdoption()!!

        assertFalse("foreign claim must be rejected", handle.completeAdoption(CandidateAdoptionClaim(otherHandle)))
        assertEquals(CandidateOwnership.ADOPTING, handle.state())

        assertTrue(handle.completeAdoption(claim))
        assertEquals(CandidateOwnership.ADOPTED, handle.state())

        assertFalse("second completion must fail", handle.completeAdoption(claim))
        assertEquals(CandidateOwnership.ADOPTED, handle.state())
    }

    @Test
    fun discardDuringAdoptionClaimIsImpossible() {
        val handle = YuvCandidateHandle(0, File("candidate.tmp"))
        val claim = handle.tryBeginAdoption()!!

        val outcome = handle.discardOrQuarantine(RecordingFilesystem(CandidateFileOperationResult.DELETED))

        assertTrue(outcome.alreadySettled)
        assertEquals(CandidateOwnership.ADOPTING, outcome.finalState)
        assertEquals(CandidateOwnership.ADOPTING, handle.state())
        assertTrue(handle.completeAdoption(claim))
        assertEquals(CandidateOwnership.ADOPTED, handle.state())
    }

    @Test
    fun discardDuringAdoptionClaimCannotUseForeignClaim() {
        val handle = YuvCandidateHandle(0, File("candidate.tmp"))
        val otherHandle = YuvCandidateHandle(0, File("other.tmp"))
        val claim = handle.tryBeginAdoption()!!
        val filesystem = RecordingFilesystem(CandidateFileOperationResult.DELETED)

        val foreign = handle.abortAdoption(CandidateAdoptionClaim(otherHandle), filesystem)

        assertTrue(foreign.alreadySettled)
        assertEquals(CandidateOwnership.ADOPTING, handle.state())
        assertEquals(0, filesystem.deleteCalls.get())
        // The real holder can still settle the adoption.
        assertTrue(handle.completeAdoption(claim))
        assertEquals(CandidateOwnership.ADOPTED, handle.state())
    }

    @Test
    fun abortAdoptionSettlesExactlyOnceThroughDiscarding() {
        val handle = YuvCandidateHandle(0, File("candidate.tmp"))
        val claim = handle.tryBeginAdoption()!!
        val filesystem = RecordingFilesystem(CandidateFileOperationResult.DELETED)

        val first = handle.abortAdoption(claim, filesystem)

        assertEquals(CandidateOwnership.DISCARDED, first.finalState)
        assertEquals(CandidateOwnership.DISCARDED, handle.state())
        assertFalse(first.alreadySettled)
        assertEquals(1, filesystem.deleteCalls.get())

        val second = handle.abortAdoption(claim, filesystem)
        assertTrue(second.alreadySettled)
        assertEquals(CandidateOwnership.DISCARDED, second.finalState)
        assertEquals(1, filesystem.deleteCalls.get())
    }

    @Test
    fun abortAdoptionQuarantineFailureRecordsDebtWithThrowable() {
        val handle = YuvCandidateHandle(7, File("candidate.tmp"))
        val claim = handle.tryBeginAdoption()!!
        val boom = IllegalStateException("abort quarantine boom")
        val filesystem = RecordingFilesystem(
            deleteResult = CandidateFileOperationResult.DELETE_THREW(boom),
            quarantineResult = CandidateFileOperationResult.QUARANTINE_FAILED(boom)
        )

        val outcome = handle.abortAdoption(claim, filesystem)

        assertEquals(CandidateOwnership.QUARANTINED, outcome.finalState)
        assertTrue(outcome.cleanupFailed)
        val description = outcome.failureDescription(7, handle.file)
        assertNotNull(description)
        assertTrue(description!!.contains("candidate cleanup debt"))
        assertTrue(description.contains("frame=7"))
        assertTrue(description.contains("delete=DELETE_THREW"))
        assertTrue(description.contains("deleteThrowable=IllegalStateException: abort quarantine boom"))
        assertTrue(description.contains("quarantine=QUARANTINE_FAILED"))
        assertTrue(description.contains("quarantineThrowable=IllegalStateException: abort quarantine boom"))
        assertTrue(description.contains("state=QUARANTINED"))
    }

    @Test
    fun abortAdoptionDeletesRealFileAndSettlesDiscarded() {
        val dir = Files.createTempDirectory("yuv-handle").toFile()
        try {
            val candidate = File(dir, "candidate.tmp")
            Files.write(candidate.toPath(), byteArrayOf(1, 2, 3))
            val handle = YuvCandidateHandle(0, candidate)
            val claim = handle.tryBeginAdoption()!!

            val outcome = handle.abortAdoption(claim, RealYuvCandidateFilesystem)

            assertEquals(CandidateOwnership.DISCARDED, outcome.finalState)
            assertEquals(CandidateOwnership.DISCARDED, handle.state())
            assertEquals(CandidateFileOperationResult.DELETED, outcome.deleteResult)
            assertFalse(candidate.exists())
            assertFalse(outcome.cleanupFailed)
            assertNull(outcome.failureDescription(0, candidate))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun discardDeletesFileAndSettlesDiscarded() {
        val dir = Files.createTempDirectory("yuv-handle").toFile()
        try {
            val candidate = File(dir, "candidate.tmp")
            Files.write(candidate.toPath(), byteArrayOf(1, 2, 3))
            val handle = YuvCandidateHandle(0, candidate)

            val outcome = handle.discardOrQuarantine(RealYuvCandidateFilesystem)

            assertEquals(CandidateOwnership.DISCARDED, outcome.finalState)
            assertEquals(CandidateOwnership.DISCARDED, handle.state())
            assertEquals(CandidateFileOperationResult.DELETED, outcome.deleteResult)
            assertFalse(candidate.exists())
            assertFalse(outcome.cleanupFailed)
            assertNull(outcome.failureDescription(0, candidate))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun absentCandidateSettlesDiscardedWithoutError() {
        val dir = Files.createTempDirectory("yuv-handle-absent").toFile()
        try {
            val handle = YuvCandidateHandle(0, File(dir, "never-created.tmp"))
            val outcome = handle.discardOrQuarantine(RealYuvCandidateFilesystem)
            assertEquals(CandidateOwnership.DISCARDED, outcome.finalState)
            assertEquals(CandidateFileOperationResult.FILE_ABSENT, outcome.deleteResult)
            assertFalse(outcome.cleanupFailed)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deleteReturnedFalseQuarantinesAndSettlesQuarantined() {
        val handle = YuvCandidateHandle(0, File("candidate.tmp"))
        val filesystem = RecordingFilesystem(
            deleteResult = CandidateFileOperationResult.DELETE_RETURNED_FALSE,
            quarantineResult = CandidateFileOperationResult.QUARANTINED
        )

        val outcome = handle.discardOrQuarantine(filesystem)

        assertEquals(CandidateOwnership.QUARANTINED, outcome.finalState)
        assertEquals(CandidateFileOperationResult.DELETE_RETURNED_FALSE, outcome.deleteResult)
        assertEquals(CandidateFileOperationResult.QUARANTINED, outcome.quarantineResult)
        assertEquals(1, filesystem.deleteCalls.get())
        assertEquals(1, filesystem.quarantineCalls.get())
        assertFalse(outcome.cleanupFailed)
    }

    @Test
    fun deleteThrewQuarantinesAndSettlesQuarantined() {
        val handle = YuvCandidateHandle(0, File("candidate.tmp"))
        val boom = RuntimeException("delete boom")
        val filesystem = RecordingFilesystem(
            deleteResult = CandidateFileOperationResult.DELETE_THREW(boom),
            quarantineResult = CandidateFileOperationResult.QUARANTINED
        )

        val outcome = handle.discardOrQuarantine(filesystem)

        assertEquals(CandidateOwnership.QUARANTINED, outcome.finalState)
        assertEquals(CandidateFileOperationResult.DELETE_THREW(boom), outcome.deleteResult)
        assertEquals(1, filesystem.deleteCalls.get())
        assertFalse(outcome.cleanupFailed)
    }

    @Test
    fun quarantineThrewIsContainedWithThrowablePreserved() {
        val handle = YuvCandidateHandle(3, File("candidate.tmp"))
        val deleteBoom = RuntimeException("delete boom")
        val quarantineBoom = IllegalStateException("quarantine boom")
        val filesystem = RecordingFilesystem(
            deleteResult = CandidateFileOperationResult.DELETE_THREW(deleteBoom),
            quarantineResult = CandidateFileOperationResult.QUARANTINE_FAILED(quarantineBoom)
        )

        val outcome = handle.discardOrQuarantine(filesystem)

        assertEquals(CandidateOwnership.QUARANTINED, outcome.finalState)
        assertEquals(CandidateFileOperationResult.DELETE_THREW(deleteBoom), outcome.deleteResult)
        assertEquals(CandidateFileOperationResult.QUARANTINE_FAILED(quarantineBoom), outcome.quarantineResult)
        assertTrue(outcome.cleanupFailed)
        val description = outcome.failureDescription(3, handle.file)
        assertTrue(description!!.contains("quarantineThrowable=IllegalStateException: quarantine boom"))
    }

    @Test
    fun quarantineFailureRecordsCleanupDebt() {
        val handle = YuvCandidateHandle(7, File("candidate.tmp"))
        val filesystem = RecordingFilesystem(
            deleteResult = CandidateFileOperationResult.DELETE_RETURNED_FALSE,
            quarantineResult = CandidateFileOperationResult.QUARANTINE_FAILED()
        )

        val outcome = handle.discardOrQuarantine(filesystem)

        assertEquals(CandidateOwnership.QUARANTINED, outcome.finalState)
        assertTrue(outcome.cleanupFailed)
        val description = outcome.failureDescription(7, handle.file)
        assertNotNull(description)
        assertTrue(description!!.contains("candidate cleanup debt"))
        assertTrue(description.contains("frame=7"))
        assertTrue(description.contains("quarantine=QUARANTINE_FAILED"))
    }

    @Test
    fun discardDuringBlockedDiscardCannotAdopt() {
        val handle = YuvCandidateHandle(0, File("candidate.tmp"))
        val filesystem = GatedFilesystem()

        // The discard thread blocks inside delete(): DISCARDING is observable.
        val discardThread = Thread {
            handle.discardOrQuarantine(filesystem)
        }
        discardThread.start()
        filesystem.awaitEntered()
        assertEquals(CandidateOwnership.DISCARDING, handle.state())
        assertNull("adoption during DISCARDING must fail", handle.tryBeginAdoption())

        filesystem.release()
        discardThread.join(5_000)
        assertFalse("discard thread still alive", discardThread.isAlive)
        assertEquals(CandidateOwnership.DISCARDED, handle.state())
    }

    @Test
    fun repeatedSettlementIsIdempotentAndPerformsNoSecondFileOperation() {
        val handle = YuvCandidateHandle(0, File("candidate.tmp"))
        val filesystem = RecordingFilesystem(CandidateFileOperationResult.DELETED)

        val first = handle.discardOrQuarantine(filesystem)
        val second = handle.discardOrQuarantine(filesystem)

        assertEquals(CandidateOwnership.DISCARDED, first.finalState)
        assertEquals(CandidateOwnership.DISCARDED, second.finalState)
        assertTrue(second.alreadySettled)
        assertEquals(1, filesystem.deleteCalls.get())
        assertEquals(0, filesystem.quarantineCalls.get())
    }

    @Test
    fun concurrentSettlementPerformsExactlyOneFileOperation() {
        val handle = YuvCandidateHandle(0, File("candidate.tmp"))
        val filesystem = RecordingFilesystem(CandidateFileOperationResult.DELETED)
        val start = CountDownLatch(2)
        val done = CountDownLatch(2)

        val threads = listOf(
            Thread {
                start.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS))
                handle.discardOrQuarantine(filesystem)
                done.countDown()
            },
            Thread {
                start.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS))
                handle.discardOrQuarantine(filesystem)
                done.countDown()
            }
        )
        threads.forEach { it.start() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        threads.forEach {
            it.join(5_000)
            assertFalse("${it.name} still alive", it.isAlive)
        }

        assertEquals(1, filesystem.deleteCalls.get())
        assertEquals(0, filesystem.quarantineCalls.get())
        assertEquals(CandidateOwnership.DISCARDED, handle.state())
    }

    @Test
    fun settlementAfterAdoptionIsNoOp() {
        val handle = YuvCandidateHandle(0, File("candidate.tmp"))
        val filesystem = RecordingFilesystem(CandidateFileOperationResult.DELETED)
        val claim = handle.tryBeginAdoption()!!
        assertTrue(handle.completeAdoption(claim))

        val outcome = handle.discardOrQuarantine(filesystem)

        assertTrue(outcome.alreadySettled)
        assertEquals(CandidateOwnership.ADOPTED, outcome.finalState)
        assertEquals(0, filesystem.deleteCalls.get())
        assertEquals(0, filesystem.quarantineCalls.get())
    }
}
