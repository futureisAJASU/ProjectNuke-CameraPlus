package com.projectnuke.keplernightlab

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraOfflineSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RawProductionResourceCoordinatorTest {
    @Test
    fun performPublishesClosedPhaseAndWorkerCleanupReportExactlyOnce() {
        val thread = HandlerThread("raw-coordinator-test").apply { start() }
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val worker = BoundedCaptureWorker("raw-coordinator-worker", 1)
        val coordinator = RawProductionResourceCoordinator(scheduler, worker, thread)
        try {
            assertEquals(RawCoordinatorPhase.OPEN, coordinator.snapshot().phase)
            assertEquals(RawAttachmentDisposition.NO_RESOURCE, coordinator.attachImageReader(null))
            val first = coordinator.perform()
            assertEquals(RawCoordinatorPhase.CLOSED, first.phase)
            assertNotNull(first.workerCleanupReport)
            val second = coordinator.perform()
            assertEquals(first.records, second.records)
            assertEquals(1, second.records.count { it.tag == "TimeoutScheduler" })
            assertEquals(1, second.records.count { it.tag == "HandlerThread" })
        } finally {
            coordinator.perform()
        }
    }

    @Test
    fun concurrentPerformHasOneCleanupClaimAndNoDuplicateInfrastructureRelease() {
        val thread = HandlerThread("raw-coordinator-race").apply { start() }
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val worker = BoundedCaptureWorker("raw-coordinator-race-worker", 1)
        val coordinator = RawProductionResourceCoordinator(scheduler, worker, thread)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>(null)
        try {
            repeat(2) {
                Thread {
                    try {
                        assertTrue(start.await(2, TimeUnit.SECONDS))
                        coordinator.perform()
                    } catch (t: Throwable) {
                        failure.set(t)
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            val snapshot = coordinator.snapshot()
            assertEquals(RawCoordinatorPhase.CLOSED, snapshot.phase)
            assertEquals(1, snapshot.records.count { it.tag == "TimeoutScheduler" })
            assertEquals(1, snapshot.records.count { it.tag == "HandlerThread" })
        } finally {
            coordinator.perform()
        }
    }

    @Test
    fun sameImageReaderDuplicateIsAlreadyOwnedAndNotClosed() {
        val thread = HandlerThread("raw-reader-duplicate").apply { start() }
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val worker = BoundedCaptureWorker("raw-reader-worker", 1)
        val coordinator = RawProductionResourceCoordinator(scheduler, worker, thread)
        val reader = ImageReader.newInstance(1, 1, ImageFormat.YUV_420_888, 2)
        try {
            assertEquals(RawAttachmentDisposition.ACCEPTED, coordinator.attachImageReader(reader))
            assertEquals(RawAttachmentDisposition.ALREADY_OWNED, coordinator.attachImageReader(reader))
            assertEquals(1, coordinator.snapshot().duplicateAttachments)
        } finally {
            coordinator.perform()
        }
    }

    @Test
    fun differentImageReaderDuplicateIsSettledAndOriginalRemainsOwned() {
        val thread = HandlerThread("raw-reader-different").apply { start() }
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val worker = BoundedCaptureWorker("raw-reader-worker-2", 1)
        val coordinator = RawProductionResourceCoordinator(scheduler, worker, thread)
        val original = ImageReader.newInstance(1, 1, ImageFormat.YUV_420_888, 2)
        val duplicate = ImageReader.newInstance(1, 1, ImageFormat.YUV_420_888, 2)
        try {
            assertEquals(RawAttachmentDisposition.ACCEPTED, coordinator.attachImageReader(original))
            assertEquals(RawAttachmentDisposition.SETTLED_DUPLICATE, coordinator.attachImageReader(duplicate))
            assertEquals(1, coordinator.snapshot().duplicateAttachments)
            assertEquals(1, coordinator.snapshot().records.count { it.tag == "ImageReader" })
        } finally {
            coordinator.perform()
        }
    }

    @Test
    fun lateImageReaderAttachmentIsSettledExactlyOnce() {
        val thread = HandlerThread("raw-reader-late").apply { start() }
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val worker = BoundedCaptureWorker("raw-reader-worker-3", 1)
        val coordinator = RawProductionResourceCoordinator(scheduler, worker, thread)
        try {
            assertEquals(RawCoordinatorPhase.CLOSED, coordinator.perform().phase)
            val late = ImageReader.newInstance(1, 1, ImageFormat.YUV_420_888, 2)
            assertEquals(RawAttachmentDisposition.SETTLED_LATE, coordinator.attachImageReader(late))
            assertEquals(1, coordinator.snapshot().lateAttachments)
            assertEquals(1, coordinator.snapshot().records.count { it.tag == "ImageReader" })
        } finally {
            coordinator.perform()
        }
    }

    // ------------------------------------------------------------------
    // Atomic attachment: concurrent racing attachments on one slot.
    // Every synchronization is CountDownLatch based; no sleeps/polling.
    // ------------------------------------------------------------------

    private class FakeAttachableResource(val id: Int)

    private fun newCoordinator(name: String): RawProductionResourceCoordinator {
        val thread = HandlerThread(name).apply { start() }
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val worker = BoundedCaptureWorker("$name-worker", 1)
        return RawProductionResourceCoordinator(scheduler, worker, thread)
    }

    @Test
    fun concurrentDifferentImageReadersExactlyOneAcceptedAndOneDuplicate() {
        val coordinator = newCoordinator("raw-race-reader")
        val first = ImageReader.newInstance(1, 1, ImageFormat.YUV_420_888, 2)
        val second = ImageReader.newInstance(1, 1, ImageFormat.YUV_420_888, 2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = Collections.synchronizedList(mutableListOf<Pair<ImageReader, RawAttachmentDisposition>>())
        val failure = AtomicReference<Throwable?>(null)
        try {
            repeat(2) { i ->
                val reader = if (i == 0) first else second
                Thread {
                    try {
                        assertTrue(start.await(2, TimeUnit.SECONDS))
                        results.add(reader to coordinator.attachImageReader(reader))
                    } catch (t: Throwable) {
                        failure.set(t)
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            assertEquals(
                1,
                results.count { it.second == RawAttachmentDisposition.ACCEPTED }
            )
            assertEquals(
                1,
                results.count { it.second == RawAttachmentDisposition.SETTLED_DUPLICATE }
            )
            val accepted = results.first { it.second == RawAttachmentDisposition.ACCEPTED }.first
            val duplicate = results.first { it.second == RawAttachmentDisposition.SETTLED_DUPLICATE }.first
            assertNotEquals(accepted, duplicate)
            // The accepted resource is NOT settled; the duplicate is settled exactly once.
            val before = coordinator.snapshot()
            assertEquals(1, before.records.count { it.tag == "ImageReader" })
            assertEquals(1, before.duplicateAttachments)
            assertEquals(0, before.lateAttachments)
            // perform() later settles the retained accepted resource exactly once.
            val after = coordinator.perform()
            assertEquals(RawCoordinatorPhase.CLOSED, after.phase)
            assertEquals(2, after.records.count { it.tag == "ImageReader" && it.succeeded })
        } finally {
            coordinator.perform()
        }
    }

    @Test
    fun concurrentSameImageReaderExactlyOneAcceptedAndOneAlreadyOwned() {
        val coordinator = newCoordinator("raw-race-same")
        val reader = ImageReader.newInstance(1, 1, ImageFormat.YUV_420_888, 2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = Collections.synchronizedList(mutableListOf<RawAttachmentDisposition>())
        val failure = AtomicReference<Throwable?>(null)
        try {
            repeat(2) {
                Thread {
                    try {
                        assertTrue(start.await(2, TimeUnit.SECONDS))
                        results.add(coordinator.attachImageReader(reader))
                    } catch (t: Throwable) {
                        failure.set(t)
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            assertEquals(1, results.count { it == RawAttachmentDisposition.ACCEPTED })
            assertEquals(1, results.count { it == RawAttachmentDisposition.ALREADY_OWNED })
            // Not prematurely closed: no settlement before perform().
            val before = coordinator.snapshot()
            assertEquals(0, before.records.count { it.tag == "ImageReader" })
            assertEquals(1, before.duplicateAttachments)
            // perform() closes the single owned instance exactly once.
            val after = coordinator.perform()
            assertEquals(1, after.records.count { it.tag == "ImageReader" && it.succeeded })
        } finally {
            coordinator.perform()
        }
    }

    @Test
    fun concurrentGenericSlotAttachmentsExactlyOneAcceptedAndOneDuplicate() {
        val coordinator = newCoordinator("raw-race-slot")
        val first = FakeAttachableResource(1)
        val second = FakeAttachableResource(2)
        val slot = AtomicReference<FakeAttachableResource?>(null)
        val settlements = AtomicInteger(0)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = Collections.synchronizedList(
            mutableListOf<Pair<FakeAttachableResource, RawAttachmentDisposition>>()
        )
        val failure = AtomicReference<Throwable?>(null)
        try {
            repeat(2) { i ->
                val resource = if (i == 0) first else second
                Thread {
                    try {
                        assertTrue(start.await(2, TimeUnit.SECONDS))
                        results.add(
                            resource to coordinator.attach(
                                value = resource,
                                tag = "FakeSlot",
                                current = { slot.get() },
                                store = { slot.set(it) },
                                settle = { settlements.incrementAndGet() }
                            )
                        )
                    } catch (t: Throwable) {
                        failure.set(t)
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            val accepted = results.filter { it.second == RawAttachmentDisposition.ACCEPTED }
            val duplicate = results.filter { it.second == RawAttachmentDisposition.SETTLED_DUPLICATE }
            assertEquals(1, accepted.size)
            assertEquals(1, duplicate.size)
            assertNotEquals(accepted[0].first, duplicate[0].first)
            assertEquals(accepted[0].first, slot.get())
            assertEquals(1, settlements.get())
        } finally {
            coordinator.perform()
        }
    }

    // ------------------------------------------------------------------
    // Attachment vs perform ownership regressions.
    // ------------------------------------------------------------------

    @Test
    fun attachRacingPerformYieldsExactlyOneSettlementOwner() {
        val coordinator = newCoordinator("raw-attach-perform")
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val attachResult = AtomicReference<RawAttachmentDisposition?>(null)
        val failure = AtomicReference<Throwable?>(null)
        try {
            val reader = ImageReader.newInstance(1, 1, ImageFormat.YUV_420_888, 2)
            Thread {
                try {
                    assertTrue(start.await(2, TimeUnit.SECONDS))
                    attachResult.set(coordinator.attachImageReader(reader))
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    done.countDown()
                }
            }.start()
            Thread {
                try {
                    assertTrue(start.await(2, TimeUnit.SECONDS))
                    coordinator.perform()
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    done.countDown()
                }
            }.start()
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            val disposition = attachResult.get()!!
            assertTrue(
                "unexpected disposition $disposition",
                disposition == RawAttachmentDisposition.ACCEPTED ||
                    disposition == RawAttachmentDisposition.SETTLED_LATE
            )
            val snap = coordinator.snapshot()
            assertEquals(RawCoordinatorPhase.CLOSED, snap.phase)
            // Exactly one settlement owner: never leaked, never double-settled.
            assertEquals(1, snap.records.count { it.tag == "ImageReader" })
            assertTrue(snap.records.filter { it.tag == "ImageReader" }.all { it.succeeded })
            if (disposition == RawAttachmentDisposition.ACCEPTED) {
                assertEquals(0, snap.lateAttachments)
            } else {
                assertEquals(1, snap.lateAttachments)
            }
        } finally {
            coordinator.perform()
        }
    }

    @Test
    fun duplicateDispositionChosenUnderLockIsNotRetroactivelyChangedByPerform() {
        val coordinator = newCoordinator("raw-duplicate-preserved")
        val first = ImageReader.newInstance(1, 1, ImageFormat.YUV_420_888, 2)
        val second = ImageReader.newInstance(1, 1, ImageFormat.YUV_420_888, 2)
        val enteredSettlement = CountDownLatch(1)
        val continueSettlement = CountDownLatch(1)
        val pausedFirst = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val attachResult = AtomicReference<RawAttachmentDisposition?>(null)
        val failure = AtomicReference<Throwable?>(null)
        try {
            assertEquals(RawAttachmentDisposition.ACCEPTED, coordinator.attachImageReader(first))
            coordinator.releaseInterceptor = { tag, real ->
                if (tag == "ImageReader" && pausedFirst.compareAndSet(false, true)) {
                    enteredSettlement.countDown()
                    if (!continueSettlement.await(5, TimeUnit.SECONDS)) {
                        throw IllegalStateException("continueSettlement not released")
                    }
                }
                real()
            }
            val attachThread = Thread {
                try {
                    attachResult.set(coordinator.attachImageReader(second))
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    done.countDown()
                }
            }
            attachThread.start()
            assertTrue(enteredSettlement.await(5, TimeUnit.SECONDS))
            // The duplicate settlement is now paused OUTSIDE the lock.  perform()
            // claims ownership and fully closes the coordinator while it runs.
            assertEquals(RawCoordinatorPhase.CLOSED, coordinator.perform().phase)
            continueSettlement.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            // The disposition was selected under the lock BEFORE the settlement ran:
            // a perform() that completed during the settlement cannot turn the
            // duplicate into a late attachment.
            assertEquals(RawAttachmentDisposition.SETTLED_DUPLICATE, attachResult.get())
            val snap = coordinator.snapshot()
            assertEquals(1, snap.duplicateAttachments)
            assertEquals(0, snap.lateAttachments)
            assertEquals(2, snap.records.count { it.tag == "ImageReader" && it.succeeded })
        } finally {
            continueSettlement.countDown()
            coordinator.perform()
        }
    }

    @Test
    fun lateAttachmentReleaseThrowsKeepsSettledLateAndRecordsFailure() {
        val coordinator = newCoordinator("raw-late-throw")
        try {
            coordinator.perform()
            coordinator.releaseInterceptor = { tag, real ->
                if (tag == "ImageReader") {
                    throw IllegalStateException("injected late release failure")
                }
                real()
            }
            val late = ImageReader.newInstance(1, 1, ImageFormat.YUV_420_888, 2)
            assertEquals(RawAttachmentDisposition.SETTLED_LATE, coordinator.attachImageReader(late))
            val snap = coordinator.snapshot()
            assertEquals(1, snap.lateAttachments)
            assertTrue(
                snap.records.any {
                    it.tag == "ImageReader" && !it.succeeded && it.failure is IllegalStateException
                }
            )
        } finally {
            coordinator.perform()
        }
    }

    // ------------------------------------------------------------------
    // Partial-release and worker cleanup regressions.
    // ------------------------------------------------------------------

    private class FakeCaptureSession(
        private val abortFailure: Throwable? = null
    ) : CameraCaptureSession() {
        var aborted = false
            private set
        var stopped = false
            private set
        var closed = false
            private set

        override fun abortCaptures() {
            aborted = true
            abortFailure?.let { throw it }
        }

        override fun stopRepeating() {
            stopped = true
        }

        override fun close() {
            closed = true
        }

        override fun getDevice(): CameraDevice = throw UnsupportedOperationException()
        override fun getInputSurface(): Surface = throw UnsupportedOperationException()
        override fun isReprocessable(): Boolean = throw UnsupportedOperationException()
        override fun prepare(surface: Surface) {
            throw UnsupportedOperationException()
        }
        override fun finalizeOutputConfigurations(outputConfigurations: MutableList<OutputConfiguration>) {
            throw UnsupportedOperationException()
        }
        override fun capture(
            request: CaptureRequest,
            listener: CaptureCallback?,
            handler: Handler?
        ): Int = throw UnsupportedOperationException()
        override fun captureBurst(
            requests: MutableList<CaptureRequest>,
            listener: CaptureCallback?,
            handler: Handler?
        ): Int = throw UnsupportedOperationException()
        override fun setRepeatingRequest(
            request: CaptureRequest,
            listener: CaptureCallback?,
            handler: Handler?
        ): Int = throw UnsupportedOperationException()
        override fun setRepeatingBurst(
            requests: MutableList<CaptureRequest>,
            listener: CaptureCallback?,
            handler: Handler?
        ): Int = throw UnsupportedOperationException()
        override fun captureBurstRequests(
            requests: MutableList<CaptureRequest>,
            executor: Executor,
            listener: CameraCaptureSession.CaptureCallback
        ): Int = throw UnsupportedOperationException()
        override fun captureSingleRequest(
            request: CaptureRequest,
            executor: Executor,
            listener: CameraCaptureSession.CaptureCallback
        ): Int = throw UnsupportedOperationException()
        override fun setRepeatingBurstRequests(
            requests: MutableList<CaptureRequest>,
            executor: Executor,
            listener: CameraCaptureSession.CaptureCallback
        ): Int = throw UnsupportedOperationException()
        override fun setSingleRepeatingRequest(
            request: CaptureRequest,
            executor: Executor,
            listener: CameraCaptureSession.CaptureCallback
        ): Int = throw UnsupportedOperationException()
        override fun updateOutputConfiguration(config: OutputConfiguration) {
            throw UnsupportedOperationException()
        }
        override fun supportsOfflineProcessing(target: Surface): Boolean =
            throw UnsupportedOperationException()
        override fun switchToOffline(
            targets: Collection<Surface>,
            executor: Executor,
            callback: CameraOfflineSession.CameraOfflineSessionCallback
        ): CameraOfflineSession = throw UnsupportedOperationException()
    }

    @Test
    fun captureSessionAbortFailureStillStopsAndCloses() {
        val coordinator = newCoordinator("raw-session-abort")
        val session = FakeCaptureSession(abortFailure = IllegalStateException("injected abort failure"))
        try {
            assertEquals(RawAttachmentDisposition.ACCEPTED, coordinator.attachCaptureSession(session))
            val snap = coordinator.perform()
            assertEquals(RawCoordinatorPhase.CLOSED, snap.phase)
            assertTrue(session.stopped)
            assertTrue(session.closed)
            assertTrue(
                snap.records.any {
                    it.tag == "CaptureSession.abort" && !it.succeeded &&
                        it.failure is IllegalStateException
                }
            )
            assertTrue(snap.records.any { it.tag == "CaptureSession.stop" && it.succeeded })
            assertTrue(snap.records.any { it.tag == "CaptureSession.close" && it.succeeded })
        } finally {
            coordinator.perform()
        }
    }

    @Test
    fun workerCleanupReportPreservesQueuedTaskDisposalFailure() {
        val thread = HandlerThread("raw-worker-fail").apply { start() }
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val worker = BoundedCaptureWorker("raw-worker-fail-worker", 1)
        val coordinator = RawProductionResourceCoordinator(scheduler, worker, thread)
        val started = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        try {
            // Occupy the single worker thread so the failing task stays queued.
            assertTrue(
                worker.submit(Runnable {
                    started.countDown()
                    try {
                        unblock.await(5, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                    }
                })
            )
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val failing = object : DisposableCaptureTask {
                override fun run() {
                }
                override fun dispose() {
                    throw IllegalStateException("queued dispose failed")
                }
            }
            assertTrue(worker.submit(failing))
            assertEquals(1, worker.queuedCount())
            val snap = coordinator.perform()
            unblock.countDown()
            assertTrue(worker.awaitTermination(5_000))
            val report = snap.workerCleanupReport
            assertNotNull(report)
            assertEquals(1, report!!.queuedTasksRemoved)
            assertEquals(1, report.queuedDisposableTasksDisposalAttempted)
            assertEquals(0, report.queuedDisposableTasksDisposedSuccessfully)
            assertEquals(1, report.taskDisposalFailures.size)
            assertTrue(report.taskDisposalFailures[0].contains("queued dispose failed"))
            assertEquals(RawCoordinatorPhase.CLOSED, snap.phase)
        } finally {
            coordinator.perform()
            worker.close()
        }
    }
}
