package com.projectnuke.keplernightlab

import android.media.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 1 corrected state-model tests: a camera-acquisition deadline is NOT proof
 * that accepted persistence work failed.  When every requested frame arrived
 * cleanly and accepted worker tasks are still draining, the owner must NOT publish
 * CAPTURE_PARTIAL / processing handoff; it settles only by drain truth:
 *
 *  - full drain (persisted == requested && buffered/reserved/queued/inFlight == 0)
 *    -> SUCCESS;
 *  - concrete persistence failure during drain -> concrete FAILED truth;
 *  - bounded persistence-drain deadline expiry -> actual persistence timeout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21])
class YuvCaptureOwnerDrainTest {

    private companion object {
        val PNG_1X1: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
    }

    private class FakeBufferedAccess(
        private val ts: Long,
        private val bytes: Long = 12L
    ) : YuvImageAccess {
        @Volatile private var released = false
        override fun timestampNs(): Long = if (released) error("timestampNs after release") else ts
        override fun allocationBytes(): Long = if (released) error("allocationBytes after release") else bytes
        override fun copy(frameIndex: Int): BufferedYuvFrame =
            if (released) error("copy after release")
            else BufferedYuvFrame(
                frameIndex, ts, 1, 1,
                byteArrayOf(0), byteArrayOf(0), byteArrayOf(0),
                1, 1, 1, 1, 1, 1
            )
        override fun release() {
            check(!released) { "released twice" }
            released = true
        }
    }

    /**
     * Sequential-call controlled encoder: call N (1-based) blocks when
     * [blockFromCall] <= N until [release].  [failSubsequentEncodes] makes every
     * call above the first throw after resume — simulating persistence failure
     * DURING drain.
     */
    private class ControlledYuvEncoder : YuvPngEncoder {
        private val blockFromCall = AtomicInteger(Int.MAX_VALUE)
        private val startedCalls = AtomicInteger(0)
        private val blockedSignal = CountDownLatch(1)
        private val releaseLatch = CountDownLatch(1)
        @Volatile private var failSubsequent = false

        fun blockFrom(callNumber: Int) { blockFromCall.set(callNumber) }
        fun failSubsequentEncodes() { failSubsequent = true }
        fun release() { releaseLatch.countDown() }
        fun awaitBlockedCall() {
            assertTrue("encoder never reached the blocked call", blockedSignal.await(5, TimeUnit.SECONDS))
        }

        override fun encodeDirect(image: Image, candidate: File, rotationDegrees: Int) {
            encodeCommon(candidate)
        }

        override fun encodeBuffered(frame: BufferedYuvFrame, candidate: File, rotationDegrees: Int) {
            encodeCommon(candidate)
        }

        private fun encodeCommon(candidate: File) {
            val call = startedCalls.incrementAndGet()
            if (call >= blockFromCall.get()) {
                blockedSignal.countDown()
                assertTrue("blocked encode never released", releaseLatch.await(10, TimeUnit.SECONDS))
            }
            if (failSubsequent && call > 1) throw IllegalStateException("forced encode failure")
            Files.write(candidate.toPath(), PNG_1X1)
        }
    }

    private class DrainHarness(
        val frameCount: Int = 4,
        // Capacity >= frameCount so no backpressure drop pollutes the clean-drain
        // fixtures (a genuine drop legitimately routes to the partial policy).
        workerCapacity: Int = 4
    ) {
        val dir: File = Files.createTempDirectory("yuv-drain-test").toFile()
        val handlerThread = android.os.HandlerThread("yuv-drain-test").apply { start() }
        val handler: android.os.Handler = android.os.Handler(handlerThread.looper)
        val encoder = ControlledYuvEncoder()

        val onCaptureCompleteCount = AtomicInteger(0)
        val onCaptureErrorCount = AtomicInteger(0)
        val errorMessage = AtomicReference<String?>(null)
        private val completeCallbackLatch = CountDownLatch(1)
        private val errorCallbackLatch = CountDownLatch(1)

        /** Last terminal metadata snapshot written through the session gateway. */
        @Volatile var metaStatus: String? = null
            private set
        @Volatile var metaQueuedWork: Int = -1
            private set
        @Volatile var metaInFlightWork: Int = -1
            private set
        @Volatile var metaBufferedFrames: Int = -1
            private set
        @Volatile var metaReservedCount: Int = -1
            private set
        @Volatile var metaPersistedFrames: Int = -1
            private set
        @Volatile var metaReceivedFrames: Int = -1
            private set
        @Volatile var metaFailedFrames: Int = -1
            private set
        @Volatile var metaDroppedFrames: Int = -1
            private set

        private val terminalLatch = CountDownLatch(1)

        val session: YuvCaptureSession = YuvCaptureSession.create(
            dispatch = { event -> handler.post { event.execute() }; true },
            outputDir = dir,
            frameCount = frameCount,
            rotationDegrees = 0,
            workerCapacity = workerCapacity,
            maxRetainedBytes = 16L * 1024 * 1024,
            workProcessor = YuvPngWorkProcessor(
                encoder = encoder,
                committer = YuvCandidateCommitter { candidate, final ->
                    Files.move(candidate.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
            ),
            postStatus = { _ -> true },
            dispatchCallback = CallbackDispatcher { runnable ->
                if (!handler.post(runnable)) runnable.run()
                true
            },
            writeJobJson = { status, saved, _, received, persisted, failed, dropped,
                             _, _, _, _, _, _, _, queued, inFlight, buffered, reserved ->
                handler.post {
                    metaStatus = status
                    metaQueuedWork = queued
                    metaInFlightWork = inFlight
                    metaBufferedFrames = buffered
                    metaReservedCount = reserved
                    metaPersistedFrames = persisted
                    metaReceivedFrames = received
                    metaFailedFrames = failed
                    metaDroppedFrames = dropped
                    check(saved == persisted) { "savedFrames=$saved must mirror persistedFrames=$persisted" }
                    if (status in TERMINAL_JOB_STATUSES) terminalLatch.countDown()
                }
            },
            onCaptureComplete = { _ ->
                onCaptureCompleteCount.incrementAndGet()
                completeCallbackLatch.countDown()
            },
            onCaptureError = { msg, _ ->
                onCaptureErrorCount.incrementAndGet()
                errorMessage.set(msg)
                errorCallbackLatch.countDown()
            },
            productionResourceCoordinator = YuvProductionResourceCoordinator(
                timeoutScheduler = null,
                backgroundHandler = null,
                backgroundThread = null
            ),
            startTerminalObserverOnCreate = true,
            // Tests trigger the bounded drain deadline manually and deterministically.
            schedulePersistenceDrainDeadline = null
        )

        /** Sends [frameCount] cleanly-acquired buffered frames. */
        fun sendAllFrames() {
            repeat(frameCount) { index ->
                session.owner.acceptBuffered(FakeBufferedAccess((index + 1) * 1000L))
            }
        }

        /** Fires the camera-acquisition deadline and settles its owner event. */
        fun fireAcquisitionDeadline() {
            session.owner.onDeadlineReached()
            flushHandler()
        }

        /** Fires the bounded persistence-drain deadline and settles its owner event. */
        fun fireDrainDeadline() {
            session.owner.onPersistenceDrainDeadlineReached()
            flushHandler()
        }

        fun awaitTerminal(timeoutSec: Long = 10): CaptureTerminalStatus {
            assertTrue("terminal not reached", terminalLatch.await(timeoutSec, TimeUnit.SECONDS))
            flushHandler()
            return session.terminalState.status()
        }

        /** Deterministically waits for the error user callback and returns its message. */
        fun awaitErrorCallback(timeoutSec: Long = 10): String? {
            assertTrue("error callback not fired", errorCallbackLatch.await(timeoutSec, TimeUnit.SECONDS))
            flushHandler()
            return errorMessage.get()
        }

        /** Deterministically waits for the success user callback. */
        fun awaitCompleteCallback(timeoutSec: Long = 10) {
            assertTrue("complete callback not fired", completeCallbackLatch.await(timeoutSec, TimeUnit.SECONDS))
            flushHandler()
        }

        fun flushHandler() {
            val latch = CountDownLatch(1)
            assertTrue("handler flush did not complete", handler.post { latch.countDown() })
            assertTrue(latch.await(5, TimeUnit.SECONDS))
        }

        fun shutdown() {
            session.close()
            handlerThread.quitSafely()
        }

        companion object {
            val TERMINAL_JOB_STATUSES = setOf(
                "CAPTURE_COMPLETE", "CAPTURE_PARTIAL", "CAPTURE_FAILED", "CAPTURE_TIMEOUT", "CAPTURE_CANCELLED"
            )
        }
    }

    /**
     * Shared physical-shape fixture: requested=4, Camera2 delivered 4, first frame
     * persisted (1), second encoding, two still buffered — exactly the S24 evidence
     * shape minus the invalid PARTIAL_SUCCESS publication.
     */
    private fun onePersistedThreePendingHarness(): DrainHarness {
        val harness = DrainHarness(frameCount = 4, workerCapacity = 4)
        harness.encoder.blockFrom(2)
        harness.sendAllFrames()
        harness.encoder.awaitBlockedCall()
        harness.flushHandler()
        return harness
    }

    @Test
    fun allImagesReceived_workerStillDraining_doesNotPublishPartialSuccess() {
        val harness = onePersistedThreePendingHarness()
        try {
            assertEquals(1, harness.session.accounting.snapshot().persistedFrames)
            harness.fireAcquisitionDeadline()
            // The burst is fully acquired and merely draining: NO partial publication.
            assertEquals(CaptureTerminalStatus.ACTIVE, harness.session.terminalState.status())
            assertTrue(
                "unexpected terminal metadata write: ${harness.metaStatus}",
                harness.metaStatus == null || harness.metaStatus == "CAPTURING"
            )
            assertNull(harness.session.terminalRequestHandoff.request())
            // The drain completes by truth afterwards.
            harness.encoder.release()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
            assertEquals(4, harness.session.accounting.snapshot().persistedFrames)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun allImagesReceived_workerStillDraining_doesNotPublishProcessingHandoff() {
        val harness = onePersistedThreePendingHarness()
        try {
            harness.fireAcquisitionDeadline()
            harness.flushHandler()
            // No processing handoff may exist while accepted source persistence
            // work remains: fusion-ready would process an incomplete burst.
            assertNull(harness.session.terminalRequestHandoff.request())
            assertEquals(0, harness.onCaptureCompleteCount.get())
            assertEquals(0, harness.onCaptureErrorCount.get())
            // Still nothing after additional dispatcher settling.
            harness.flushHandler()
            assertNull(harness.session.terminalRequestHandoff.request())
            harness.encoder.release()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
            // The handoff publishes only after the drain completed.
            harness.awaitCompleteCallback()
            assertEquals(1, harness.onCaptureCompleteCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun allImagesReceived_workerDrainsToFour_thenPublishesSuccess() {
        val harness = onePersistedThreePendingHarness()
        try {
            harness.fireAcquisitionDeadline()
            harness.encoder.release()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
            harness.awaitCompleteCallback()
            val snap = harness.session.accounting.snapshot()
            assertEquals(4, snap.persistedFrames)
            assertEquals(4, snap.manifest.size)
            assertEquals(0, snap.bufferedFrames)
            assertEquals(0, snap.reservedCount)
            assertEquals(1, harness.onCaptureCompleteCount.get())
            assertEquals(0, harness.onCaptureErrorCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun persistenceFailureDuringDrain_producesConcreteFailure() {
        val harness = onePersistedThreePendingHarness()
        try {
            harness.fireAcquisitionDeadline()
            // Persistence now fails for the still-draining frames.
            harness.encoder.failSubsequentEncodes()
            harness.encoder.release()
            assertEquals(CaptureTerminalStatus.FAILED, harness.awaitTerminal())
            assertEquals(0, harness.onCaptureCompleteCount.get())
            val message = harness.awaitErrorCallback() ?: ""
            assertEquals(1, harness.onCaptureErrorCount.get())
            assertTrue("reason=$message", message.contains("persistence failed during drain"))
            assertTrue("reason=$message", message.contains("forced encode failure"))
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun persistenceDrainTimeout_neverPublishesSuccess() {
        val harness = onePersistedThreePendingHarness()
        try {
            harness.fireAcquisitionDeadline()
            // Bounded persistence-drain deadline expires with work outstanding.
            harness.fireDrainDeadline()
            assertEquals(CaptureTerminalStatus.TIMED_OUT, harness.awaitTerminal())
            assertEquals(0, harness.onCaptureCompleteCount.get())
            val message = harness.awaitErrorCallback() ?: ""
            assertEquals(1, harness.onCaptureErrorCount.get())
            assertTrue("reason=$message", message.contains("persistence drain timeout"))
            assertTrue("reason=$message", message.contains("saved=1/4"))
            // Releasing the workers afterwards can NEVER retroactively succeed.
            harness.encoder.release()
            harness.flushHandler()
            harness.flushHandler()
            assertEquals(CaptureTerminalStatus.TIMED_OUT, harness.session.terminalState.status())
            assertEquals(0, harness.onCaptureCompleteCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun captureStageComplete_requiresZeroQueuedInflightBuffered() {
        val harness = DrainHarness(frameCount = 4, workerCapacity = 4)
        try {
            harness.sendAllFrames()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
            harness.flushHandler()
            assertEquals("CAPTURE_COMPLETE", harness.metaStatus)
            // Terminal metadata observed after settlement must never snapshot
            // queued/in-flight/buffered work as nonzero on a successful handoff.
            assertEquals(0, harness.metaQueuedWork)
            assertEquals(0, harness.metaInFlightWork)
            assertEquals(0, harness.metaBufferedFrames)
            assertEquals(0, harness.metaReservedCount)
            assertEquals(4, harness.metaPersistedFrames)
            assertEquals(4, harness.metaReceivedFrames)
            assertEquals(0, harness.metaFailedFrames)
            assertEquals(0, harness.metaDroppedFrames)
            val snap = harness.session.owner.terminalSnapshotRef()
            assertEquals(0, snap.queuedWork)
            assertEquals(0, snap.inFlightWork)
            assertEquals(0, snap.bufferedFrames)
            assertEquals(TerminalSettlementPhase.SETTLED, snap.terminalSettlementPhase)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun fourRequested_fourReceived_onePersisted_isNeverFusionReady() {
        val harness = onePersistedThreePendingHarness()
        try {
            harness.fireAcquisitionDeadline()
            harness.flushHandler()
            // The exact S24 evidence shape: 4/4 received, 1 persisted, 3 pending.
            val snap = harness.session.accounting.snapshot()
            assertEquals(4, snap.receivedFrames)
            assertEquals(1, snap.persistedFrames)
            assertEquals(3, snap.bufferedFrames)
            // Fusion-ready == processing handoff published: it must be absent.
            assertNull(harness.session.terminalRequestHandoff.request())
            assertEquals(0, harness.onCaptureCompleteCount.get())
            harness.encoder.release()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
        } finally {
            harness.shutdown()
        }
    }
}
