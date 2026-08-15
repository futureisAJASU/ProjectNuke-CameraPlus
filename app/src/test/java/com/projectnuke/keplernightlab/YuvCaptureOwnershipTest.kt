package com.projectnuke.keplernightlab

import android.media.FakeImage
import android.media.Image
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
            assertTrue(worker.awaitTermination(5_000))
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
            worker.close()
            assertTrue(worker.awaitTermination(5_000))
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
        val drained = lifecycle.drainRetainedForTest()
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
        lifecycle.drainRetainedForTest()

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
        lifecycle.drainRetainedForTest()

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

        val drained = lifecycle.drainRetainedForTest()
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

        val drained = lifecycle.drainRetainedForTest()
        assertTrue(drained.isEmpty())
        assertEquals(0, disposeCount.get())
        assertEquals(1, lifecycle.encodingCount())
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

        lifecycle.drainRetainedForTest()
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
                assertTrue(start.await(5, TimeUnit.SECONDS))
                lifecycle.settleEncoding(item, accounting)
                done.countDown()
            },
            Thread {
                start.countDown()
                assertTrue(start.await(5, TimeUnit.SECONDS))
                lifecycle.claimRetainedForDrain().forEach { claim ->
                    claim.disposeAndFinish(accounting)
                }
                done.countDown()
            }
        )
        threads.forEach { it.start() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        threads.forEach {
            it.join(5_000)
            assertFalse("${it.name} still alive", it.isAlive)
        }

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
        lifecycle.drainRetainedForTest()

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

    // ---- additional lifecycle tests (Phase 2A-P1) ----

    @Test
    fun beginEncodingRacingCloseRetainsCorrectOwnership() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))

        val started = CountDownLatch(2)
        val done = CountDownLatch(2)
        val encodingWon = AtomicInteger(0)
        val drained = AtomicReference<List<YuvPngWorkItem>?>()

        val encoder = Thread {
            started.countDown(); assertTrue(started.await(5, TimeUnit.SECONDS))
            if (lifecycle.beginEncoding(item)) encodingWon.incrementAndGet()
            done.countDown()
        }
        val closer = Thread {
            started.countDown(); assertTrue(started.await(5, TimeUnit.SECONDS))
            drained.set(lifecycle.claimRetainedForDrain().map { it.item })
            done.countDown()
        }
        encoder.start(); closer.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        encoder.join(5_000)
        assertFalse("encoder still alive", encoder.isAlive)
        closer.join(5_000)
        assertFalse("closer still alive", closer.isAlive)

        // Exactly one side claims the item
        assertEquals(1, encodingWon.get() + (drained.get()?.size ?: 0))

        // If encoding won, settleEncoding removes it
        lifecycle.settleEncoding(item, accounting)
        // If the drain claim won, dispose the item and finish the claim
        if (drained.get()?.isNotEmpty() == true) {
            val claim = lifecycle.claimRetainedForDrain() // never returns a second claim
            assertTrue(claim.isEmpty())
            drained.get()?.forEach {
                it.dispose(accounting)
                lifecycle.finishDrain(it)
            }
        }

        assertEquals(0, lifecycle.retainedCount())
        assertEquals(0, lifecycle.encodingCount())
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0L, reservations.currentBytes())
        assertEquals(0, accounting.snapshot().bufferedFrames)
    }

    @Test
    fun repeatedCloseReturnsNoItemTwice() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))

        val first = lifecycle.drainRetainedForTest()
        assertEquals(listOf(item), first)
        val second = lifecycle.drainRetainedForTest()
        assertTrue(second.isEmpty())

        first.forEach { it.dispose(accounting) }
        assertEquals(0L, reservations.currentBytes())
    }

    @Test
    fun encodingItemRemainsTrackedThroughClose() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(100L))
        val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(item))
        assertTrue(lifecycle.beginEncoding(item))

        assertEquals(0, lifecycle.retainedCount())
        assertEquals(1, lifecycle.encodingCount())
        assertEquals(1, lifecycle.trackedCount())

        val drained = lifecycle.drainRetainedForTest()
        assertTrue(drained.isEmpty())
        assertEquals(0, lifecycle.retainedCount())
        assertEquals(1, lifecycle.encodingCount())
        assertEquals(1, lifecycle.trackedCount())

        lifecycle.settleEncoding(item, accounting)
        assertEquals(0, lifecycle.trackedCount())
        assertEquals(0L, reservations.currentBytes())
    }

    @Test
    fun registryCountsDistinguishRetainedAndEncoding() {
        val lifecycle = YuvBufferedLifecycle()
        val accounting = YuvCaptureAccounting()
        val reservations = YuvBufferReservations(1024L)
        assertTrue(reservations.tryReserve(200L))
        val rItem = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, reservations, accounting)
        val eItem = YuvPngWorkItem.bufferedForTest(1, 2L, 100L, reservations, accounting)
        assertTrue(lifecycle.tryRegister(rItem))
        assertTrue(lifecycle.tryRegister(eItem))
        assertTrue(lifecycle.beginEncoding(eItem))

        assertEquals(1, lifecycle.retainedCount())
        assertEquals(1, lifecycle.encodingCount())
        assertEquals(2, lifecycle.trackedCount())

        lifecycle.claimRetainedForDrain().forEach { claim ->
            claim.disposeAndFinish(accounting)
        }
        lifecycle.settleEncoding(eItem, accounting)

        assertEquals(0, lifecycle.trackedCount())
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
     * Phase 2A-P2: Direct success transfers Image ownership to the work item.  The
     * access wrapper is consumed by takeImage (no further access release), the item
     * owns the [OwnedDirectYuvSource], and dispose() closes the Image exactly once.
     */
    @Test
    fun directSuccessTransfersImageOwnershipToWorkItemAndClosesExactlyOnce() {
        val accounting = YuvCaptureAccounting()
        val fakeImage = FakeDirectImage()

        val result = createDirectYuvWork(0, fakeImage, accounting)

        assertTrue(result is DirectYuvWorkCreation.Accepted)
        assertEquals(0, accounting.snapshot().failedFrames)
        // takeImage consumed the access wrapper: ownership moved to the item's source.
        assertEquals(0, fakeImage.closeCount.get())
        val item = (result as DirectYuvWorkCreation.Accepted).item
        assertEquals(4321L, item.timestampNs)
        val wrapped = ((item.sourceForEncoding() as? YuvOwnedSource.Direct)
            ?.source as? AndroidOwnedDirectYuvSource)?.image as? FakeImage
        assertNotNull(wrapped)
        assertEquals(0, wrapped!!.closeCount.get())
        item.dispose(accounting)
        assertEquals(1, wrapped.closeCount.get())
        item.dispose(accounting)
        assertEquals(1, wrapped.closeCount.get())
    }

    /**
     * Phase 2A-P2: takeImage() returning null is a FAILURE (never a valid-null direct
     * item); the Image is released exactly once.
     */
    @Test
    fun directNullTakeImageFailsAndClosesOnce() {
        val accounting = YuvCaptureAccounting()
        val fakeImage = FakeDirectImage(nullImage = true)

        val result = createDirectYuvWork(0, fakeImage, accounting)

        assertTrue(result is DirectYuvWorkCreation.Failed)
        assertEquals(1, accounting.snapshot().failedFrames)
        assertEquals(1, fakeImage.closeCount.get())
        assertTrue((result as DirectYuvWorkCreation.Failed).cause is NullPointerException)
    }

    @Test
    fun directTakeImageThrowsFailsAndClosesOnce() {
        val accounting = YuvCaptureAccounting()
        val fakeImage = FakeDirectImage(throwOnTake = true)

        val result = createDirectYuvWork(0, fakeImage, accounting)

        assertTrue(result is DirectYuvWorkCreation.Failed)
        assertEquals(1, accounting.snapshot().failedFrames)
        assertEquals(1, fakeImage.closeCount.get())
    }

    @Test
    fun directSourceAdapterFailureClosesImageOnce() {
        val accounting = YuvCaptureAccounting()
        val fakeImage = FakeDirectImage()

        val result = createDirectYuvWork(
            0, fakeImage, accounting,
            sourceFactory = { _, _ -> error("source adapter failed") }
        )

        assertTrue(result is DirectYuvWorkCreation.Failed)
        assertEquals(1, accounting.snapshot().failedFrames)
        assertEquals(1, fakeImage.lastImage.get()!!.closeCount.get())
    }

    @Test
    fun directWorkItemConstructionFailureReleasesSourceOnce() {
        val accounting = YuvCaptureAccounting()
        val fakeImage = FakeDirectImage()

        val result = createDirectYuvWork(
            0, fakeImage, accounting,
            itemFactory = { _, _, _, _ -> error("work item construction failed") }
        )

        assertTrue(result is DirectYuvWorkCreation.Failed)
        assertEquals(1, accounting.snapshot().failedFrames)
        assertEquals(1, fakeImage.lastImage.get()!!.closeCount.get())
    }

    // ── Combined-failure release diagnostics (spec: every failure path performs at
    // most one release attempt and a release failure is reported, never discarded)

    @Test
    fun timestampFailureWithThrowingReleaseReportsBothFailures() {
        val accounting = YuvCaptureAccounting()
        val fakeImage = FakeDirectImage(failTimestamp = true, throwOnRelease = true)

        val failed = createDirectYuvWork(0, fakeImage, accounting) as DirectYuvWorkCreation.Failed

        assertEquals("timestamp failed", failed.cause.message)
        assertNotNull(failed.releaseFailure)
        assertEquals("access release failed", failed.releaseFailure!!.message)
        assertEquals(1, accounting.snapshot().failedFrames)
    }

    @Test
    fun takeImageThrowWithThrowingReleaseReportsBothFailures() {
        val accounting = YuvCaptureAccounting()
        val fakeImage = FakeDirectImage(throwOnTake = true, throwOnRelease = true)

        val failed = createDirectYuvWork(0, fakeImage, accounting) as DirectYuvWorkCreation.Failed

        assertEquals("takeImage failed", failed.cause.message)
        assertNotNull(failed.releaseFailure)
        assertEquals("access release failed", failed.releaseFailure!!.message)
        assertEquals(1, accounting.snapshot().failedFrames)
    }

    @Test
    fun nullTakeImageWithThrowingReleaseReportsBothFailures() {
        val accounting = YuvCaptureAccounting()
        val fakeImage = FakeDirectImage(nullImage = true, throwOnRelease = true)

        val failed = createDirectYuvWork(0, fakeImage, accounting) as DirectYuvWorkCreation.Failed

        assertTrue(failed.cause is NullPointerException)
        assertNotNull(failed.releaseFailure)
        assertEquals("access release failed", failed.releaseFailure!!.message)
        assertEquals(1, accounting.snapshot().failedFrames)
    }

    @Test
    fun sourceAdapterFailureWithThrowingImageCloseReportsBothFailures() {
        val accounting = YuvCaptureAccounting()
        val fakeImage = FakeDirectImage(closeThrows = true)

        val failed = createDirectYuvWork(
            0, fakeImage, accounting,
            sourceFactory = { _, _ -> error("source adapter failed") }
        ) as DirectYuvWorkCreation.Failed

        assertEquals("source adapter failed", failed.cause.message)
        assertNotNull(failed.releaseFailure)
        assertEquals("image close failed", failed.releaseFailure!!.message)
        assertEquals(1, accounting.snapshot().failedFrames)
        assertEquals(1, fakeImage.lastImage.get()!!.closeCount.get())
    }

    @Test
    fun itemConstructionFailureWithThrowingSourceReleaseReportsBothFailures() {
        val accounting = YuvCaptureAccounting()
        val fakeImage = FakeDirectImage()
        val throwingSource = object : OwnedDirectYuvSource {
            override val timestampNs: Long = 4321L
            override fun encodeTo(encoder: YuvPngEncoder, candidate: File, rotationDegrees: Int) =
                error("unreachable")
            override fun release() { error("source release failed") }
        }

        val failed = createDirectYuvWork(
            0, fakeImage, accounting,
            sourceFactory = { _, _ -> throwingSource },
            itemFactory = { _, _, _, _ -> error("work item construction failed") }
        ) as DirectYuvWorkCreation.Failed

        assertEquals("work item construction failed", failed.cause.message)
        assertNotNull(failed.releaseFailure)
        assertEquals("source release failed", failed.releaseFailure!!.message)
        assertEquals(1, accounting.snapshot().failedFrames)
    }

    @Test
    fun directImageFactoryWrapsImageAndClosesExactlyOnceOnDispose() {
        val image = FakeImage()
        val item = YuvPngWorkItem.direct(0, 4321L, image)
        assertEquals(0, image.closeCount.get())
        item.dispose(null)
        assertEquals(1, image.closeCount.get())
        item.dispose(null)
        assertEquals(1, image.closeCount.get())
    }

    /**
     * Phase 2A-P2: a valid (fake) direct source enters the SAME production
     * YuvPngWorkProcessor direct encode path — typed dispatch over the sealed
     * owned source, never via nullable probing.
     */
    @Test
    fun directSourceEntersProcessorDirectEncodePathThroughOwnedSource() {
        val dir = Files.createTempDirectory("yuv-direct-encode").toFile()
        try {
            val source = TestOwnedDirectYuvSource()
            val item = YuvPngWorkItem.directOwned(0, 4321L, source)
            val processor = YuvPngWorkProcessor(
                encoder = object : YuvPngEncoder {
                    override fun encodeDirect(image: Image, candidate: File, rotationDegrees: Int) =
                        error("encoder direct must not be reached for owned sources")
                    override fun encodeBuffered(frame: BufferedYuvFrame, candidate: File, rotationDegrees: Int) =
                        error("buffered encode must not be reached")
                },
                committer = YuvCandidateCommitter { _, _ -> }
            )

            processor.encode(item, File(dir, "candidate.tmp"), 0)

            assertEquals(1, source.encodeCount.get())
            assertFalse(source.released)
            item.dispose(null)
            assertTrue(source.released)
        } finally {
            dir.deleteRecursively()
        }
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
            assertTrue(worker.awaitTermination(5_000))
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
            assertTrue(worker.awaitTermination(5_000))
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
            assertTrue(worker.awaitTermination(5_000))
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

    /**
     * Mirrors Camera2DirectYuvImageAccess semantics: release after takeImage is a
     * no-op; the image handed over by takeImage is owned by the work item.
     */
    private class FakeDirectImage(
        private val failTimestamp: Boolean = false,
        private val nullImage: Boolean = false,
        private val throwOnTake: Boolean = false,
        private val throwOnRelease: Boolean = false,
        private val closeThrows: Boolean = false
    ) : DirectYuvImageAccess {
        val closeCount = AtomicInteger(0)
        val lastImage = AtomicReference<FakeImage?>()
        private var taken = false
        private var closed = false

        override fun timestampNs(): Long = if (failTimestamp) error("timestamp failed") else 4321L
        override fun allocationBytes(): Long = 0L
        override fun copy(frameIndex: Int): BufferedYuvFrame = error("unreachable for direct work")
        override fun release() {
            if (taken) return
            if (closed) error("Image closed twice")
            closed = true
            closeCount.incrementAndGet()
            if (throwOnRelease) error("access release failed")
        }
        override fun takeImage(): Image? {
            if (taken) error("takeImage called twice")
            if (throwOnTake) error("takeImage failed")
            if (nullImage) return null
            // Ownership only transfers on a successful, non-null take: for null/throw
            // paths the access was NOT consumed, so release() still closes it.
            taken = true
            return FakeImage(closeThrows = closeThrows).also { lastImage.set(it) }
        }
    }

    /** Test-owned direct source: released by the work item's dispose, encoded by the processor. */
    private class TestOwnedDirectYuvSource : OwnedDirectYuvSource {
        val encodeCount = AtomicInteger(0)
        @Volatile var released = false
        override val timestampNs: Long = 4321L
        override fun encodeTo(encoder: YuvPngEncoder, candidate: File, rotationDegrees: Int) {
            encodeCount.incrementAndGet()
        }
        override fun release() { released = true }
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

    @Test
    fun realYuvFinalFileVerifierAcceptsPngSignatureAndRejectsOtherContent() {
        val root = Files.createTempDirectory("kepler-yuv-verifier").toFile()
        try {
            val png = root.resolve("frame.png").apply {
                writeBytes(PNG_1X1)
            }
            assertTrue(RealYuvFinalFileVerifier.verify(png, 0))
            val garbage = root.resolve("frame.txt").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
            }
            assertFalse(RealYuvFinalFileVerifier.verify(garbage, 0))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun realYuvFinalFileVerifierRejectsMissingEmptyAndDirectoryTargets() {
        val root = Files.createTempDirectory("kepler-yuv-verifier").toFile()
        try {
            assertFalse(RealYuvFinalFileVerifier.verify(root.resolve("missing.png"), 0))
            val empty = root.resolve("empty.png").apply { writeBytes(ByteArray(0)) }
            assertFalse(RealYuvFinalFileVerifier.verify(empty, 0))
            assertFalse(RealYuvFinalFileVerifier.verify(root, 0))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun realYuvFinalFileVerifierPropagatesFatalDigestFailure() {
        val root = Files.createTempDirectory("kepler-yuv-verifier-fatal").toFile()
        val previousFailure = noFollowDigestFailureForTest
        try {
            val png = root.resolve("frame.png").apply { writeBytes(PNG_1X1) }
            val fatal = AssertionError("fatal digest failure")
            noFollowDigestFailureForTest = fatal

            val escaped = assertThrows(AssertionError::class.java) {
                RealYuvFinalFileVerifier.verify(png, 0)
            }
            assertEquals(fatal, escaped)
        } finally {
            noFollowDigestFailureForTest = previousFailure
            root.deleteRecursively()
        }
    }
}
