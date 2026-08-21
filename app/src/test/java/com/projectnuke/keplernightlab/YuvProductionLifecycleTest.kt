package com.projectnuke.keplernightlab

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        callbackBodyFailure: Boolean = false,
        rejectOwnerEvent: Boolean = false
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
        val callbackLatch = CountDownLatch(1)

        val completeCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val lastError = AtomicReference<String?>(null)
        val capturedDir = AtomicReference<File?>(null)

        val session: YuvCaptureSession = YuvCaptureSession.create(
            dispatch = { event ->
                // rejectOwnerEvent exercises the owner-event emergency settlement
                // paths (deadline/cancellation) on the caller thread.
                if (rejectOwnerEvent) {
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
            postStatus = { true },
            dispatchCallback = CallbackDispatcher { runnable ->
                if (throwCallbackDispatch) throw IllegalStateException("dispatch threw")
                if (rejectCallbackDispatch) return@CallbackDispatcher false
                handler.post { runnable.run() }
                true
            },
            writeJobJson = { status, _, _, _, _, _, _, _, _, _, _ ->
                handler.post {
                    if (status in TERMINAL_JOB_STATUSES) terminalLatch.countDown()
                }
            },
            saveMotionOnce = { _ -> null to null },
            onCaptureComplete = { file ->
                if (callbackBodyFailure) throw IllegalStateException("callback body threw")
                completeCount.incrementAndGet()
                capturedDir.set(file)
                callbackLatch.countDown()
            },
            onCaptureError = { msg, _ ->
                if (callbackBodyFailure) throw IllegalStateException("callback body threw")
                errorCount.incrementAndGet()
                lastError.set(msg)
                callbackLatch.countDown()
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

        fun awaitCallback() {
            assertTrue("terminal callback not reached", callbackLatch.await(10, TimeUnit.SECONDS))
            flushHandler()
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
        val first = coord.perform()
        assertTrue(first.isTerminal)
        assertEquals(CoordinatorLifecyclePhase.CLOSED, first.phase)
        assertEquals(1, first.performCount)
        assertEquals(1, coord.performCount())
        assertEquals(
            setOf("Background.handler", "BackgroundThread.quit"),
            coord.releasedResourceTags().filter { it.startsWith("Background") }.toSet()
        )
        // Second perform is a no-op: exactly-once.
        val second = coord.perform()
        assertEquals(CoordinatorLifecyclePhase.CLOSED, second.phase)
        assertEquals(1, second.performCount)
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
            harness.awaitCallback()
            assertEquals(1, harness.completeCount.get())
            assertEquals(0, harness.errorCount.get())
            assertEquals(1, harness.coordinator.performCount())
            assertTrue(harness.coordinator.releasedResourceTags().isNotEmpty())
            // Idempotent: a repeated perform never re-runs release.
            val repeated = harness.coordinator.perform()
            assertEquals(CoordinatorLifecyclePhase.CLOSED, repeated.phase)
            assertEquals(1, repeated.performCount)
            assertEquals(1, harness.coordinator.performCount())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun terminalSuccessPopulatesAllOperationOutcomes() {
        val harness = Harness(frameCount = 3)
        try {
            harness.feedAll()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
            harness.awaitCallback()
            val snap = harness.session.owner.terminalSnapshotRef()
            assertTrue(snap.metadataWriteOutcome is TerminalOperationOutcome.Succeeded)
            assertTrue(snap.motionSaveOutcome is TerminalOperationOutcome.Succeeded)
            assertTrue(snap.statusDispatchOutcome is TerminalOperationOutcome.Succeeded)
            assertTrue(snap.callbackDispatchOutcome is TerminalOperationOutcome.Succeeded)
            assertTrue(snap.callbackExecutionOutcome is TerminalOperationOutcome.Succeeded)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun terminalFailurePopulatesExactOperationOutcomes() {
        val harness = Harness(frameCount = 3)
        try {
            harness.session.owner.onCaptureFailed(RuntimeException("terminal failure"), "failure")
            assertEquals(CaptureTerminalStatus.FAILED, harness.awaitTerminal())
            harness.awaitCallback()
            val snap = harness.session.owner.terminalSnapshotRef()
            assertTrue("terminal metadata write must be requested and succeed on failure",
                snap.metadataWriteOutcome is TerminalOperationOutcome.Succeeded)
            assertTrue("motion save not requested on terminal failure",
                snap.motionSaveOutcome is TerminalOperationOutcome.NotRequested)
            assertTrue("status dispatch must succeed even on failure",
                snap.statusDispatchOutcome is TerminalOperationOutcome.Succeeded)
            assertTrue(snap.callbackDispatchOutcome is TerminalOperationOutcome.Succeeded)
            assertTrue(snap.callbackExecutionOutcome is TerminalOperationOutcome.Succeeded)
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
            assertEquals(CoordinatorLifecyclePhase.CLOSED, harness.coordinator.snapshot().phase)
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
            harness.awaitCallback()
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

    // ------------------------------------------------------------------
    // Phase 0.9: deterministic YuvProductionResourceCoordinator unit tests.
    // Every synchronization is CountDownLatch based; no sleeps/polling.
    // ------------------------------------------------------------------

    private fun newCoordinator(): Pair<YuvProductionResourceCoordinator, android.os.HandlerThread> {
        val ht = android.os.HandlerThread("coord-test").apply { start() }
        return Pair(YuvProductionResourceCoordinator(null, android.os.Handler(ht.looper), ht), ht)
    }

    private fun newReader(): ImageReader =
        ImageReader.newInstance(64, 64, ImageFormat.YUV_420_888, 2)

    @Test
    fun attachImageReaderBeforePerformIsReleasedByPerform() {
        val (coord, ht) = newCoordinator()
        try {
            val reader = newReader()
            coord.attachImageReader(reader)
            val snap = coord.perform()
            assertTrue(snap.isTerminal)
            assertEquals(CoordinatorLifecyclePhase.CLOSED, snap.phase)
            assertTrue(
                snap.records.any { it.resourceType == "ImageReader" && it.action == "close" && it.succeeded }
            )
            assertTrue(coord.releasedResourceTags().contains("ImageReader.close"))
        } finally {
            ht.quitSafely()
        }
    }

    @Test
    fun performWinsBeforeLateImageReaderAttachmentSettlesImmediately() {
        val (coord, ht) = newCoordinator()
        try {
            assertTrue(coord.perform().isTerminal)
            val reader = newReader()
            coord.attachImageReader(reader)
            val snap = coord.snapshot()
            assertEquals(1, snap.lateAttachmentCount)
            assertTrue(snap.records.any { it.resourceType == "ImageReader" && it.lateAttachment && it.succeeded })
            // The attachment was NOT retained: a repeated perform re-releases nothing.
            val second = coord.perform()
            assertEquals(CoordinatorLifecyclePhase.CLOSED, second.phase)
            assertEquals(1, second.performCount)
        } finally {
            ht.quitSafely()
        }
    }

    @Test
    fun lateAttachmentReleaseThrowsAndIsRecordedAsFailure() {
        val (coord, ht) = newCoordinator()
        try {
            coord.releaseInterceptor = { type, action, _ ->
                if (type == "ImageReader" && action == "close") {
                    throw IllegalStateException("injected late release failure")
                }
            }
            coord.perform()
            coord.attachImageReader(newReader())
            val snap = coord.snapshot()
            assertEquals(1, snap.lateAttachmentSettlementFailures)
            assertTrue(
                snap.records.any {
                    it.resourceType == "ImageReader" && it.lateAttachment &&
                        !it.succeeded && it.failure is IllegalStateException
                }
            )
        } finally {
            ht.quitSafely()
        }
    }

    @Test
    fun concurrentPerformCallsRunExactlyOnce() {
        val (coord, ht) = newCoordinator()
        try {
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val results = java.util.Collections.synchronizedList(mutableListOf<Int>())
            repeat(2) {
                Thread {
                    try {
                        start.await()
                        results.add(coord.perform().performCount)
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 1), results.sorted())
            assertEquals(1, coord.performCount())
            assertTrue(coord.snapshot().isTerminal)
        } finally {
            ht.quitSafely()
        }
    }

    @Test
    fun snapshotDuringCleaningShowsPendingPhase() {
        val (coord, ht) = newCoordinator()
        try {
            val enteredRelease = CountDownLatch(1)
            val continueRelease = CountDownLatch(1)
            coord.releaseInterceptor = { type, action, real ->
                if (type == "ImageReader" && action == "close") {
                    enteredRelease.countDown()
                    assertTrue(continueRelease.await(5, TimeUnit.SECONDS))
                    real()
                } else {
                    real()
                }
            }
            coord.attachImageReader(newReader())
            val performed = AtomicReference<ProductionCleanupSnapshot>()
            val worker = Thread {
                performed.set(coord.perform())
            }
            worker.start()
            assertTrue(enteredRelease.await(5, TimeUnit.SECONDS))
            val mid = coord.snapshot()
            assertEquals(CoordinatorLifecyclePhase.CLEANING, mid.phase)
            assertFalse(mid.isTerminal)
            continueRelease.countDown()
            worker.join(5_000)
            assertTrue(performed.get().isTerminal)
        } finally {
            ht.quitSafely()
        }
    }

    @Test
    fun snapshotAfterClosedContainsCompleteResults() {
        val (coord, ht) = newCoordinator()
        try {
            coord.attachImageReader(newReader())
            coord.perform()
            val snap = coord.snapshot()
            assertEquals(CoordinatorLifecyclePhase.CLOSED, snap.phase)
            assertTrue(snap.isTerminal)
            assertEquals(1, snap.performCount)
            assertTrue(snap.releaseAttempts > 0)
            assertTrue(snap.records.isNotEmpty())
        } finally {
            ht.quitSafely()
        }
    }

    @Test
    fun lateSettlementUpdatesClosedSnapshot() {
        val (coord, ht) = newCoordinator()
        try {
            coord.perform()
            val before = coord.snapshot()
            assertEquals(0, before.lateAttachmentCount)
            assertFalse(before.records.any { it.lateAttachment })
            coord.attachImageReader(newReader())
            val after = coord.snapshot()
            assertEquals(1, after.lateAttachmentCount)
            assertTrue(after.records.any { it.resourceType == "ImageReader" && it.lateAttachment })
        } finally {
            ht.quitSafely()
        }
    }

    // ------------------------------------------------------------------
    // Phase 1: terminal publication semantics
    // ------------------------------------------------------------------

    @Test
    fun terminalRequestIsSolelyPublishedExactlyOnce() {
        val harness = Harness(frameCount = 3)
        try {
            harness.feedAll()
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
            val handoff = harness.session.terminalRequestHandoff
            val published = handoff.request()
            assertNotNull("terminal request must be published", published)
            val publishedRequest = published!!
            assertEquals(CaptureTerminalStatus.SUCCESS, publishedRequest.status)
            // Duplicate/late publications are rejected and never replace the winner.
            assertFalse(handoff.publish(publishedRequest))
            assertFalse(handoff.publish(publishedRequest.copy(status = CaptureTerminalStatus.CANCELLED)))
            assertEquals(CaptureTerminalStatus.SUCCESS, handoff.request()?.status)
            // The published request is the terminal authority: no counters/elapsed
            // time inference anywhere in the snapshot path.
            assertEquals(publishedRequest, handoff.request())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun gateReturnsPublishedRequestImmediately() {
        val handoff = YuvTerminalRequestHandoff()
        val request = YuvTerminalRequest(
            status = CaptureTerminalStatus.SUCCESS,
            jobStatus = "CAPTURE_COMPLETE",
            reason = "All 3 frames persisted",
            completionKind = TerminalCompletionKind.SUCCESS,
            cause = null,
            saveMotion = true
        )
        assertTrue(handoff.publish(request))
        assertEquals(request, handoff.awaitPublishedOrClosed())
        assertFalse(handoff.isClosed())
    }

    @Test
    fun gateUnblocksOnCloseWithoutSynthesizingRequest() {
        val handoff = YuvTerminalRequestHandoff()
        assertTrue(handoff.close())
        assertNull("closure must never synthesize a request", handoff.awaitPublishedOrClosed())
        assertTrue(handoff.isClosed())
        // Publish after close is rejected.
        val request = YuvTerminalRequest(
            status = CaptureTerminalStatus.TIMED_OUT,
            jobStatus = "CAPTURE_TIMEOUT",
            reason = "timeout",
            completionKind = TerminalCompletionKind.ERROR,
            cause = null,
            saveMotion = false
        )
        assertFalse(handoff.publish(request))
        assertNull(handoff.request())
    }

    @Test
    fun sessionCloseUnblocksGateDeterministically() {
        val harness = Harness(frameCount = 3)
        try {
            // Close before any terminal publication: the gate must unblock with null.
            harness.session.close()
            val waited = harness.session.terminalRequestHandoff.awaitPublishedOrClosed()
            assertNull(waited)
            assertTrue(harness.session.terminalRequestHandoff.isClosed())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun concurrentPublicationsExactlyOneWins() {
        val handoff = YuvTerminalRequestHandoff()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val winners = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val request = YuvTerminalRequest(
            status = CaptureTerminalStatus.SUCCESS,
            jobStatus = "CAPTURE_COMPLETE",
            reason = "all persisted",
            completionKind = TerminalCompletionKind.SUCCESS,
            cause = null,
            saveMotion = true
        )
        repeat(2) {
            Thread {
                try {
                    start.await()
                    winners.add(handoff.publish(request))
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, winners.count { it })
        assertNotNull(handoff.request())
    }

    @Test
    fun emergencyCancellationSettlementPublishesRequestThroughSameTransaction() {
        val harness = Harness(frameCount = 3, rejectOwnerEvent = true)
        try {
            harness.session.owner.onCancellationRequested()
            val published = harness.session.terminalRequestHandoff.awaitPublishedOrClosed(5)
            assertNotNull("emergency settlement must publish the request", published)
            val publishedRequest = published!!
            assertEquals(CaptureTerminalStatus.CANCELLED, publishedRequest.status)
            assertEquals(TerminalCompletionKind.ERROR, publishedRequest.completionKind)
            assertEquals(CaptureTerminalStatus.CANCELLED, harness.session.terminalState.status())
            assertEquals(TerminalSettlementPhase.SETTLED, harness.session.owner.terminalSettlementPhase())
            assertEquals(1, harness.coordinator.performCount())
            // Emergency settlement still attempts every session-safe operation; only
            // motion itself is not requested for cancellation.
            harness.awaitCallback()
            val snap = harness.session.owner.terminalSnapshotRef()
            assertTrue(snap.metadataWriteOutcome is TerminalOperationOutcome.Succeeded)
            assertTrue(snap.motionSaveOutcome is TerminalOperationOutcome.NotRequested)
            assertTrue(snap.statusDispatchOutcome is TerminalOperationOutcome.Succeeded)
            assertTrue(snap.callbackDispatchOutcome is TerminalOperationOutcome.Succeeded)
            assertTrue(snap.callbackExecutionOutcome is TerminalOperationOutcome.Succeeded)
            assertEquals(1, harness.errorCount.get())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun emergencyDeadlineSettlementPublishesTimedOutRequest() {
        val harness = Harness(frameCount = 3, rejectOwnerEvent = true)
        try {
            harness.session.owner.onDeadlineReached()
            val published = harness.session.terminalRequestHandoff.awaitPublishedOrClosed(5)
            assertNotNull("emergency deadline settlement must publish the request", published)
            assertEquals(CaptureTerminalStatus.TIMED_OUT, published!!.status)
            assertEquals(CaptureTerminalStatus.TIMED_OUT, harness.session.terminalState.status())
            assertEquals(TerminalSettlementPhase.SETTLED, harness.session.owner.terminalSettlementPhase())
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
