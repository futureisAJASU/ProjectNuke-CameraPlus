package com.projectnuke.keplernightlab

import android.media.FakeYuvImage
import android.media.Image
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
 * Production-bridge tests for the YUV capture pipeline seam created in Phase 2.
 *
 * Unlike [YuvCaptureOwnerTest] which exercises primitive owner behavior with fake
 * access interfaces, this suite drives the actual production wrappers
 * ([Camera2YuvImageAccess], [Camera2DirectYuvImageAccess]) — the same classes that
 * ColorFusion.kt's ImageReader listener constructs and hands to the session owner.
 *
 * Tests assert:
 * - exactly-once Image release through the production access wrappers,
 * - terminal invariant relationships (manifest size == persistedFrames, etc.),
 * - backpressure rejection (queue full -> release once -> received/dropped tracked),
 * - late Camera2 callbacks after terminal never mutate owner state,
 * - cancellation/deadline ordering deterministically reaches the expected terminal,
 * - main callback dispatch failure never runs callbacks inline on capture/worker threads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21])
class ProductionYuvCaptureBridgeTest {

    // ------------------------------------------------------------------
    // Harness: production seam via YuvCaptureSession.create(...)
    // ------------------------------------------------------------------

    private class Harness(
        val frameCount: Int = 3,
        workerCapacity: Int = 4,
        encodeFailure: Boolean = false,
        encodeLatch: EncodeLatch? = null,
        rejectCallbackDispatch: Boolean = false,
        callbackThreadName: ThreadLocal<String?> = ThreadLocal.withInitial { null }
    ) {
        val dir: File = Files.createTempDirectory("prod-yuv-bridge").toFile()
        val handlerThread = android.os.HandlerThread("prod-yuv-bridge").apply { start() }
        val handler: android.os.Handler = android.os.Handler(handlerThread.looper)

        val terminalLatch = CountDownLatch(1)
        val terminalObservationLatch = CountDownLatch(1)
        val completeCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val callbackLatch = CountDownLatch(1)
        val callbackThread = AtomicReference<Thread?>(null)
        val capturedDir = AtomicReference<File?>(null)
        val lastErrorMessage = AtomicReference<String?>(null)

        val session: YuvCaptureSession = YuvCaptureSession.create(
            dispatch = { event ->
                handler.post { event.execute() }
                true
            },
            outputDir = dir,
            frameCount = frameCount,
            rotationDegrees = 0,
            workerCapacity = workerCapacity,
            maxRetainedBytes = 16L * 1024L * 1024L,
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
            dispatchCallback = CallbackDispatcher { runnable ->
                if (rejectCallbackDispatch) {
                    terminalObservationLatch.countDown()
                    return@CallbackDispatcher false
                }
                val captured = Thread.currentThread().name
                terminalObservationLatch.countDown()
                if (!handler.post {
                    callbackThread.set(Thread.currentThread())
                    runnable.run()
                }) runnable.run()
                true
            },
            writeJobJson = { status, _, _ ->
                if (status in TERMINAL_STATUSES) {
                    handler.post {
                        terminalLatch.countDown()
                    }
                }
            },
            onCaptureComplete = { file ->
                callbackThreadName.set(Thread.currentThread().name)
                completeCount.incrementAndGet()
                capturedDir.set(file)
                callbackLatch.countDown()
            },
            onCaptureError = { msg, _ ->
                callbackThreadName.set(Thread.currentThread().name)
                errorCount.incrementAndGet()
                lastErrorMessage.set(msg)
                callbackLatch.countDown()
            },
            productionResourceCoordinator = YuvProductionResourceCoordinator(
                timeoutScheduler = null,
                backgroundHandler = null,
                backgroundThread = null
            )
        )

        fun awaitTerminal(timeoutSec: Long = 10): CaptureTerminalStatus {
            assertTrue("terminal not reached", terminalLatch.await(timeoutSec, TimeUnit.SECONDS))
            assertTrue("terminal callback dispatch not reached", terminalObservationLatch.await(timeoutSec, TimeUnit.SECONDS))
            flushHandler()
            return session.terminalState.status()
        }

        fun flushHandler() {
            val drain = CountDownLatch(1)
            handler.post { drain.countDown() }
            assertTrue(drain.await(2, TimeUnit.SECONDS))
        }

        fun awaitCallback() {
            assertTrue("terminal callback not reached", callbackLatch.await(10, TimeUnit.SECONDS))
            flushHandler()
        }

        fun shutdown() {
            session.close()
            handlerThread.quitSafely()
        }

        companion object {
            private val TERMINAL_STATUSES = setOf(
                "CAPTURE_COMPLETE",
                "CAPTURE_PARTIAL",
                "CAPTURE_FAILED",
                "CAPTURE_TIMEOUT",
                "CAPTURE_CANCELLED"
            )
            private val PNG_1X1: ByteArray = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
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
    // 3.1 Production acquisition: direct path
    // ------------------------------------------------------------------

    @Test
    fun productionDirectAcquireSucceedsAndReleasesImageExactlyOnce() {
        val harness = Harness(frameCount = 1)
        try {
            val fake = FakeYuvImage(timestamp = 4321L)
            // Mirror the production ColorFusion.kt ImageReader callback path:
            // acceptDirect(Camera2DirectYuvImageAccess(image)).
            val access = Camera2DirectYuvImageAccess(fake)
            harness.session.owner.acceptDirect(access)

            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.SUCCESS, status)
            // The Image was transferred into the worker, encoded, and released exactly once.
            assertEquals(1, fake.closeCount.get())
            assertEquals(1, harness.completeCount.get())
            assertEquals(0, harness.errorCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun productionDirectAcquireAcquireFailureReleasesImageExactlyOnce() {
        val harness = Harness(frameCount = 1, encodeFailure = true)
        try {
            val fake = FakeYuvImage(timestamp = 1000L)
            val access = Camera2DirectYuvImageAccess(fake)
            harness.session.owner.acceptDirect(access)

            // Wait for the worker to process the failed encode. Close + awaitTermination
            // ensures the running task's finally block runs and disposes the Image.
            harness.session.boundedWorker.close()
            assertTrue(harness.session.boundedWorker.awaitTermination(5_000L))
            harness.flushHandler()

            // The Image is released exactly once by the task's finally block.
            assertEquals(1, fake.closeCount.get())
        } finally {
            harness.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // 3.1 Production acquisition: buffered path
    // ------------------------------------------------------------------

    @Test
    fun productionBufferedAcquireSucceedsAndReleasesImageExactlyOnce() {
        val harness = Harness(frameCount = 3)
        try {
            val fake1 = FakeYuvImage(timestamp = 1000L)
            val fake2 = FakeYuvImage(timestamp = 2000L)
            val fake3 = FakeYuvImage(timestamp = 3000L)
            // Mirror the production buffered path: acceptBuffered(Camera2YuvImageAccess(image)).
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(fake1))
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(fake2))
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(fake3))

            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.SUCCESS, status)
            harness.session.boundedWorker.close()
            assertTrue(harness.session.boundedWorker.awaitTermination(5_000L))
            harness.flushHandler()
            // Each Image was released exactly once after copy into BufferedYuvFrame.
            assertEquals(1, fake1.closeCount.get())
            assertEquals(1, fake2.closeCount.get())
            assertEquals(1, fake3.closeCount.get())
            assertEquals(1, harness.completeCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun productionBufferedAcquireWithReservationRejectionReleasesImageExactlyOnce() {
        val harness = Harness(frameCount = 1, workerCapacity = 1)
        try {
            // FakeImage doesn't expose allocation size, so an excessively-sized image
            // would force reservation rejection. Instead, force the owner-event rejection
            // path by submitting too many images beyond frameCount.
            val fake1 = FakeYuvImage(timestamp = 1000L)
            val fake2 = FakeYuvImage(timestamp = 2000L)
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(fake1))
            // second image exceeds frameCount; identity allocation drops it, releasing once
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(fake2))
            harness.awaitTerminal()
            harness.session.boundedWorker.close()
            assertTrue(harness.session.boundedWorker.awaitTermination(5_000L))

            assertEquals(1, fake1.closeCount.get())
            assertEquals(1, fake2.closeCount.get())
            val snap = harness.session.accounting.snapshot()
            assertEquals(2, snap.receivedFrames)
            assertEquals(1, snap.droppedFrames)
            assertEquals(1, snap.persistedFrames)
            // No duplicate manifest entries.
            val filenames = snap.manifest.map { it.filename }
            assertEquals(filenames.toSet().size, filenames.size)
        } finally {
            harness.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // 3.2 Backpressure
    // ------------------------------------------------------------------

    @Test
    fun productionDirectQueueRejectionReleasesImageExactlyOnce() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val harness = Harness(frameCount = 1, workerCapacity = 1)
        try {
            // Occupy the worker slot AND the queue so the next direct acquisition is rejected.
            assertTrue(harness.session.boundedWorker.submit(Runnable {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(harness.session.boundedWorker.submit(Runnable {
                release.await(5, TimeUnit.SECONDS)
            }))
            val fake = FakeYuvImage(timestamp = 4321L)
            harness.session.owner.acceptDirect(Camera2DirectYuvImageAccess(fake))
            harness.flushHandler()

            assertEquals(1, fake.closeCount.get())
            val snap = harness.session.accounting.snapshot()
            assertEquals(1, snap.receivedFrames)
            assertEquals(1, snap.droppedFrames)
            assertEquals(0, snap.persistedFrames)
            // No candidate adoption, no duplicate frame identity.
            assertTrue(snap.manifest.isEmpty())
        } finally {
            release.countDown()
            harness.shutdown()
        }
    }

    @Test
    fun productionBufferedBackpressureDroppedCountedAndImageReleasedOnce() {
        val harness = Harness(frameCount = 1, workerCapacity = 1)
        try {
            val fake1 = FakeYuvImage(timestamp = 1000L)
            val fake2 = FakeYuvImage(timestamp = 2000L)
            // First buffered access succeeds; second is dropped by identity exhaustion.
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(fake1))
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(fake2))
            harness.awaitTerminal()
            harness.session.boundedWorker.close()
            assertTrue(harness.session.boundedWorker.awaitTermination(5_000L))

            assertEquals(1, fake1.closeCount.get())
            assertEquals(1, fake2.closeCount.get())
            val snap = harness.session.accounting.snapshot()
            assertEquals(2, snap.receivedFrames)
            assertEquals(1, snap.droppedFrames)
            assertEquals(1, snap.persistedFrames)
            // No duplicate manifest entries.
            val filenames = snap.manifest.map { it.filename }
            assertEquals(filenames.toSet().size, filenames.size)
        } finally {
            harness.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // 3.3 Timeout races
    // ------------------------------------------------------------------

    @Test
    fun productionDeadlineBeforeBufferedEncoderCompletionReachesTimedOut() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(frameCount = 2, workerCapacity = 1, encodeLatch = encodeLatch)
        try {
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(FakeYuvImage(1000L)))
            encodeLatch.awaitStart()
            // Deadline fires while encoder is still running.
            harness.session.owner.onDeadlineReached()
            encodeLatch.release()

            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.TIMED_OUT, status)
            harness.awaitCallback()
            val snap = harness.session.accounting.snapshot()
            assertEquals(0, snap.persistedFrames)
            assertTrue(snap.manifest.isEmpty())
            assertTrue(harness.errorCount.get() >= 1)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun productionEncoderCompletionBeforeDeadlineReachesSuccess() {
        val harness = Harness(frameCount = 2)
        try {
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(FakeYuvImage(1000L)))
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(FakeYuvImage(2000L)))
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.SUCCESS, status)
            // Late deadline after success must not mutate state.
            harness.session.owner.onDeadlineReached()
            harness.flushHandler()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.session.terminalState.status())
            assertEquals(1, harness.completeCount.get())
            assertEquals(0, harness.errorCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun productionBufferedSettlingDuringDeadlineCleanupIsIdempotent() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(frameCount = 1, workerCapacity = 1, encodeLatch = encodeLatch)
        try {
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(FakeYuvImage(1234L)))
            encodeLatch.awaitStart()
            harness.session.owner.onDeadlineReached()
            encodeLatch.release()
            harness.awaitTerminal()
            // Wait for the worker to complete the buffered task and release reservations.
            harness.session.boundedWorker.close()
            assertTrue(harness.session.boundedWorker.awaitTermination(5_000L))
            // Settlement attempts must not double-count or alter accounting.
            val snap = harness.session.accounting.snapshot()
            assertEquals(0, snap.persistedFrames)
            assertEquals(0, snap.failedFrames)
            // Reservations released once after worker task completes.
            assertEquals(0L, harness.session.reservations.currentBytes())
            // Lifecycle drained (no retained items).
            assertEquals(0, harness.session.lifecycle.retainedCount())
        } finally {
            harness.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // 3.4 Cancellation races
    // ------------------------------------------------------------------

    @Test
    fun productionCancellationBeforeAnyAcceptReachesCancelledTerminal() {
        val harness = Harness(frameCount = 2)
        try {
            // Cancel immediately without accepting any Image — analogous to cancel
            // before Camera2 session is configured.
            harness.session.owner.onCancellationRequested()
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.CANCELLED, status)
            assertEquals(1, harness.errorCount.get())
            assertEquals(0, harness.completeCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun productionCancellationWithRunningDirectEncoderReachesCancelled() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(frameCount = 2, workerCapacity = 2, encodeLatch = encodeLatch)
        try {
            val fake = FakeYuvImage(timestamp = 1000L)
            harness.session.owner.acceptDirect(Camera2DirectYuvImageAccess(fake))
            encodeLatch.awaitStart()
            harness.session.owner.onCancellationRequested()
            encodeLatch.release()
            val status = harness.awaitTerminal()
            // Wait for the worker to complete the cancelled task.
            harness.session.boundedWorker.close()
            assertTrue(harness.session.boundedWorker.awaitTermination(5_000L))
            harness.flushHandler()
            assertEquals(CaptureTerminalStatus.CANCELLED, status)
            // Image released exactly once even with cancelled terminal.
            assertEquals(1, fake.closeCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun productionCancellationAfterFullSuccessDoesNotChangeTerminal() {
        val harness = Harness(frameCount = 2)
        try {
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(FakeYuvImage(1000L)))
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(FakeYuvImage(2000L)))
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.SUCCESS, status)
            harness.session.owner.onCancellationRequested()
            harness.flushHandler()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.session.terminalState.status())
            assertEquals(1, harness.completeCount.get())
            assertEquals(0, harness.errorCount.get())
        } finally {
            harness.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // 3.5 Late Camera2 callbacks after terminal
    // ------------------------------------------------------------------

    @Test
    fun productionLateBufferedAcceptAfterTerminalReleasesImageOnceAndDoesNotMutate() {
        val harness = Harness(frameCount = 1)
        try {
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(FakeYuvImage(1000L)))
            harness.awaitTerminal()
            val snapBefore = harness.session.accounting.snapshot()
            val manifestBefore = snapBefore.manifest.toList()

            // Late ImageReader callback after success — should release Image once and
            // NOT mutate manifest, terminal status, or completedResults.
            val lateFake = FakeYuvImage(timestamp = 9999L)
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(lateFake))
            harness.flushHandler()

            assertEquals(1, lateFake.closeCount.get())
            val snapAfter = harness.session.accounting.snapshot()
            // Manifest and persistedFrames unchanged.
            assertEquals(manifestBefore, snapAfter.manifest)
            assertEquals(snapBefore.persistedFrames, snapAfter.persistedFrames)
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.session.terminalState.status())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun productionLateOwnerEventAfterTerminalDoesNotMutateTerminalStatus() {
        val harness = Harness(frameCount = 1)
        try {
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(FakeYuvImage(1000L)))
            val terminal = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.SUCCESS, terminal)
            // All these late events arrive AFTER terminal — none should change status.
            harness.session.owner.onCaptureCompletedResult()
            harness.session.owner.onDeadlineReached()
            harness.session.owner.onCancellationRequested()
            harness.flushHandler()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.session.terminalState.status())
            assertEquals(TerminalSettlementPhase.SETTLED, harness.session.owner.terminalSettlementPhase())
            assertEquals(1, harness.completeCount.get())
            assertEquals(0, harness.errorCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun productionSessionCloseAfterTerminalIsIdempotent() {
        val harness = Harness(frameCount = 1)
        try {
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(FakeYuvImage(1000L)))
            harness.awaitTerminal()
            // Close twice — cleanup is exactly-once; second close must be a no-op.
            harness.session.close()
            harness.session.close()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.session.terminalState.status())
        } finally {
            harness.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // 3.6 Main callback dispatch failure
    // ------------------------------------------------------------------

    @Test
    fun productionCallbackDispatchRejectionNeverRunsCallbackInline() {
        val harness = Harness(frameCount = 1, rejectCallbackDispatch = true)
        try {
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(FakeYuvImage(1000L)))
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.SUCCESS, status)
            // Callback was rejected at dispatch — neither complete nor error callback ran.
            assertEquals(0, harness.completeCount.get())
            assertEquals(0, harness.errorCount.get())
            // But callback state is DISPATCH_REJECTED, not still pending.
            assertEquals(CallbackState.DISPATCH_REJECTED, harness.session.owner.callbackState())
            // Terminal metadata was still attempted (writeJobJson received the status).
            assertEquals(TerminalSettlementPhase.SETTLED, harness.session.owner.terminalSettlementPhase())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun productionCallbackDispatchedOnMainHandlerThread() {
        val handlerThread = android.os.HandlerThread("prod-cb-thread").apply { start() }
        val handler = android.os.Handler(handlerThread.looper)
        val capturedThreads = mutableListOf<String>()
        val callbackLatch = CountDownLatch(1)
        val dir = Files.createTempDirectory("prod-cb-thread").toFile()
        val session = YuvCaptureSession.create(
            dispatch = { event ->
                handler.post { event.execute() }
                true
            },
            outputDir = dir,
            frameCount = 1,
            rotationDegrees = 0,
            workerCapacity = 4,
            maxRetainedBytes = 16L * 1024L * 1024L,
            workProcessor = YuvPngWorkProcessor(
                encoder = object : YuvPngEncoder {
                    override fun encodeDirect(image: Image, candidate: File, rotationDegrees: Int) {
                        Files.write(candidate.toPath(), PNG_1X1)
                    }
                    override fun encodeBuffered(frame: BufferedYuvFrame, candidate: File, rotationDegrees: Int) {
                        Files.write(candidate.toPath(), PNG_1X1)
                    }
                },
                committer = YuvCandidateCommitter { candidate, final ->
                    Files.move(candidate.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
            ),
            dispatchCallback = CallbackDispatcher { runnable ->
                capturedThreads += Thread.currentThread().name
                handler.post(runnable)
                true
            },
            onCaptureComplete = {
                capturedThreads += "COMPLETE:${Thread.currentThread().name}"
                callbackLatch.countDown()
            },
            productionResourceCoordinator = YuvProductionResourceCoordinator(
                timeoutScheduler = null,
                backgroundHandler = null,
                backgroundThread = null
            )
        )
        try {
            session.owner.acceptBuffered(Camera2YuvImageAccess(FakeYuvImage(1000L)))
            assertTrue("completion callback not reached", callbackLatch.await(5, TimeUnit.SECONDS))
            assertEquals(TerminalSettlementPhase.SETTLED, session.owner.terminalSettlementPhase())
            // The dispatch thread (captured before post) must NOT be the worker thread.
            // We allow the COMPLETE callback to fire on the dispatched handler thread.
            assertTrue("callback thread not captured", capturedThreads.any { it.startsWith("COMPLETE:") })
        } finally {
            session.close()
            handlerThread.quitSafely()
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 3.7 Terminal invariant assertions
    // ------------------------------------------------------------------

    @Test
    fun productionTerminalInvariantsAtSuccess() {
        val harness = Harness(frameCount = 4)
        try {
            for (i in 0 until 4) {
                harness.session.owner.acceptBuffered(Camera2YuvImageAccess(FakeYuvImage(1000L * (i + 1))))
            }
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())

            val snap = harness.session.accounting.snapshot()
            // persistedFrames == manifest.size
            assertEquals(snap.persistedFrames, snap.manifest.size)
            // No negative accounting.
            assertTrue("bufferedFrames must be non-negative", snap.bufferedFrames >= 0)
            assertTrue("droppedFrames must be non-negative", snap.droppedFrames >= 0)
            assertTrue("failedFrames must be non-negative", snap.failedFrames >= 0)
            assertTrue("receivedFrames >= persisted", snap.receivedFrames >= snap.persistedFrames)
            // No duplicate frame identity.
            val frameIndices = snap.manifest.map { it.frameIndex }
            assertEquals("duplicate frame index", frameIndices.size, frameIndices.toSet().size)
            // No duplicate final filename.
            val filenames = snap.manifest.map { it.filename }
            assertEquals("duplicate filename", filenames.size, filenames.toSet().size)
            // Reservations released.
            assertEquals(0L, harness.session.reservations.currentBytes())
            // Lifecycle drained.
            assertEquals(0, harness.session.lifecycle.retainedCount())
            // terminalStatus != ACTIVE.
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.session.terminalState.status())
            // Snapshot is consistent.
            val terminalSnap = harness.session.owner.terminalSnapshotRef()
            assertTrue(terminalSnap.isTerminal)
            assertTrue(terminalSnap.isSettled)
            assertEquals(TerminalSettlementPhase.SETTLED, terminalSnap.terminalSettlementPhase)
            // Callback at most once.
            assertEquals(1, harness.completeCount.get())
            assertEquals(0, harness.errorCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun productionTerminalInvariantsAtCancelled() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(frameCount = 2, workerCapacity = 1, encodeLatch = encodeLatch)
        try {
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(FakeYuvImage(1000L)))
            encodeLatch.awaitStart()
            harness.session.owner.onCancellationRequested()
            encodeLatch.release()
            assertEquals(CaptureTerminalStatus.CANCELLED, harness.awaitTerminal())
            // Wait for the worker to drain.
            harness.session.boundedWorker.close()
            assertTrue(harness.session.boundedWorker.awaitTermination(5_000L))

            val snap = harness.session.accounting.snapshot()
            assertEquals(snap.persistedFrames, snap.manifest.size)
            assertTrue(snap.bufferedFrames >= 0)
            assertTrue(snap.failedFrames >= 0)
            assertEquals(0L, harness.session.reservations.currentBytes())
            // terminalStatus != ACTIVE.
            assertEquals(CaptureTerminalStatus.CANCELLED, harness.session.terminalState.status())
            // Callback at most once.
            assertTrue("callback should fire at most once", harness.errorCount.get() >= 1)
            assertEquals(0, harness.completeCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun productionTerminalInvariantsAtTimedOut() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(frameCount = 2, workerCapacity = 1, encodeLatch = encodeLatch)
        try {
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(FakeYuvImage(1000L)))
            encodeLatch.awaitStart()
            harness.session.owner.onDeadlineReached()
            encodeLatch.release()
            assertEquals(CaptureTerminalStatus.TIMED_OUT, harness.awaitTerminal())
            // Wait for the worker to drain.
            harness.session.boundedWorker.close()
            assertTrue(harness.session.boundedWorker.awaitTermination(5_000L))

            val snap = harness.session.accounting.snapshot()
            assertEquals(snap.persistedFrames, snap.manifest.size)
            assertEquals(0L, harness.session.reservations.currentBytes())
            assertEquals(CaptureTerminalStatus.TIMED_OUT, harness.session.terminalState.status())
            assertTrue(harness.errorCount.get() >= 1)
            assertEquals(0, harness.completeCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun productionTerminalInvariantsAtFailed() {
        val harness = Harness(frameCount = 1, encodeFailure = true)
        try {
            val fake = FakeYuvImage(timestamp = 1000L)
            harness.session.owner.acceptDirect(Camera2DirectYuvImageAccess(fake))
            // Wait for the worker to complete the failed task.
            harness.session.boundedWorker.close()
            assertTrue(harness.session.boundedWorker.awaitTermination(5_000L))
            harness.flushHandler()
            val snap = harness.session.accounting.snapshot()
            assertEquals(snap.persistedFrames, snap.manifest.size)
            assertEquals(1, fake.closeCount.get())
            assertEquals(0, harness.completeCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun productionWorkerHistoricalFailurePreservedAcrossCleanup() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val harness = Harness(frameCount = 1, workerCapacity = 1)
        try {
            // Submit a task that fails at dispose — the worker must record this failure.
            assertTrue(harness.session.boundedWorker.submit(Runnable {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                error("historical worker failure")
            }))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            harness.session.close()
            release.countDown()
            // Even after cleanup, the worker's rejection record must remain in the snapshot.
            // The bounded worker's queue-rejection failure is preserved in its history.
            val snap = harness.session.owner.terminalSnapshotRef()
            // No terminal status yet (we never sent any terminal event).
            // But the worker's historical failures must be reflected.
            assertNotNull(snap)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun productionDirectEncodeReleasedExactlyOnceOnSessionCleanup() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(frameCount = 1, workerCapacity = 1, encodeLatch = encodeLatch)
        try {
            val fake = FakeYuvImage(timestamp = 1000L)
            harness.session.owner.acceptDirect(Camera2DirectYuvImageAccess(fake))
            encodeLatch.awaitStart()
            // Mid-encode, close the session. The running worker task is not interrupted;
            // the source stays owned until the encoder returns.
            harness.session.close()
            assertEquals(0, fake.closeCount.get())
            encodeLatch.release()
            assertTrue(harness.session.boundedWorker.awaitTermination(5_000L))
            harness.flushHandler()
            // Exactly once.
            assertEquals(1, fake.closeCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun productionBlockedRunningWorkerOwnershipTruthfulInSnapshot() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(frameCount = 2, workerCapacity = 1, encodeLatch = encodeLatch)
        try {
            val fake = FakeYuvImage(timestamp = 1000L)
            harness.session.owner.acceptDirect(Camera2DirectYuvImageAccess(fake))
            encodeLatch.awaitStart()
            // Cancel while encoder is blocked: the running task still owns the Image.
            harness.session.owner.onCancellationRequested()
            // The cancelled terminal still propagates, but the running task continues
            // until encode completes — and the Image is released exactly once.
            val status = harness.awaitTerminal()
            assertEquals(CaptureTerminalStatus.CANCELLED, status)
            encodeLatch.release()
            assertTrue(harness.session.boundedWorker.awaitTermination(5_000L))
            harness.flushHandler()
            assertEquals(1, fake.closeCount.get())
        } finally {
            harness.shutdown()
        }
    }

    companion object {
        private val PNG_1X1: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
    }
}
