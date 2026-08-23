package com.projectnuke.keplernightlab

import android.media.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Phase-A corrective audit: the durable handoff invariant applies to ALL accepted
 * persistence work.  The camera-acquisition deadline must ask FIRST whether
 * accepted persistence work remains — regardless of whether acquisition ended
 * FULL or PARTIAL — and enter/continue DRAINING instead of publishing.  Terminal
 * classification (SUCCESS/PARTIAL_SUCCESS/FAILED/TIMED_OUT) may decide only after
 * every accepted task settles, and SUCCESS must still satisfy the strict terminal
 * predicate (persisted == requested, manifest == requested, buffered/reserved/
 * pending/queued/in-flight all zero).  Emergency/event-dispatch settlement fails
 * closed: it never publishes SUCCESS or PARTIAL_SUCCESS while accepted work may
 * remain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21])
class YuvCaptureOwnerAcceptedWorkTest {

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

    /** Call N (1-based) blocks when blockFromCall <= N until [release]. */
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

    private class AcceptedWorkHarness(
        val frameCount: Int = 4,
        workerCapacity: Int = 4
    ) {
        @Volatile var rejectOwnerEvents: Boolean = false

        val dir: File = Files.createTempDirectory("yuv-accepted-work-test").toFile()
        val handlerThread = android.os.HandlerThread("yuv-accepted-work-test").apply { start() }
        val handler: android.os.Handler = android.os.Handler(handlerThread.looper)
        val encoder = ControlledYuvEncoder()

        val onCaptureCompleteCount = AtomicInteger(0)
        val onCaptureErrorCount = AtomicInteger(0)
        val errorMessage = AtomicReference<String?>(null)
        val postedStatuses = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val completeCallbackLatch = CountDownLatch(1)
        private val errorCallbackLatch = CountDownLatch(1)

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

        private val terminalLatch = CountDownLatch(1)

        val session: YuvCaptureSession = YuvCaptureSession.create(
            dispatch = { event ->
                if (rejectOwnerEvents) {
                    event.disposeWithoutMutation()
                    false
                } else {
                    handler.post { event.execute() }
                    true
                }
            },
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
            postStatus = { status ->
                postedStatuses.add(status)
                true
            },
            dispatchCallback = CallbackDispatcher { runnable ->
                if (!handler.post(runnable)) runnable.run()
                true
            },
            writeJobJson = { status, saved, _, _, persisted, _, _,
                             _, _, _, _, _, _, _, queued, inFlight, buffered, reserved ->
                handler.post {
                    metaStatus = status
                    metaQueuedWork = queued
                    metaInFlightWork = inFlight
                    metaBufferedFrames = buffered
                    metaReservedCount = reserved
                    metaPersistedFrames = persisted
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
            schedulePersistenceDrainDeadline = null
        )

        /** Sends exactly [count] cleanly-acquired buffered frames (count <= frameCount). */
        fun sendFrames(count: Int) {
            repeat(count) { index ->
                session.owner.acceptBuffered(FakeBufferedAccess((index + 1) * 1000L))
            }
        }

        fun fireAcquisitionDeadline() {
            session.owner.onDeadlineReached()
            flushHandler()
        }

        /**
         * Fires the BOUNDED drain deadline (not the acquisition deadline).
         */
        fun fireBoundedDrainDeadline() {
            session.owner.onPersistenceDrainDeadlineReached()
            flushHandler()
        }

        /**
         * Owner-level adoption injection: commits a manifest entry directly through
         * the accounting ownership seam (no worker involvement).  Used to construct
         * the persisted==requested-with-outstanding-work shape that real flows cannot.
         */
        fun manuallyPersistFrame(frameIndex: Int): Boolean {
            val entry = YuvFrameManifestEntry(
                frameIndex,
                "frame_${frameIndex.toString().padStart(2, '0')}_color.png",
                frameIndex * 1000L + 7L,
                true
            )
            val token = session.accounting.tryReserveAdoption(entry) ?: return false
            return token.commit()
        }

        /** Submits a blocking no-op that occupies the bounded worker until released. */
        fun submitBlockingWorkerTask(): CountDownLatch {
            val release = CountDownLatch(1)
            val submitted = session.boundedWorker.submit(Runnable {
                release.await(30, TimeUnit.SECONDS)
            })
            check(submitted) { "blocking worker task submission rejected" }
            // Wait until the worker thread is visibly active.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (session.boundedWorker.activeCount() == 0 && System.nanoTime() < deadline) {
                Thread.yield()
            }
            return release
        }

        fun awaitTerminal(timeoutSec: Long = 10): CaptureTerminalStatus {
            assertTrue("terminal not reached", terminalLatch.await(timeoutSec, TimeUnit.SECONDS))
            flushHandler()
            return session.terminalState.status()
        }

        fun awaitErrorCallback(timeoutSec: Long = 10): String? {
            assertTrue("error callback not fired", errorCallbackLatch.await(timeoutSec, TimeUnit.SECONDS))
            flushHandler()
            return errorMessage.get()
        }

        fun awaitCompleteCallback(timeoutSec: Long = 10) {
            assertTrue("complete callback not fired", completeCallbackLatch.await(timeoutSec, TimeUnit.SECONDS))
            flushHandler()
        }

        fun awaitPublishedRequest(timeoutSec: Long = 10): YuvTerminalRequest? =
            session.terminalRequestHandoff.awaitPublishedOrClosed(timeoutSec)

        fun statusesContaining(fragment: String): List<String> = synchronized(postedStatuses) {
            postedStatuses.filter { it.contains(fragment) }
        }

        fun flushHandler() {
            val latch = CountDownLatch(1)
            assertTrue(handler.post { latch.countDown() })
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

    /** requested=4, delivered=3, persisted=1, one encoding blocked, two buffered. */
    private fun partialAcquisitionHarness(): AcceptedWorkHarness {
        val harness = AcceptedWorkHarness(frameCount = 4, workerCapacity = 4)
        harness.encoder.blockFrom(2)
        harness.sendFrames(3)
        harness.encoder.awaitBlockedCall()
        harness.flushHandler()
        return harness
    }

    @Test
    fun partialAcquisition_withAcceptedPersistence_doesNotPublishPartial() {
        val harness = partialAcquisitionHarness()
        try {
            assertEquals(1, harness.session.accounting.snapshot().persistedFrames)
            assertEquals(3, harness.session.accounting.snapshot().receivedFrames)
            harness.fireAcquisitionDeadline()
            // A genuine camera partial (3/4 received) with two already-accepted
            // persistence operations outstanding must NOT publish CAPTURE_PARTIAL.
            assertEquals(CaptureTerminalStatus.ACTIVE, harness.session.terminalState.status())
            assertNull(harness.session.terminalRequestHandoff.request())
            assertEquals(0, harness.onCaptureCompleteCount.get())
            assertEquals(0, harness.onCaptureErrorCount.get())
            assertTrue(
                "unexpected terminal metadata write: ${harness.metaStatus}",
                harness.metaStatus == null || harness.metaStatus == "CAPTURING"
            )
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun partialAcquisition_withAcceptedPersistence_entersDraining() {
        val harness = partialAcquisitionHarness()
        try {
            harness.fireAcquisitionDeadline()
            // The owner entered the DRAINING phase: the draining status dispatch is
            // the observable marker, and no terminal exists yet.
            assertTrue(
                "draining status not observed: ${harness.postedStatuses}",
                harness.statusesContaining("deadline reached: storing").isNotEmpty()
            )
            assertEquals(CaptureTerminalStatus.ACTIVE, harness.session.terminalState.status())
            assertNull(harness.session.terminalRequestHandoff.request())
            // Re-firing the acquisition deadline keeps draining (idempotent phase).
            harness.fireAcquisitionDeadline()
            assertEquals(CaptureTerminalStatus.ACTIVE, harness.session.terminalState.status())
            assertNull(harness.session.terminalRequestHandoff.request())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun partialAcquisition_drainsAllAcceptedFrames_thenPublishesPartial() {
        val harness = partialAcquisitionHarness()
        try {
            harness.fireAcquisitionDeadline()
            // Every accepted frame settles by truth...
            harness.encoder.release()
            // ...and only then may the genuine partial capture be published.
            assertEquals(CaptureTerminalStatus.PARTIAL_SUCCESS, harness.awaitTerminal())
            harness.awaitCompleteCallback()
            assertEquals(1, harness.onCaptureCompleteCount.get())
            assertEquals(0, harness.onCaptureErrorCount.get())
            val snap = harness.session.accounting.snapshot()
            assertEquals(3, snap.persistedFrames)
            assertEquals(3, snap.manifest.size)
            assertEquals("CAPTURE_PARTIAL", harness.metaStatus)
            assertEquals(3, harness.metaPersistedFrames)
            assertNotNull(harness.session.terminalRequestHandoff.request())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun partialAcquisition_drainFailure_fails() {
        val harness = partialAcquisitionHarness()
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
    fun partialAcquisition_drainTimeout_timesOut() {
        val harness = partialAcquisitionHarness()
        try {
            harness.fireAcquisitionDeadline()
            // Bounded persistence-drain deadline expires with accepted work still
            // outstanding: an actual persistence timeout, never a camera partial.
            harness.fireBoundedDrainDeadline()
            assertEquals(CaptureTerminalStatus.TIMED_OUT, harness.awaitTerminal())
            assertEquals(0, harness.onCaptureCompleteCount.get())
            val message = harness.awaitErrorCallback() ?: ""
            assertEquals(1, harness.onCaptureErrorCount.get())
            assertTrue("reason=$message", message.contains("persistence drain timeout"))
            assertTrue("reason=$message", message.contains("saved=1/4"))
            // Late worker completion can NEVER retroactively succeed or partially publish.
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
    fun partialHandoff_hasZeroQueuedInflightBufferedReserved() {
        val harness = partialAcquisitionHarness()
        try {
            harness.fireAcquisitionDeadline()
            harness.encoder.release()
            assertEquals(CaptureTerminalStatus.PARTIAL_SUCCESS, harness.awaitTerminal())
            harness.awaitCompleteCallback()
            harness.flushHandler()
            // The durable partial handoff must snapshot zero accepted work.
            assertEquals("CAPTURE_PARTIAL", harness.metaStatus)
            assertEquals(0, harness.metaQueuedWork)
            assertEquals(0, harness.metaInFlightWork)
            assertEquals(0, harness.metaBufferedFrames)
            assertEquals(0, harness.metaReservedCount)
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
    fun fullPersistedCount_withOutstandingOwnerWork_doesNotBypassStrictGate() {
        val harness = AcceptedWorkHarness(frameCount = 2, workerCapacity = 2)
        try {
            // Occupies the single worker thread: an accepted owner-visible task is
            // genuinely in flight while the persisted count already equals requested.
            val blockerRelease = harness.submitBlockingWorkerTask()
            assertTrue(harness.manuallyPersistFrame(0))
            assertTrue(harness.manuallyPersistFrame(1))
            val snap = harness.session.accounting.snapshot()
            assertEquals(2, snap.persistedFrames)
            assertEquals(2, snap.manifest.size)
            assertEquals(1, harness.session.boundedWorker.activeCount())
            // Legacy behavior would publish SUCCESS here purely from persisted==N.
            harness.fireAcquisitionDeadline()
            assertEquals(CaptureTerminalStatus.ACTIVE, harness.session.terminalState.status())
            assertNull(harness.session.terminalRequestHandoff.request())
            assertTrue(
                "draining status not observed: ${harness.postedStatuses}",
                harness.statusesContaining("deadline reached: storing").isNotEmpty()
            )
            // Once the accepted work settles, deadline re-evaluation settles by
            // truth through the STRICT gate (never by the persisted count alone).
            blockerRelease.countDown()
            var attempts = 0
            while (harness.session.terminalState.status() == CaptureTerminalStatus.ACTIVE && attempts < 200) {
                harness.fireAcquisitionDeadline()
                attempts++
            }
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
            harness.awaitCompleteCallback()
            assertEquals(2, harness.metaPersistedFrames)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun emergencyDeadline_neverPublishesSuccessWithAcceptedWorkOutstanding() {
        val harness = partialAcquisitionHarness()
        try {
            // Owner events are now rejected: the deadline falls to the emergency path.
            harness.rejectOwnerEvents = true
            harness.session.owner.onDeadlineReached()
            val published = harness.awaitPublishedRequest()
            assertNotNull("emergency settlement must publish a request", published)
            val request = published!!
            // FAIL-CLOSED: accepted persistence remains (pending=2, in-flight=1,
            // buffered=2), so neither SUCCESS nor PARTIAL_SUCCESS may be claimed.
            assertEquals(CaptureTerminalStatus.TIMED_OUT, request.status)
            assertEquals(TerminalCompletionKind.ERROR, request.completionKind)
            assertTrue(
                "reason=${request.reason}",
                request.reason?.contains("persistence drain timeout") == true
            )
            assertFalse(request.saveMotion)
            assertEquals(CaptureTerminalStatus.TIMED_OUT, harness.session.terminalState.status())
            assertEquals(0, harness.onCaptureCompleteCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun emergencyDeadline_quiescentFullPersist_stillClassifiesByTruth() {
        val harness = AcceptedWorkHarness(frameCount = 2, workerCapacity = 2)
        try {
            assertTrue(harness.manuallyPersistFrame(0))
            assertTrue(harness.manuallyPersistFrame(1))
            // Quiescent: zero buffered/reserved/pending/queued/in-flight work.
            harness.rejectOwnerEvents = true
            harness.session.owner.onDeadlineReached()
            val published = harness.awaitPublishedRequest()
            assertNotNull(published)
            assertEquals(CaptureTerminalStatus.SUCCESS, published!!.status)
            assertEquals(TerminalCompletionKind.SUCCESS, published.completionKind)
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.session.terminalState.status())
        } finally {
            harness.shutdown()
        }
    }
}
