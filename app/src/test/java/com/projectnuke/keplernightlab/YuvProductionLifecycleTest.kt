package com.projectnuke.keplernightlab

import android.media.Image
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 0 production-lifecycle tests. These exercise the SAME production objects that
 * ColorFusion's [captureYuvBurstColorWithMotion] invokes:
 *   - [YuvPreSessionTerminal] (pre-session init failure / pre-session cancellation)
 *   - [YuvProductionResourceCoordinator] (exactly-once external Camera2/infra cleanup)
 *   - [YuvCaptureOwner] settlement via [YuvCaptureSession] with a wired coordinator
 * All waits are deterministic (CountDownLatch based) -- no Thread.sleep/yield/polling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21])
class YuvProductionLifecycleTest {

    private class FakeBufferedAccess(
        private val ts: Long
    ) : YuvImageAccess {
        val releaseCount = AtomicInteger(0)
        @Volatile private var released = false

        override fun timestampNs(): Long =
            if (released) error("timestampNs after release") else ts

        override fun allocationBytes(): Long =
            if (released) error("allocationBytes after release") else 12L

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

    private class Harness(
        val frameCount: Int = 3,
        encodeFailure: Boolean = false,
        rejectCallbackDispatch: Boolean = false,
        throwCallbackDispatch: Boolean = false,
        callbackBodyFailure: Boolean = false
    ) {
        val dir: File = Files.createTempDirectory("yuv-prod-lifecycle").toFile()
        val handlerThread = android.os.HandlerThread("yuv-prod-lifecycle").apply { start() }
        val handler = android.os.Handler(handlerThread.looper)

        // The same production resource coordinator ColorFusion uses.  A SEPARATE
        // infrastructure thread is attached so that perform() (which quits the
        // background thread) never kills the handler that carries owner dispatch and
        // this harness's terminal latch.
        val coordThread = android.os.HandlerThread("yuv-prod-coord").apply { start() }
        val coordinator = YuvProductionResourceCoordinator(
            timeoutScheduler = null,
            backgroundHandler = android.os.Handler(coordThread.looper),
            backgroundThread = coordThread
        )

        val terminalLatch = CountDownLatch(1)

        val completeCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val lastError = AtomicReference<String?>(null)
        val capturedDir = AtomicReference<File?>(null)

        val session: YuvCaptureSession = YuvCaptureSession.create(
            dispatch = { event ->
                handler.post { event.execute() }
                true
            },
            outputDir = dir,
            frameCount = frameCount,
            rotationDegrees = 0,
            workerCapacity = 4,
            maxRetainedBytes = 16L * 1024 * 1024,
            workProcessor = YuvPngWorkProcessor(
                encoder = object : YuvPngEncoder {
                    override fun encodeDirect(image: Image, candidate: File, rotationDegrees: Int) {
                        if (encodeFailure) throw IllegalStateException("forced encode failure")
                        Files.write(candidate.toPath(), PNG_1X1)
                    }
                    override fun encodeBuffered(frame: BufferedYuvFrame, candidate: File, rotationDegrees: Int) {
                        if (encodeFailure) throw IllegalStateException("forced encode failure")
                        Files.write(candidate.toPath(), PNG_1X1)
                    }
                },
                committer = YuvCandidateCommitter { candidate, final ->
                    Files.move(candidate.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
            ),
            postStatus = { },
            dispatchCallback = CallbackDispatcher { runnable ->
                if (throwCallbackDispatch) throw IllegalStateException("dispatch threw")
                if (rejectCallbackDispatch) return@CallbackDispatcher false
                handler.post { runnable.run() }
                true
            },
            writeJobJson = { status, _, _ ->
                handler.post {
                    if (status in TERMINAL_JOB_STATUSES) terminalLatch.countDown()
                }
            },
            saveMotionOnce = { _ -> null to null },
            onCaptureComplete = { file ->
                if (callbackBodyFailure) throw IllegalStateException("callback body threw")
                completeCount.incrementAndGet()
                capturedDir.set(file)
            },
            onCaptureError = { msg, _ ->
                if (callbackBodyFailure) throw IllegalStateException("callback body threw")
                errorCount.incrementAndGet()
                lastError.set(msg)
            },
            productionResourceCoordinator = coordinator
        )

        fun feedAll() {
            for (i in 0 until frameCount) {
                session.owner.acceptBuffered(FakeBufferedAccess(1000L + i))
            }
        }

        fun awaitTerminal(timeoutSec: Long = 10): CaptureTerminalStatus {
            assertTrue("terminal not reached", terminalLatch.await(timeoutSec, TimeUnit.SECONDS))
            flushHandler()
            return session.terminalState.status()
        }

        fun flushHandler() {
            val latch = CountDownLatch(1)
            assertTrue("handler flush did not complete", handler.post { latch.countDown() })
            assertTrue(latch.await(2, TimeUnit.SECONDS))
        }

        fun shutdown() {
            session.close()
            handlerThread.quitSafely()
            coordThread.quitSafely()
        }

        companion object {
            private val TERMINAL_JOB_STATUSES = setOf(
                "CAPTURE_COMPLETE", "CAPTURE_PARTIAL", "CAPTURE_FAILED", "CAPTURE_TIMEOUT", "CAPTURE_CANCELLED"
            )
        }
    }

    // Pre-session terminal (the SAME object ColorFusion invokes)
    @Test
    fun preSessionInitFailureDispatchesOnErrorAndRunsCleanup() {
        val statusDispatched = AtomicInteger(0)
        val errorsRun = AtomicInteger(0)
        val cleanupRuns = AtomicInteger(0)
        val terminal = YuvPreSessionTerminal(
            dispatchStatus = { statusDispatched.incrementAndGet(); true },
            dispatchError = { errorsRun.incrementAndGet(); true },
            cleanup = { cleanupRuns.incrementAndGet() }
        )
        assertTrue(terminal.finish("StreamConfigurationMap missing"))
        assertEquals(1, statusDispatched.get())
        assertEquals(1, errorsRun.get())
        assertEquals(1, cleanupRuns.get())
        assertTrue(terminal.isTerminal())
        // Exactly-once: a second terminal event is a no-op but cleanup stays at 1.
        assertFalse(terminal.finish("StreamConfigurationMap missing"))
        assertEquals(1, cleanupRuns.get())
        assertEquals(1, errorsRun.get())
    }

    @Test
    fun preSessionInitFailureWithRejectedDispatchStillRunsCleanup() {
        val dispatchAttempts = AtomicInteger(0)
        val cleanupRuns = AtomicInteger(0)
        val terminal = YuvPreSessionTerminal(
            dispatchStatus = { false },
            dispatchError = { dispatchAttempts.incrementAndGet(); false },
            cleanup = { cleanupRuns.incrementAndGet() }
        )
        assertTrue(terminal.finish("Pictures dir unavailable"))
        // The onError user callback is not executed inline on a rejected dispatch; the
        // dispatch is attempted exactly once and cleanup still runs.
        assertEquals(1, dispatchAttempts.get())
        assertEquals(1, cleanupRuns.get())
    }

    @Test
    fun preSessionCancellationRunsCleanupOnce() {
        var cleanupRuns = 0
        val terminal = YuvPreSessionTerminal(
            dispatchStatus = { true },
            dispatchError = { true },
            cleanup = { cleanupRuns++ }
        )
        assertTrue(terminal.finish("cancelled"))
        assertFalse(terminal.finish("cancelled"))
        assertEquals(1, cleanupRuns)
        assertTrue(terminal.isTerminal())
    }

    @Test
    fun coordinatorReleasesAttachedResourcesExactlyOnce() {
        val ht = android.os.HandlerThread("coord-test").apply { start() }
        val h = android.os.Handler(ht.looper)
        val coord = YuvProductionResourceCoordinator(null, h, ht)
        assertTrue(coord.perform())
        assertEquals(1, coord.performCount())
        assertEquals(
            setOf("Background.handler", "BackgroundThread.quit"),
            coord.releasedResourceTags().filter { it.startsWith("Background") }.toSet()
        )
        // Second perform is a no-op: exactly-once.
        assertFalse(coord.perform())
        assertEquals(1, coord.performCount())
        ht.quitSafely()
    }

    // External cleanup on session terminal settlement (wired coordinator)
    @Test
    fun normalSuccessPerformsExternalCleanupExactlyOnce() {
        val harness = Harness(frameCount = 3)
        try {
            harness.feedAll()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
            assertEquals(1, harness.completeCount.get())
            assertEquals(0, harness.errorCount.get())
            assertEquals(1, harness.coordinator.performCount())
            assertTrue(harness.coordinator.releasedResourceTags().isNotEmpty())
            // Idempotent: ColorFusion's own productionCleanup would not double-release.
            assertFalse(harness.coordinator.perform())
            assertEquals(1, harness.coordinator.performCount())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun terminalFailurePerformsExternalCleanupExactlyOnce() {
        val harness = Harness(frameCount = 3)
        try {
            harness.session.owner.onCaptureFailed(RuntimeException("terminal failure"), "failure")
            assertEquals(CaptureTerminalStatus.FAILED, harness.awaitTerminal())
            assertEquals(0, harness.completeCount.get())
            assertEquals(1, harness.coordinator.performCount())
            assertFalse(harness.coordinator.perform())
            assertEquals(1, harness.coordinator.performCount())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun callbackDispatchRejectedDoesNotRunCallbackButStillCleansUp() {
        val harness = Harness(frameCount = 3, rejectCallbackDispatch = true)
        try {
            harness.feedAll()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
            assertEquals(0, harness.completeCount.get())
            assertEquals(0, harness.errorCount.get())
            assertEquals(1, harness.coordinator.performCount())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun callbackDispatchThrowsButStillCleansUpExactlyOnce() {
        val harness = Harness(frameCount = 3, throwCallbackDispatch = true)
        try {
            harness.feedAll()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
            assertEquals(1, harness.coordinator.performCount())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun callbackBodyThrowsButStillCleansUpExactlyOnce() {
        val harness = Harness(frameCount = 3, callbackBodyFailure = true)
        try {
            harness.feedAll()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
            harness.flushHandler()
            assertEquals(1, harness.coordinator.performCount())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun lateImageReaderCallbackAfterTerminalDoesNotMutateTerminalState() {
        val harness = Harness(frameCount = 3)
        try {
            harness.feedAll()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
            val settledSnapshot = harness.session.owner.terminalSnapshotRef()
            // Late ImageReader callback: a completed-result event posted after terminal.
            harness.session.owner.onCaptureCompletedResult()
            harness.flushHandler()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.session.terminalState.status())
            assertEquals(settledSnapshot.completedResults, harness.session.owner.terminalSnapshotRef().completedResults)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun lateCameraDeviceCallbackAfterTerminalDoesNotMutateTerminalState() {
        val harness = Harness(frameCount = 3)
        try {
            harness.feedAll()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
            harness.session.owner.onCaptureFailed(RuntimeException("late"), "CameraDevice.onError")
            harness.flushHandler()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.session.terminalState.status())
            assertEquals(0, harness.session.accounting.snapshot().failedFrames)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun cameraCaptureFailureIncrementsFailedAccountingAndTerminatesFailed() {
        val harness = Harness(frameCount = 3)
        try {
            harness.session.owner.onCaptureFailed(RuntimeException("capture failed"), "onCaptureFailed")
            assertEquals(CaptureTerminalStatus.FAILED, harness.awaitTerminal())
            // Authoritative failed-capture accounting incremented exactly once and is
            // present in terminal metadata.
            assertEquals(1, harness.session.accounting.snapshot().failedFrames)
            assertEquals(1, harness.session.owner.terminalSnapshotRef().failedFrames)
            assertEquals(1, harness.errorCount.get())
            assertEquals(1, harness.coordinator.performCount())
        } finally {
            harness.shutdown()
        }
    }

    private companion object {
        val PNG_1X1: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
    }
}
