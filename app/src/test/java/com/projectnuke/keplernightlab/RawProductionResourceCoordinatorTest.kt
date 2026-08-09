package com.projectnuke.keplernightlab

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
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
}
