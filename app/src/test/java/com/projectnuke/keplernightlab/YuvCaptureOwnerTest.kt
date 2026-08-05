package com.projectnuke.keplernightlab

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
        @Volatile private var closed = false

        override fun timestampNs(): Long =
            if (failTimestamp) error("timestamp failed") else 4321L

        override fun allocationBytes(): Long = 0L
        override fun copy(frameIndex: Int): BufferedYuvFrame = error("direct work does not copy")

        override fun release() {
            if (closed) error("double close")
            closed = true
            closeCount.incrementAndGet()
        }

        override fun takeImage(): Image? {
            // No real Image in JVM tests; the encoder handles null gracefully.
            return null
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

    // ------------------------------------------------------------------
    // Test harness: deterministic session via the production seam
    // ------------------------------------------------------------------

    private class Harness(
        val frameCount: Int = 3,
        workerCapacity: Int = 4,
        val rotationDegrees: Int = 0,
        encodeFailure: Boolean = false,
        encodeLatch: EncodeLatch? = null
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
            dispatch = { event -> handler.post { event.execute() }; true },
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

        /** Wait for `target` persisted frames deterministically via handler flushes. */
        fun awaitPersisted(target: Int, timeoutSec: Long = 10) {
            val deadline = System.currentTimeMillis() + timeoutSec * 1000
            while (System.currentTimeMillis() < deadline && persistedFrames.get() < target) {
                flushHandler()
            }
            assertEquals(target, persistedFrames.get())
        }

        /** Run posted tasks on the test handler thread to make latches advance. */
        fun flushHandler() {
            val latch = CountDownLatch(1)
            handler.post { latch.countDown() }
            latch.await(2, TimeUnit.SECONDS)
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
            assertEquals(1, harness.session.accounting.snapshot().droppedFrames)
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
    fun directAccessReleasedExactlyOnceAndWorkerFailureCountsAsFailedFrame() {
        val harness = Harness(frameCount = 1, encodeFailure = false)
        try {
            val access = FakeDirectAccess()
            // FakeDirectAccess.takeImage returns null; the work processor then fails
            // (no owned source) and the owner records a failed frame.  The owner
            // does not auto-fail capture on a single worker failure; the terminal
            // state remains ACTIVE until the caller triggers the deadline.
            harness.session.owner.acceptDirect(access)
            harness.flushHandler()
            // Give the worker thread a brief deterministic wait to settle the failure.
            harness.session.boundedWorker.awaitTermination(2_000L)
            harness.flushHandler()
            assertEquals(1, access.closeCount.get())
            assertTrue(harness.session.accounting.snapshot().failedFrames >= 1)
            // No terminal claim yet: capture did not finish or fail at the framework level.
            assertEquals(CaptureTerminalStatus.ACTIVE, harness.session.terminalState.status())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun directWorkerFailureThenDeadlineReachesTimedOutTerminal() {
        val harness = Harness(frameCount = 1)
        try {
            val access = FakeDirectAccess()
            harness.session.owner.acceptDirect(access)
            harness.session.boundedWorker.awaitTermination(2_000L)
            harness.flushHandler()
            harness.session.owner.onDeadlineReached()
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.TIMED_OUT, status)
            assertEquals(1, access.closeCount.get())
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
        assertEquals(1, lifecycle.retainedCount())
        lifecycle.settleEncoding(item1, accounting)
        item2.dispose(accounting)
        assertEquals(0, accounting.snapshot().bufferedFrames)
        assertEquals(0L, reservations.currentBytes())
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
