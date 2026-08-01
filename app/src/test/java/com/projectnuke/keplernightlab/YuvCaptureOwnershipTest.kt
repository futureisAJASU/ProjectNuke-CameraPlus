package com.projectnuke.keplernightlab

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
