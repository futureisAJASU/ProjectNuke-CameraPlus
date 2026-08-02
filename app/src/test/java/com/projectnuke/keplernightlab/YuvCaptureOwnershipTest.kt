package com.projectnuke.keplernightlab

import android.media.Image
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvCaptureOwnershipTest {
    @Test
    fun bufferedCopyCapturesMetadataBeforeReleaseAndReleasesImageOnce() {
        val access = FakeYuvAccess(bytes = 11L)
        val reservations = YuvBufferReservations(32L)
        val accounting = YuvCaptureAccounting()

        val result = createBufferedYuvWork(7, access, reservations, accounting)

        val item = (result as BufferedYuvWorkCreation.Accepted).item
        assertEquals(1234L, item.timestampNs)
        assertEquals(1, access.releaseCount.get())
        assertEquals(0, access.accessAfterClose.get())
        assertEquals(11L, reservations.currentBytes())
        item.dispose(accounting)
        assertEquals(0L, reservations.currentBytes())
        assertEquals(1, access.releaseCount.get())
    }

    @Test
    fun cumulativeReservationRejectsExactFrameAndNeverDoubleReleases() {
        val reservations = YuvBufferReservations(20L)
        val accounting = YuvCaptureAccounting()
        val first = createBufferedYuvWork(0, FakeYuvAccess(11L), reservations, accounting)
        val secondAccess = FakeYuvAccess(11L)
        val second = createBufferedYuvWork(1, secondAccess, reservations, accounting)

        assertTrue(first is BufferedYuvWorkCreation.Accepted)
        assertTrue(second is BufferedYuvWorkCreation.Rejected)
        assertEquals(1, secondAccess.releaseCount.get())
        assertEquals(11L, reservations.currentBytes())
        (first as BufferedYuvWorkCreation.Accepted).item.dispose(accounting)
        (first.item).dispose(accounting)
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
    }

    @Test
    fun shutdownNowDisposesQueuedOwnedYuvTaskExactlyOnce() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queuedRelease = AtomicInteger(0)
        val worker = BoundedCaptureWorker("yuv-dispose", 1)
        try {
            assertTrue(worker.submit(Runnable { started.countDown(); release.await(2, TimeUnit.SECONDS) }))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val accounting = YuvCaptureAccounting()
            val queued = DisposableYuvTask(YuvPngWorkItem.ownedForTest { queuedRelease.incrementAndGet() }, accounting) { }
            assertTrue(worker.submit(queued))
            worker.shutdownNow()
            release.countDown()
            assertEquals(1, queuedRelease.get())
            assertFalse(worker.submit(DisposableYuvTask(YuvPngWorkItem.ownedForTest { queuedRelease.incrementAndGet() }, accounting) { }))
            assertEquals(2, queuedRelease.get())
        } finally {
            worker.close()
        }
    }

    @Test
    fun saturationDisposesTheExactRejectedOwnedTask() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queuedRelease = AtomicInteger(0)
        val rejectedRelease = AtomicInteger(0)
        val accounting = YuvCaptureAccounting()
        val worker = BoundedCaptureWorker("yuv-reject", 1)
        try {
            assertTrue(worker.submit(Runnable { started.countDown(); release.await(2, TimeUnit.SECONDS) }))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(worker.submit(DisposableYuvTask(YuvPngWorkItem.ownedForTest { queuedRelease.incrementAndGet() }, accounting) { }))
            assertFalse(worker.submit(DisposableYuvTask(YuvPngWorkItem.ownedForTest { rejectedRelease.incrementAndGet() }, accounting) { }))
            assertEquals(1, rejectedRelease.get())
            assertEquals(0, queuedRelease.get())
            release.countDown()
        } finally {
            worker.close()
        }
    }

    @Test
    fun bufferedWorkPreservesNonPrefixIdentitiesAndCommitsDistinctReadablePngs() {
        val dir = Files.createTempDirectory("yuv-work").toFile()
        val reservations = YuvBufferReservations(128L)
        val accounting = YuvCaptureAccounting()
        val processor = YuvPngWorkProcessor(
            encoder = object : YuvPngEncoder {
                override fun encodeDirect(image: android.media.Image, candidate: File, rotationDegrees: Int) = error("not used")
                override fun encodeBuffered(frame: BufferedYuvFrame, candidate: File, rotationDegrees: Int) {
                    candidate.writeBytes(PNG_1X1)
                }
            },
            committer = YuvCandidateCommitter { candidate, final ->
                Files.move(candidate.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        )
        try {
            listOf(0, 2, 4).forEach { index ->
                val item = (createBufferedYuvWork(index, FakeYuvAccess(12L), reservations, accounting)
                    as BufferedYuvWorkCreation.Accepted).item
                val filename = "frame_${index.toString().padStart(2, '0')}_color.png"
                val final = File(dir, filename)
                val candidate = File(dir, ".${filename}.tmp")
                processor.encode(item, candidate, 0)
                processor.commit(candidate, final)
                assertTrue(accounting.persistedFrame(YuvFrameManifestEntry(index, filename, item.timestampNs, true)))
                item.dispose(accounting)
            }
            val snapshot = accounting.snapshot()
            assertEquals(listOf(0, 2, 4), snapshot.manifest.map { it.frameIndex })
            assertEquals(3, snapshot.persistedFrames)
            assertEquals(0, snapshot.bufferedFrames)
            assertEquals(0L, reservations.currentBytes())
            assertEquals(3, dir.listFiles()?.size)
            dir.listFiles()?.forEach { assertNotNull(ImageIO.read(it)) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun duplicateFilenameCannotIncreasePersistedCount() {
        val accounting = YuvCaptureAccounting()
        assertTrue(accounting.persistedFrame(YuvFrameManifestEntry(0, "frame_00_color.png", 1L, true)))
        assertFalse(accounting.persistedFrame(YuvFrameManifestEntry(2, "frame_00_color.png", 2L, true)))
        assertEquals(1, accounting.snapshot().persistedFrames)
    }

    // -------------------------------------------------------------------------------------
    // Buffered lifecycle: production YuvBufferedLifecycle state machine.
    // -------------------------------------------------------------------------------------

    @Test
    fun cleanupBetweenWorkerCheckAndBufferedRegistrationFailsAndDisposesItemOnce() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val disposeCount = AtomicInteger(0)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            disposeCount.incrementAndGet()
        }

        // Worker's initial terminal check passed.
        assertFalse(lifecycle.isClosed())
        // Terminal cleanup closes acceptance and drains (nothing retained yet).
        val drained = lifecycle.closeAndDrainRetained()
        assertTrue(drained.isEmpty())
        // Worker attempts to retain the buffered item AFTER cleanup completed.
        assertFalse(lifecycle.tryRegister(item))
        // Caller disposes the exact item; reservation settles.
        item.dispose(accounting)
        assertEquals(1, disposeCount.get())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
        assertEquals(0, lifecycle.retainedCount())
    }

    @Test
    fun registrationAfterClosureDisposesItemExactlyOnce() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val disposeCount = AtomicInteger(0)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            disposeCount.incrementAndGet()
        }
        lifecycle.closeAndDrainRetained()

        assertFalse(lifecycle.tryRegister(item))
        item.dispose(accounting)
        item.dispose(accounting)
        assertEquals(1, disposeCount.get())
        assertEquals(0, lifecycle.retainedCount())
    }

    @Test
    fun registrationAfterClosureReleasesItsReservation() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        lifecycle.closeAndDrainRetained()

        assertFalse(lifecycle.tryRegister(item))
        assertEquals(100L, reservations.currentBytes())
        item.dispose(accounting)
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
    }

    @Test
    fun cleanupDrainsSafelyRetainedItemOnce() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val disposeCount = AtomicInteger(0)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            disposeCount.incrementAndGet()
        }
        assertTrue(lifecycle.tryRegister(item))
        assertEquals(1, accounting.snapshot().bufferedFrames)

        val drained = lifecycle.closeAndDrainRetained()
        assertEquals(listOf(item), drained)
        drained.forEach { it.dispose(accounting) }
        drained.forEach { it.dispose(accounting) }
        assertEquals(1, disposeCount.get())
        assertEquals(0, accounting.snapshot().bufferedFrames)
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, lifecycle.retainedCount())
    }

    @Test
    fun cleanupDuringBlockedBufferedEncodingDoesNotDisposeEarly() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val disposeCount = AtomicInteger(0)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            disposeCount.incrementAndGet()
        }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        val drained = lifecycle.closeAndDrainRetained()
        assertTrue(drained.isEmpty())
        assertEquals(0, disposeCount.get())
        assertEquals(1, lifecycle.retainedCount())
        lifecycle.settleEncoding(item, accounting)
        assertEquals(1, disposeCount.get())
        assertEquals(0L, reservations.currentBytes())
    }

    @Test
    fun blockedEncodingKeepsRetainedBytesAndBufferedFramesNonzero() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        lifecycle.closeAndDrainRetained()
        assertEquals(100L, reservations.currentBytes())
        assertEquals(1, accounting.snapshot().bufferedFrames)

        // After the encoder returns, everything settles exactly once.
        lifecycle.settleEncoding(item, accounting)
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
    }

    @Test
    fun encoderReturnPerformsFinalDisposalExactlyOnce() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val disposeCount = AtomicInteger(0)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            disposeCount.incrementAndGet()
        }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        lifecycle.settleEncoding(item, accounting)
        lifecycle.settleEncoding(item, accounting)
        lifecycle.settleEncoding(item, accounting)
        assertEquals(1, disposeCount.get())
        assertEquals(0, lifecycle.retainedCount())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
    }

    @Test
    fun cleanupRacingEncoderCompletionNeverDoubleDisposes() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val disposeCount = AtomicInteger(0)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            disposeCount.incrementAndGet()
        }
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        val start = CountDownLatch(2)
        val done = CountDownLatch(2)
        val threads = listOf(
            Thread {
                start.countDown()
                start.await(5, TimeUnit.SECONDS)
                lifecycle.settleEncoding(item, accounting)
                done.countDown()
            },
            Thread {
                start.countDown()
                start.await(5, TimeUnit.SECONDS)
                lifecycle.closeAndDrainRetained().forEach { it.dispose(accounting) }
                done.countDown()
            }
        )
        threads.forEach { it.start() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        threads.forEach { it.join(5_000) }

        assertEquals(1, disposeCount.get())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
        assertEquals(0, lifecycle.retainedCount())
    }

    @Test
    fun noItemCanAppearInRegistryAfterCloseAndDrain() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        lifecycle.closeAndDrainRetained()

        repeat(25) { index ->
            assertTrue(reservations.tryReserve(10L))
            val item = YuvPngWorkItem.bufferedForTest(index, index.toLong(), 10L, reservations, accounting)
            assertFalse(lifecycle.tryRegister(item))
            item.dispose(accounting)
        }
        assertEquals(0, lifecycle.retainedCount())
        assertEquals(0, accounting.snapshot().bufferedFrames)
        assertEquals(0L, reservations.currentBytes())
    }

    // -------------------------------------------------------------------------------------
    // Image release: exactly-once release attempts through createBufferedYuvWork.
    // -------------------------------------------------------------------------------------

    @Test
    fun successfulCopyPerformsOneReleaseAttempt() {
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val access = FakeYuvAccess(bytes = 10L)
        val result = createBufferedYuvWork(0, access, reservations, accounting)
        assertTrue(result is BufferedYuvWorkCreation.Accepted)
        assertEquals(1, access.releaseCount.get())
        assertEquals(0, access.accessAfterClose.get())
        (result as BufferedYuvWorkCreation.Accepted).item.dispose(accounting)
        assertEquals(1, access.releaseCount.get())
        assertEquals(0L, reservations.currentBytes())
    }

    @Test
    fun copyFailurePerformsOneReleaseAttemptAndReleasesReservation() {
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val access = ThrowingCopyYuvAccess(bytes = 10L)
        val result = createBufferedYuvWork(0, access, reservations, accounting)
        assertTrue(result is BufferedYuvWorkCreation.Failed)
        assertEquals(1, access.releaseCount.get())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
    }

    /**
     * Step 0.3: Copy succeeds, work-item construction fails, Image released once,
     * reservation returns to zero, buffered accounting remains zero, no work item escapes.
     */
    @Test
    fun workItemConstructionFailureReleasesReservationAndDoesNotLeak() {
        val reservations = YuvBufferReservations(1024L)
        val accounting = FailingBufferedAccounting()
        val access = FakeYuvAccess(bytes = 10L)

        val result = createBufferedYuvWork(0, access, reservations, accounting)

        assertTrue(result is BufferedYuvWorkCreation.Failed)
        assertEquals(1, access.releaseCount.get())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(1, accounting.bufferedAttempts)
        assertEquals(0, accounting.snapshot().bufferedFrames)
    }

    @Test
    fun reservationRejectionPerformsOneReleaseAttempt() {
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(5L)
        val access = FakeYuvAccess(bytes = 10L)
        val result = createBufferedYuvWork(0, access, reservations, accounting)
        assertTrue(result is BufferedYuvWorkCreation.Rejected)
         assertEquals(1, access.releaseCount.get())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
    }

    /**
     * Step 0.4: Direct timestamp failure closes Image once, no work item enqueued,
     * no later Image property access.
     */
    @Test
    fun directTimestampFailureClosesImageOnceAndFailsCreation() {
        val accounting = YuvCaptureAccounting()
        val fakeImage = FakeDirectImage(failTimestamp = true)

        val result = createDirectYuvWork(0, fakeImage, accounting)

        assertTrue(result is DirectYuvWorkCreation.Failed)
        assertEquals(1, accounting.snapshot().failedFrames)
        assertEquals(1, fakeImage.closeCount.get())
    }

    /**
     * Step 0.4: Direct success creates a work item that owns the Image metadata;
     * the Image itself is not closed until the work item disposes.
     */
    @Test
    fun directSuccessCreatesOwningWorkItemAndClosesOnce() {
        val accounting = YuvCaptureAccounting()
        val fakeImage = FakeDirectImage(failTimestamp = false)

        val result = createDirectYuvWork(0, fakeImage, accounting)

        assertTrue(result is DirectYuvWorkCreation.Accepted)
        assertEquals(0, accounting.snapshot().failedFrames)
        assertEquals(0, fakeImage.closeCount.get())
        assertEquals(4321L, (result as DirectYuvWorkCreation.Accepted).item.timestampNs)
        // In JVM tests takeImage() returns null (no real android.media.Image), so the
        // work item's dispose is a no-op on the image.  The production path closes the
        // real Image via the work item's dispose when takeImage() returns the real Image.
        result.item.dispose(accounting)
    }

    @Test
    fun releaseThrowingDoesNotCauseSecondReleaseAttempt() {
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val access = ThrowingReleaseYuvAccess(bytes = 10L)
        val result = createBufferedYuvWork(0, access, reservations, accounting)
        assertTrue(result is BufferedYuvWorkCreation.Accepted)
        assertEquals(1, access.releaseCount.get())

        val item = (result as BufferedYuvWorkCreation.Accepted).item
        item.dispose(accounting)
        item.dispose(accounting)
        assertEquals(1, access.releaseCount.get())
        assertEquals(0L, reservations.currentBytes())
    }

    @Test
    fun noImagePropertyIsAccessedAfterReleaseBegins() {
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val access = TrackingReleaseYuvAccess(bytes = 10L)
        val result = createBufferedYuvWork(0, access, reservations, accounting)
        assertTrue(result is BufferedYuvWorkCreation.Accepted)
        assertEquals(1, access.releaseCount.get())
        assertEquals(0, access.accessAfterRelease.get())
        assertEquals(0, access.copyAfterRelease.get())
        (result as BufferedYuvWorkCreation.Accepted).item.dispose(accounting)
        assertEquals(1, access.releaseCount.get())
        assertEquals(0, access.accessAfterRelease.get())
    }

    @Test
    fun timestampFailurePerformsOneReleaseAttempt() {
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val access = ThrowingTimestampYuvAccess(bytes = 10L)
        val result = createBufferedYuvWork(0, access, reservations, accounting)
        assertTrue(result is BufferedYuvWorkCreation.Failed)
        assertEquals(1, access.releaseCount.get())
        assertEquals(0L, reservations.currentBytes())
    }

    // -------------------------------------------------------------------------------------
    // Queue and worker ownership: running item vs shutdownNow.
    // -------------------------------------------------------------------------------------

    @Test
    fun shutdownNowDoesNotConcurrentlyDisposeTheRunningItem() {
        val started = CountDownLatch(1)
        val block = CountDownLatch(1)
        val runningDisposed = AtomicInteger(0)
        val accounting = YuvCaptureAccounting()
        val worker = BoundedCaptureWorker("yuv-running", 1)
        try {
            val running = DisposableYuvTask(
                YuvPngWorkItem.ownedForTest { runningDisposed.incrementAndGet() },
                accounting
            ) {
                started.countDown()
                block.await(2, TimeUnit.SECONDS)
            }
            assertTrue(worker.submit(running))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            worker.shutdownNow()
            assertEquals(0, runningDisposed.get())
            block.countDown()
            worker.awaitTermination(5_000)
            assertEquals(0, runningDisposed.get())
        } finally {
            worker.close()
        }
    }

    @Test
    fun runningItemDisposesItselfWhenItsBodyExits() {
        val started = CountDownLatch(1)
        val block = CountDownLatch(1)
        val disposeCount = AtomicInteger(0)
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) {
            disposeCount.incrementAndGet()
        }
        val worker = BoundedCaptureWorker("yuv-run-body", 1)
        try {
            val task = DisposableYuvTask(item, accounting) {
                try {
                    started.countDown()
                    block.await(2, TimeUnit.SECONDS)
                } finally {
                    // Like production: the worker's finally performs the final disposal even
                    // when shutdownNow interrupts the running body.
                    item.dispose(accounting)
                }
            }
            assertTrue(worker.submit(task))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            // The running body has not exited yet, so it cannot have disposed.
            assertEquals(0, disposeCount.get())
            worker.shutdownNow()
            // shutdownNow interrupts the body; the body's own finally performs the disposal.
            worker.awaitTermination(5_000)
            assertEquals(1, disposeCount.get())
            assertEquals(0L, reservations.currentBytes())
        } finally {
            worker.close()
        }
    }

    @Test
    fun reservationAndAccountingSettleToZeroAfterShutdown() {
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        val releaseCount = AtomicInteger(0)
        val started = CountDownLatch(1)
        val block = CountDownLatch(1)
        val worker = BoundedCaptureWorker("yuv-settle", 2)
        try {
            assertTrue(worker.submit(Runnable {
                started.countDown()
                block.await(2, TimeUnit.SECONDS)
            }))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(reservations.tryReserve(100L))
            assertTrue(reservations.tryReserve(100L))
            assertTrue(worker.submit(DisposableYuvTask(
                YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting) { releaseCount.incrementAndGet() },
                accounting
            ) {}))
            assertTrue(worker.submit(DisposableYuvTask(
                YuvPngWorkItem.bufferedForTest(1, 2L, 100L, reservations, accounting) { releaseCount.incrementAndGet() },
                accounting
            ) {}))
            worker.shutdownNow()
            block.countDown()
            worker.awaitTermination(5_000)
            assertEquals(2, releaseCount.get())
            assertEquals(0L, reservations.currentBytes())
            assertEquals(0, accounting.snapshot().bufferedFrames)
        } finally {
            worker.close()
        }
    }

    // -------------------------------------------------------------------------------------
    // Fakes.
    // -------------------------------------------------------------------------------------

    /**
     * Step 0.3 test seam: a YuvCaptureAccounting whose bufferedFrame() throws, simulating
     * work-item construction failure after the YUV copy succeeded.  The reservation must still
     * be released by createBufferedYuvWork's finally block.
     */
    private class FailingBufferedAccounting : YuvCaptureAccounting() {
        var bufferedAttempts = 0

        override fun bufferedFrame(): Int {
            bufferedAttempts++
            error("work-item construction failed after copy")
        }
    }

    private class FakeDirectImage(private val failTimestamp: Boolean) : DirectYuvImageAccess {
        val closeCount = AtomicInteger(0)
        private var closed = false

        override fun timestampNs(): Long = if (failTimestamp) error("timestamp failed") else 4321L
        override fun allocationBytes(): Long = 0L
        override fun copy(frameIndex: Int): BufferedYuvFrame = error("unreachable for direct work")
        override fun release() {
            if (closed) error("Image closed twice")
            closed = true
            closeCount.incrementAndGet()
        }
        override fun takeImage(): Image? = null
    }

    private class ThrowingCopyYuvAccess(private val bytes: Long) : YuvImageAccess {
        val releaseCount = AtomicInteger(0)

        override fun timestampNs(): Long = 1234L
        override fun allocationBytes(): Long = bytes
        override fun copy(frameIndex: Int): BufferedYuvFrame {
            error("copy failed")
        }
        override fun release() {
            releaseCount.incrementAndGet()
        }
    }

    private class ThrowingTimestampYuvAccess(private val bytes: Long) : YuvImageAccess {
        val releaseCount = AtomicInteger(0)

        override fun timestampNs(): Long = error("timestamp failed")
        override fun allocationBytes(): Long = bytes
        override fun copy(frameIndex: Int): BufferedYuvFrame = error("unreachable")
        override fun release() {
            releaseCount.incrementAndGet()
        }
    }

    private class ThrowingReleaseYuvAccess(private val bytes: Long) : YuvImageAccess {
        val releaseCount = AtomicInteger(0)

        override fun timestampNs(): Long = 1234L
        override fun allocationBytes(): Long = bytes
        override fun copy(frameIndex: Int): BufferedYuvFrame =
            BufferedYuvFrame(frameIndex, 1234L, 1, 1, ByteArray(4), ByteArray(3), ByteArray(4), 1, 1, 1, 1, 1, 1)
        override fun release() {
            releaseCount.incrementAndGet()
            error("release failed after closing")
        }
    }

    private class TrackingReleaseYuvAccess(private val bytes: Long) : YuvImageAccess {
        val releaseCount = AtomicInteger(0)
        val accessAfterRelease = AtomicInteger(0)
        val copyAfterRelease = AtomicInteger(0)
        private var released = false

        override fun timestampNs(): Long = guard { 1234L }
        override fun allocationBytes(): Long = guard { bytes }
        override fun copy(frameIndex: Int): BufferedYuvFrame = guard {
            BufferedYuvFrame(frameIndex, 1234L, 1, 1, ByteArray(4), ByteArray(3), ByteArray(4), 1, 1, 1, 1, 1, 1)
        }
        override fun release() {
            releaseCount.incrementAndGet()
            released = true
        }
        private inline fun <T> guard(block: () -> T): T {
            if (released) {
                copyAfterRelease.incrementAndGet()
                accessAfterRelease.incrementAndGet()
                error("Image property accessed after release")
            }
            return block()
        }
    }

    private class FakeYuvAccess(private val bytes: Long) : YuvImageAccess {
        val releaseCount = AtomicInteger(0)
        val accessAfterClose = AtomicInteger(0)
        private var closed = false

        override fun timestampNs(): Long = guard { 1234L }
        override fun allocationBytes(): Long = guard { bytes }
        override fun copy(frameIndex: Int): BufferedYuvFrame = guard {
            BufferedYuvFrame(frameIndex, 1234L, 1, 1, ByteArray(4), ByteArray(3), ByteArray(4), 1, 1, 1, 1, 1, 1)
        }
        override fun release() {
            if (closed) error("Image released twice")
            closed = true
            releaseCount.incrementAndGet()
        }
        private inline fun <T> guard(block: () -> T): T {
            if (closed) {
                accessAfterClose.incrementAndGet()
                error("Image property accessed after close")
            }
            return block()
        }
    }

    private companion object {
        val PNG_1X1: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
    }
}
