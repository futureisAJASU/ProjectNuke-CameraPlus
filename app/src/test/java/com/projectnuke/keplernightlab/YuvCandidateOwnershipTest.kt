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
 * Phase 2A-P2 candidate ownership: exactly-once UNSETTLED -> ADOPTED | DISCARDED |
 * QUARANTINED transitions through the injectable [YuvCandidateFilesystem] seam,
 * with idempotent repeated settlement and observable cleanup debt.
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

    @Test
    fun handleStartsUnsettled() {
        val handle = YuvCandidateHandle(0, File("candidate.tmp"))
        assertEquals(CandidateOwnership.UNSETTLED, handle.state())
    }

    @Test
    fun adoptIsExactlyOnce() {
        val handle = YuvCandidateHandle(0, File("candidate.tmp"))
        assertTrue(handle.adopt())
        assertEquals(CandidateOwnership.ADOPTED, handle.state())
        assertFalse(handle.adopt())
        assertEquals(CandidateOwnership.ADOPTED, handle.state())
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
        val filesystem = RecordingFilesystem(
            deleteResult = CandidateFileOperationResult.DELETE_THREW,
            quarantineResult = CandidateFileOperationResult.QUARANTINED
        )

        val outcome = handle.discardOrQuarantine(filesystem)

        assertEquals(CandidateOwnership.QUARANTINED, outcome.finalState)
        assertEquals(CandidateFileOperationResult.DELETE_THREW, outcome.deleteResult)
        assertFalse(outcome.cleanupFailed)
    }

    @Test
    fun quarantineFailureRecordsCleanupDebt() {
        val handle = YuvCandidateHandle(7, File("candidate.tmp"))
        val filesystem = RecordingFilesystem(
            deleteResult = CandidateFileOperationResult.DELETE_RETURNED_FALSE,
            quarantineResult = CandidateFileOperationResult.QUARANTINE_FAILED
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
    fun settlementAfterAdoptIsNoOp() {
        val handle = YuvCandidateHandle(0, File("candidate.tmp"))
        val filesystem = RecordingFilesystem(CandidateFileOperationResult.DELETED)
        assertTrue(handle.adopt())

        val outcome = handle.discardOrQuarantine(filesystem)

        assertTrue(outcome.alreadySettled)
        assertEquals(CandidateOwnership.ADOPTED, outcome.finalState)
        assertEquals(0, filesystem.deleteCalls.get())
        assertEquals(0, filesystem.quarantineCalls.get())
    }
}
