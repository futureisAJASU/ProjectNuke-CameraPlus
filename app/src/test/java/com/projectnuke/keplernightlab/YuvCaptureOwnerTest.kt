package com.projectnuke.keplernightlab

import android.media.FakeImage
import android.media.Image
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Resource-aware ownership and end-to-end terminal-settlement tests for the
 * YUV capture pipeline using the production session seam
 * ([YuvCaptureSession.create]).
 *
 * The tests assert exactly-once release of the [YuvImageAccess] / [DirectYuvImageAccess]
 * seams, owner-side adoption of [YuvPngWorkProcessor] results, and the exact
 * terminal state transitions emitted by [YuvCaptureOwner].  All waits are
 * deterministic (CountDownLatch-based) — no [Thread.sleep].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21])
class YuvCaptureOwnerTest {

    // ------------------------------------------------------------------
    // Fakes for the Camera2 access seams
    // ------------------------------------------------------------------

    private class FakeBufferedAccess(
        private val ts: Long,
        private val bytes: Long = 12L
    ) : YuvImageAccess {
        val releaseCount = AtomicInteger(0)
        @Volatile private var released = false

        override fun timestampNs(): Long =
            if (released) error("timestampNs after release") else ts

        override fun allocationBytes(): Long =
            if (released) error("allocationBytes after release") else bytes

        override fun copy(frameIndex: Int): BufferedYuvFrame =
            if (released) error("copy after release")
            else BufferedYuvFrame(
                frameIndex, ts, 1, 1,
                byteArrayOf(0), byteArrayOf(0), byteArrayOf(0),
                1, 1, 1, 1, 1, 1
            )

        override fun release() {
            if (released) error("released twice")
            released = true
            releaseCount.incrementAndGet()
        }
    }

    private class FakeDirectAccess(
        private val failTimestamp: Boolean = false
    ) : DirectYuvImageAccess {
        val closeCount = AtomicInteger(0)
        val lastImage = AtomicReference<FakeImage?>()
        private var taken = false
        private var closed = false

        override fun timestampNs(): Long =
            if (failTimestamp) error("timestamp failed") else 4321L

        override fun allocationBytes(): Long = 0L
        override fun copy(frameIndex: Int): BufferedYuvFrame = error("direct work does not copy")

        override fun release() {
            // Mirrors Camera2DirectYuvImageAccess: release after takeImage is a no-op.
            if (taken) return
            if (closed) error("double close")
            closed = true
            closeCount.incrementAndGet()
        }

        override fun takeImage(): Image? {
            if (taken) error("takeImage called twice")
            taken = true
            return FakeImage().also { lastImage.set(it) }
        }
    }

    private class ThrowingBufferedAccess(
        private val ts: Long = 1000L,
        private val bytes: Long = 12L
    ) : YuvImageAccess {
        val releaseCount = AtomicInteger(0)
        override fun timestampNs(): Long = ts
        override fun allocationBytes(): Long = bytes
        override fun copy(frameIndex: Int): BufferedYuvFrame = error("copy throws")
        override fun release() { releaseCount.incrementAndGet() }
    }

    /**
     * Direct access whose release() ALSO throws: the primary failure (timestamp)
     * and the release failure must both be observable in the owner ledger.
     */
    private class ThrowingReleaseDirectAccess : DirectYuvImageAccess {
        override fun timestampNs(): Long = error("timestamp failed")
        override fun allocationBytes(): Long = 0L
        override fun copy(frameIndex: Int): BufferedYuvFrame = error("direct work does not copy")
        override fun takeImage(): Image? = null
        override fun release(): Unit = error("release threw")
    }

    // ------------------------------------------------------------------
    // Test harness: deterministic session via the production seam
    // ------------------------------------------------------------------

    private class Harness(
        val frameCount: Int = 3,
        workerCapacity: Int = 4,
        val rotationDegrees: Int = 0,
        encodeFailure: Boolean = false,
        encodeLatch: EncodeLatch? = null,
        rejectDispatch: Boolean = false
    ) {
        val dir: File = Files.createTempDirectory("yuv-owner-test").toFile()
        val handlerThread = android.os.HandlerThread("yuv-test").apply { start() }
        val handler = android.os.Handler(handlerThread.looper)

        val terminalLatch = CountDownLatch(1)
        val persistedFrames = AtomicInteger(0)
        val capturedFile = AtomicReference<File?>(null)
        val errorMessage = AtomicReference<String?>(null)
        val onCaptureCompleteCount = AtomicInteger(0)
        val onCaptureErrorCount = AtomicInteger(0)
        val postedStatus = mutableListOf<String>()

        val session: YuvCaptureSession = YuvCaptureSession.create(
            dispatch = { event ->
                if (rejectDispatch) {
                    event.disposeWithoutMutation()
                    false
                } else {
                    handler.post { event.execute() }
                    true
                }
            },
            outputDir = dir,
            frameCount = frameCount,
            rotationDegrees = rotationDegrees,
            workerCapacity = workerCapacity,
            maxRetainedBytes = 16L * 1024 * 1024,
            workProcessor = YuvPngWorkProcessor(
                encoder = object : YuvPngEncoder {
                    override fun encodeDirect(image: Image, candidate: File, rotationDegrees: Int) {
                        encodeLatch?.signalStartAndBlock()
                        if (encodeFailure) throw IllegalStateException("forced encode failure")
                        Files.write(candidate.toPath(), PNG_1X1)
                    }
                    override fun encodeBuffered(frame: BufferedYuvFrame, candidate: File, rotationDegrees: Int) {
                        encodeLatch?.signalStartAndBlock()
                        if (encodeFailure) throw IllegalStateException("forced encode failure")
                        Files.write(candidate.toPath(), PNG_1X1)
                    }
                },
                committer = YuvCandidateCommitter { candidate, final ->
                    Files.move(candidate.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
            ),
            postStatus = { msg ->
                handler.post { postedStatus += msg }
            },
            postMainOrRun = { runnable -> if (!handler.post(runnable)) runnable.run() },
            writeJobJson = { status, saved, manifest ->
                handler.post {
                    persistedFrames.set(saved)
                    if (status in TERMINAL_JOB_STATUSES) terminalLatch.countDown()
                }
            },
            saveMotionOnce = { _ -> null to null },
            onCaptureComplete = { file ->
                onCaptureCompleteCount.incrementAndGet()
                capturedFile.set(file)
            },
            onCaptureError = { msg, _ ->
                onCaptureErrorCount.incrementAndGet()
                errorMessage.set(msg)
            }
        )

        /** Wait deterministically for the terminal status to be reached and return it. */
        fun awaitTerminal(timeoutSec: Long = 10): CaptureTerminalStatus {
            assertTrue("terminal not reached", terminalLatch.await(timeoutSec, TimeUnit.SECONDS))
            flushHandler()
            return session.terminalState.status()
        }

        /** Run posted tasks on the test handler thread to make latches advance. */
        fun flushHandler() {
            val latch = CountDownLatch(1)
            assertTrue("handler flush did not complete", handler.post { latch.countDown() })
            assertTrue(latch.await(2, TimeUnit.SECONDS))
        }

        fun shutdown() {
            session.close()
            handlerThread.quitSafely()
        }

        companion object {
            private val TERMINAL_JOB_STATUSES = setOf(
                "CAPTURE_COMPLETE", "CAPTURE_PARTIAL", "CAPTURE_FAILED", "CAPTURE_TIMEOUT", "CAPTURE_CANCELLED"
            )
        }
    }

    /** Blocks an encoder task until [release] is called. */
    private class EncodeLatch {
        private val startLatch = CountDownLatch(1)
        private val releaseLatch = CountDownLatch(1)
        @Volatile private var started = false

        fun signalStartAndBlock() {
            if (!started) { started = true; startLatch.countDown() }
            releaseLatch.await(10, TimeUnit.SECONDS)
        }

        fun awaitStart(timeoutSec: Long = 5) {
            assertTrue("encode never started", startLatch.await(timeoutSec, TimeUnit.SECONDS))
        }

        fun release() { releaseLatch.countDown() }
    }

    // ------------------------------------------------------------------
    // Buffered-path ownership and terminal settlement
    // ------------------------------------------------------------------

    @Test
    fun bufferedAccessReleasedExactlyOnceOnAcceptedPathAndReachesSuccess() {
        val harness = Harness(frameCount = 3)
        try {
            val a1 = FakeBufferedAccess(1000L); val a2 = FakeBufferedAccess(2000L); val a3 = FakeBufferedAccess(3000L)
            harness.session.owner.acceptBuffered(a1)
            harness.session.owner.acceptBuffered(a2)
            harness.session.owner.acceptBuffered(a3)
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.SUCCESS, status)
            assertEquals(1, a1.releaseCount.get())
            assertEquals(1, a2.releaseCount.get())
            assertEquals(1, a3.releaseCount.get())
            assertEquals(3, harness.session.accounting.snapshot().persistedFrames)
            assertEquals(0, harness.session.reservations.currentBytes())
            assertEquals(0, harness.session.lifecycle.retainedCount())
            assertEquals(1, harness.onCaptureCompleteCount.get())
            assertEquals(0, harness.onCaptureErrorCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun bufferedAccessRejectedByReservationStillReleasesAccessExactlyOnce() {
        // capacity small enough to reject on cumulative bytes; better: a reservation
        // with a tiny budget which forces createBufferedYuvWork to return Rejected.
        val harness = Harness(frameCount = 1, workerCapacity = 1)
        try {
            val bigAccess = FakeBufferedAccess(ts = 1L, bytes = 999_999_999L)
            harness.session.owner.acceptBuffered(bigAccess)
            harness.flushHandler()
            assertEquals(1, bigAccess.releaseCount.get())
            val snap = harness.session.accounting.snapshot()
            // The frame was received by the owner BEFORE the reservation rejection
            // dropped it: both counters are truthful.
            assertEquals(1, snap.receivedFrames)
            assertEquals(1, snap.droppedFrames)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun throwingBufferedCopyReleasesAccessExactlyOnceAndFails() {
        val harness = Harness(frameCount = 1)
        try {
            val access = ThrowingBufferedAccess()
            harness.session.owner.acceptBuffered(access)
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.FAILED, status)
            assertEquals(1, access.releaseCount.get())
            assertEquals(1, harness.onCaptureErrorCount.get())
            assertEquals(0, harness.onCaptureCompleteCount.get())
            assertEquals(1, harness.session.accounting.snapshot().failedFrames)
        } finally {
            harness.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // Direct-path ownership
    // ------------------------------------------------------------------

    @Test
    fun directAccessReachesSuccessAndReleasesImageExactlyOnce() {
        val harness = Harness(frameCount = 1)
        try {
            val access = FakeDirectAccess()
            harness.session.owner.acceptDirect(access)
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.SUCCESS, status)
            // takeImage consumed the access wrapper; the image is closed by the
            // work item's dispose exactly once.
            assertEquals(0, access.closeCount.get())
            assertEquals(1, access.lastImage.get()!!.closeCount.get())
            assertEquals(1, harness.session.accounting.snapshot().persistedFrames)
            assertEquals(1, harness.onCaptureCompleteCount.get())
            assertEquals(0, harness.onCaptureErrorCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun directWorkerFailureThenDeadlineReachesTimedOutTerminal() {
        val harness = Harness(frameCount = 1, encodeFailure = true)
        try {
            val access = FakeDirectAccess()
            harness.session.owner.acceptDirect(access)
            harness.session.boundedWorker.close()
            assertTrue(harness.session.boundedWorker.awaitTermination(2_000L))
            harness.flushHandler()
            harness.session.owner.onDeadlineReached()
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.TIMED_OUT, status)
            assertEquals(0, access.closeCount.get())
            // The image is released by the work item's dispose in the task finally.
            assertEquals(1, access.lastImage.get()!!.closeCount.get())
            assertTrue(harness.onCaptureErrorCount.get() >= 1)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun directTimestampFailureReleasesAccessExactlyOnceAndFails() {
        val harness = Harness(frameCount = 1)
        try {
            val access = FakeDirectAccess(failTimestamp = true)
            harness.session.owner.acceptDirect(access)
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.FAILED, status)
            assertEquals(1, access.closeCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun directCreationReleaseFailureReachesOwnerLedger() {
        // createDirectYuvWork fails on timestamp access AND the emergency release
        // itself throws: the release failure must reach the observable ledger
        // instead of being silently discarded.
        val harness = Harness(frameCount = 1)
        try {
            harness.session.owner.acceptDirect(ThrowingReleaseDirectAccess())
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.FAILED, status)
            val debts = harness.session.owner.candidateCleanupDebt()
            assertEquals(1, debts.size)
            assertTrue(debts[0].contains("direct creation releaseFailure frame=0"))
            assertTrue(debts[0].contains("cause=IllegalStateException: timestamp failed"))
            assertTrue(debts[0].contains("releaseFailure=IllegalStateException: release threw"))
            assertEquals(1, harness.onCaptureErrorCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun blockedDirectEncoderRetainsSourceUntilReleased() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(frameCount = 1, workerCapacity = 1, encodeLatch = encodeLatch)
        try {
            val access = FakeDirectAccess()
            harness.session.owner.acceptDirect(access)
            encodeLatch.awaitStart()
            // Mid-encode: the Image must still be owned (not yet released).
            assertEquals(0, access.lastImage.get()!!.closeCount.get())
            encodeLatch.release()
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.SUCCESS, status)
            assertEquals(1, access.lastImage.get()!!.closeCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun directEncodeReleasedExactlyOnceOnSessionCleanup() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(frameCount = 1, workerCapacity = 1, encodeLatch = encodeLatch)
        try {
            val access = FakeDirectAccess()
            harness.session.owner.acceptDirect(access)
            encodeLatch.awaitStart()
            harness.session.close()
            // The running worker task is not disposed by shutdown; the source stays
            // owned until the encoder returns.
            assertEquals(0, access.lastImage.get()!!.closeCount.get())
            encodeLatch.release()
            assertTrue(harness.session.boundedWorker.awaitTermination(5_000L))
            harness.flushHandler()
            assertEquals(1, access.lastImage.get()!!.closeCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun directQueueRejectionReleasesImageExactlyOnce() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val harness = Harness(frameCount = 1, workerCapacity = 1)
        try {
            // Occupy the single worker slot AND fill the capacity-1 queue so the
            // direct task is rejected at submit (active + queued both busy).
            assertTrue(harness.session.boundedWorker.submit(Runnable {
                started.countDown(); release.await(5, TimeUnit.SECONDS)
            }))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(harness.session.boundedWorker.submit(Runnable {
                release.await(5, TimeUnit.SECONDS)
            }))
            val access = FakeDirectAccess()
            harness.session.owner.acceptDirect(access)
            harness.flushHandler()
            // Rejected task was disposed by the worker: the image is closed exactly once.
            assertEquals(1, access.lastImage.get()!!.closeCount.get())
            val snap = harness.session.accounting.snapshot()
            assertEquals(1, snap.receivedFrames)
            assertEquals(1, snap.droppedFrames)
        } finally {
            release.countDown()
            harness.shutdown()
        }
    }

    @Test
    fun frameWithoutRemainingIdentityCountsReceivedAndDroppedExactlyOnce() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(frameCount = 1, encodeLatch = encodeLatch)
        try {
            harness.session.owner.acceptDirect(FakeDirectAccess())
            encodeLatch.awaitStart()
            // Identity exhausted (frameCount=1) while the first frame is still
            // encoding: the second acquire is counted as received AND dropped, and
            // released exactly once.
            val second = FakeDirectAccess()
            harness.session.owner.acceptDirect(second)
            harness.flushHandler()
            assertEquals(1, second.closeCount.get())
            val snap = harness.session.accounting.snapshot()
            assertEquals(2, snap.receivedFrames)
            assertEquals(1, snap.droppedFrames)
        } finally {
            encodeLatch.release()
            harness.shutdown()
        }
    }

    @Test
    fun directOwnerEventRejectionReleasesAccessExactlyOnce() {
        val harness = Harness(frameCount = 1, rejectDispatch = true)
        try {
            val access = FakeDirectAccess()
            harness.session.owner.acceptDirect(access)
            // The envelope was disposed without mutation: the access (image not yet
            // taken) is released exactly once; no work item is created.
            assertEquals(1, access.closeCount.get())
            assertNull(access.lastImage.get())
        } finally {
            harness.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // Terminal settlement — exact assertions
    // ------------------------------------------------------------------

    @Test
    fun successCallbackFiresAtMostOnceEvenIfDeadlineFiresLater() {
        val harness = Harness(frameCount = 2)
        try {
            harness.session.owner.acceptBuffered(FakeBufferedAccess(1000L))
            harness.session.owner.acceptBuffered(FakeBufferedAccess(2000L))
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
            harness.session.owner.onDeadlineReached()
            harness.flushHandler()
            assertEquals(1, harness.onCaptureCompleteCount.get())
            assertEquals(0, harness.onCaptureErrorCount.get())
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.session.terminalState.status())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun deadlineReachedBeforeAnyPersistedFrameReachesTimedOut() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(frameCount = 2, workerCapacity = 1, encodeLatch = encodeLatch)
        try {
            harness.session.owner.acceptBuffered(FakeBufferedAccess(1000L))
            encodeLatch.awaitStart()
            harness.session.owner.onDeadlineReached()
            // The blocked encode is released after deadline; let the post-deadline
            // completion flow through.
            encodeLatch.release()
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.TIMED_OUT, status)
            assertEquals(0, harness.session.accounting.snapshot().persistedFrames)
            assertTrue(harness.onCaptureErrorCount.get() >= 1)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun cancellationReachesCancelledTerminalAndFiresCallbackOnce() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(frameCount = 2, workerCapacity = 2, encodeLatch = encodeLatch)
        try {
            harness.session.owner.acceptBuffered(FakeBufferedAccess(1000L))
            encodeLatch.awaitStart()
            harness.session.owner.onCancellationRequested()
            encodeLatch.release()
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.CANCELLED, status)
            assertEquals(0, harness.onCaptureCompleteCount.get())
            assertEquals(1, harness.onCaptureErrorCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun lateBufferedCompletionAfterTimeoutIsDiscarded() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(frameCount = 1, workerCapacity = 1, encodeLatch = encodeLatch)
        try {
            harness.session.owner.acceptBuffered(FakeBufferedAccess(1234L))
            encodeLatch.awaitStart()
            harness.session.owner.onDeadlineReached()
            // Wait for the TIMEOUT terminal status to be claimed before releasing.
            // The blocked encode now completes; the owner must discard it.
            encodeLatch.release()
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.TIMED_OUT, status)
            assertEquals(0, harness.session.accounting.snapshot().persistedFrames)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun duplicatePersistedIdentityCannotSatisfySuccess() {
        val harness = Harness(frameCount = 1)
        try {
            harness.session.owner.acceptBuffered(FakeBufferedAccess(1000L))
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
            // After success, the manifest is fixed: adding a duplicate entry is rejected.
            assertFalse(
                harness.session.accounting.persistedFrame(
                    YuvFrameManifestEntry(0, "frame_00_color.png", 1000L, true)
                )
            )
            assertEquals(1, harness.session.accounting.snapshot().persistedFrames)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun finalFilesAreDistinctReadablePngsAfterSuccess() {
        val harness = Harness(frameCount = 3)
        try {
            harness.session.owner.acceptBuffered(FakeBufferedAccess(1000L))
            harness.session.owner.acceptBuffered(FakeBufferedAccess(2000L))
            harness.session.owner.acceptBuffered(FakeBufferedAccess(3000L))
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
            val files = harness.dir.listFiles { f -> f.isFile && f.name.endsWith(".png") }
            assertNotNull(files)
            assertEquals(3, files!!.size)
            val snap = harness.session.accounting.snapshot()
            assertEquals(
                files.map { it.name }.sorted(),
                snap.manifest.map { it.filename }.sorted()
            )
            files.forEach { file ->
                val bytes = Files.readAllBytes(file.toPath())
                assertTrue("file ${file.name} is empty", bytes.isNotEmpty())
            }
        } finally {
            harness.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle primitives (deterministic, owner-independent)
    // ------------------------------------------------------------------

    @Test
    fun settleEncodingIsIdempotentAndReleasesReservationOnce() {
        val reservations = YuvBufferReservations(1024L)
        val accounting = YuvCaptureAccounting()
        val lifecycle = YuvBufferedLifecycle()
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))
        assertEquals(1, accounting.snapshot().bufferedFrames)
        lifecycle.settleEncoding(item, accounting)
        lifecycle.settleEncoding(item, accounting)
        assertEquals(0, accounting.snapshot().bufferedFrames)
        assertEquals(0L, reservations.currentBytes())
    }

    @Test
    fun closeAndDrainRetainedExcludesEncodingItems() {
        val reservations = YuvBufferReservations(1024L)
        val accounting = YuvCaptureAccounting()
        val lifecycle = YuvBufferedLifecycle()
        assertTrue(reservations.tryReserve(200L))
        val item1 = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        val item2 = YuvPngWorkItem.bufferedForTest(1, 2L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item1))
        assertTrue(lifecycle.tryRegister(item2))
        assertTrue(lifecycle.beginEncoding(item1))
        val drained = lifecycle.closeAndDrainRetained()
        assertEquals(listOf(item2), drained)
        assertEquals(1, lifecycle.encodingCount())
        lifecycle.settleEncoding(item1, accounting)
        item2.dispose(accounting)
        assertEquals(0, accounting.snapshot().bufferedFrames)
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, lifecycle.trackedCount())
    }

    // ------------------------------------------------------------------
    // createDirectYuvWork primitive
    // ------------------------------------------------------------------

    @Test
    fun createDirectYuvWorkWithFailingTimestampReleasesOnceAndFails() {
        val accounting = YuvCaptureAccounting()
        val directAccess = FakeDirectAccess(failTimestamp = true)
        val result = createDirectYuvWork(0, directAccess, accounting)
        assertTrue(result is DirectYuvWorkCreation.Failed)
        assertEquals(1, accounting.snapshot().failedFrames)
        assertEquals(1, directAccess.closeCount.get())
    }

    companion object {
        private val PNG_1X1: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
    }
}
