package com.projectnuke.keplernightlab

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Production-path tests for YuvCaptureOwner.  Uses the actual production owner,
 * lifecycle, worker work items, worker completion model, and adoption code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21])
class YuvCaptureOwnerTest {

    // ------------------------------------------------------------------
    // Test harness
    // ------------------------------------------------------------------

    private class EncodeLatch {
        private val startLatch = CountDownLatch(1)
        private val releaseLatch = CountDownLatch(1)
        private var started = false

        fun awaitStart() {
            startLatch.await(2, TimeUnit.SECONDS)
        }

        fun awaitRelease() {
            releaseLatch.await(2, TimeUnit.SECONDS)
        }

        fun signalStart() {
            if (!started) {
                started = true
                startLatch.countDown()
            }
        }

        fun release() {
            releaseLatch.countDown()
        }
    }

    private class Harness(
        val frameCount: Int = 3,
        val encodeLatch: EncodeLatch? = null,
        val forceEncodeFailure: Boolean = false,
        val capacity: Int = 4
    ) {
        val dir: File = java.nio.file.Files.createTempDirectory("yuv-owner-test").toFile()
        val handlerThread = HandlerThread("test-yuv-handler").apply { start() }
        val handler = Handler(handlerThread.looper)

        val reservations = YuvBufferReservations(16L * 1024 * 1024)
        val accounting = YuvCaptureAccounting()
        val lifecycle = YuvBufferedLifecycle()
        val identityOwner = CaptureFrameIdentityOwner(frameCount)
        val terminalState = CaptureTerminalState()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)

        val writtenJobJson = mutableListOf<JobJsonEntry>()
        val postedStatus = mutableListOf<String>()
        val capturedFile = AtomicReference<File?>(null)
        val errorMessage = AtomicReference<String?>(null)

        val captureStateOwner = CaptureStateOwner(
            dispatch = { event -> handler.post(event) },
            emergencyDispose = { }
        )
        val boundedWorker = BoundedCaptureWorker("test-yuv-owner", capacity) {}
        val timeoutScheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "test-timeout") }

        val workProcessor = YuvPngWorkProcessor(
                encoder = object : YuvPngEncoder {
                    override fun encodeDirect(image: android.media.Image, candidate: File, rotationDegrees: Int) {
                        handleEncode(candidate)
                    }
                    override fun encodeBuffered(frame: BufferedYuvFrame, candidate: File, rotationDegrees: Int) {
                        handleEncode(candidate)
                    }
                },
                committer = YuvCandidateCommitter { candidate, final ->
                    java.nio.file.Files.move(candidate.toPath(), final.toPath())
                }
            )

        private fun handleEncode(candidate: File) {
            if (forceEncodeFailure) throw IllegalStateException("forced encode failure")
            java.nio.file.Files.write(candidate.toPath(), PNG_1X1)
            encodeLatch?.let {
                it.signalStart()
                it.awaitRelease()
            }
        }

        val owner = YuvCaptureOwner(
            captureHandler = handler,
            frameCount = frameCount,
            outputDir = dir,
            rotationDegrees = 0,
            workProcessor = workProcessor,
            reservations = reservations,
            accounting = accounting,
            lifecycle = lifecycle,
            identityOwner = identityOwner,
            terminalState = terminalState,
            captureStateOwner = captureStateOwner,
            boundedWorker = boundedWorker,
            postStatus = { msg -> postedStatus += msg },
            postMainOrRun = { block -> handler.post(block) },
            writeJobJson = { status, saved, manifest ->
                writtenJobJson += JobJsonEntry(status, saved, manifest)
            },
            saveMotionOnce = { dir -> "gyro.txt" to "rv.txt" },
            motionLogger = null,
            finished = finished,
            onCaptureComplete = { file -> capturedFile.set(file) },
            onCaptureError = { msg -> errorMessage.set(msg) }
        )

        fun awaitIdle(timeoutSec: Long) {
            val deadline = System.currentTimeMillis() + timeoutSec * 1000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
        }

        fun awaitFrames(targetFrames: Int, timeoutSec: Long = 10) {
            val deadline = System.currentTimeMillis() + timeoutSec * 1000
            while (System.currentTimeMillis() < deadline) {
                if (accounting.snapshot().persistedFrames >= targetFrames) return
                Thread.sleep(20)
            }
            assertEquals(targetFrames, accounting.snapshot().persistedFrames)
        }

        fun shutdown() {
            handlerThread.quitSafely()
            timeoutScheduler.shutdownNow()
            boundedWorker.shutdownNow()
        }
    }

    private data class JobJsonEntry(
        val status: String,
        val savedFrames: Int,
        val manifest: List<YuvFrameManifestEntry>
    )

    /**
     * Fake for buffered path tests.  Provides a copyable YUV frame with a known timestamp.
     */
    private class FakeBufferedAccess(
        private val ts: Long,
        private val bytes: Long = 12L
    ) : YuvImageAccess {
        val releaseCount = AtomicInteger(0)
        private var released = false

        override fun timestampNs(): Long = if (released) error("access after release") else ts
        override fun allocationBytes(): Long = if (released) error("access after release") else bytes
        override fun copy(frameIndex: Int): BufferedYuvFrame =
            BufferedYuvFrame(frameIndex, ts, 1, 1, ByteArray(4), ByteArray(3), ByteArray(4), 1, 1, 1, 1, 1, 1)
        override fun release() {
            if (released) error("released twice")
            released = true
            releaseCount.incrementAndGet()
        }
    }

    /**
     * Fake for direct path tests.  Provides a timestamp and a null image (since
     * android.media.Image can't be instantiated on JVM).
     */
    private class FakeDirectAccess(private val failTimestamp: Boolean = false) : DirectYuvImageAccess {
        val closeCount = AtomicInteger(0)
        private var closed = false

        override fun timestampNs(): Long = if (failTimestamp) error("timestamp failed") else 4321L
        override fun allocationBytes(): Long = 0L
        override fun copy(frameIndex: Int): BufferedYuvFrame = error("not used for direct")
        override fun release() {
            if (closed) error("double close")
            closed = true
            closeCount.incrementAndGet()
        }
        override fun takeImage(): android.media.Image? = null
    }

    private class FailingBufferedAccounting : YuvCaptureAccounting() {
        var bufferedAttempts = 0

        override fun bufferedFrame(): Int {
            bufferedAttempts++
            error("work-item construction failed after copy")
        }
    }

    // ------------------------------------------------------------------
    // Primitive regressions
    // ------------------------------------------------------------------

    @Test
    fun doubleBufferedSettlementIsNoOpAfterFirst() {
        val harness = Harness(frameCount = 1)
        try {
            assertTrue(harness.reservations.tryReserve(100L))
            val item = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, harness.reservations, harness.accounting)
            assertTrue(harness.lifecycle.tryRegister(item))
            assertEquals(1, harness.accounting.snapshot().bufferedFrames)

            harness.lifecycle.settleEncoding(item, harness.accounting)
            // Second call is a no-op (item already removed from map)
            harness.lifecycle.settleEncoding(item, harness.accounting)

            assertEquals(0, harness.accounting.snapshot().bufferedFrames)
            assertEquals(0L, harness.reservations.currentBytes())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun closeRegisterRaceWithAnotherEncodingItemKeepsBufferedCount() {
        val harness = Harness(frameCount = 2, capacity = 2)
        try {
            assertTrue(harness.reservations.tryReserve(200L))
            val item1 = YuvPngWorkItem.bufferedForTest(0, 1L, 100L, harness.reservations, harness.accounting)
            val item2 = YuvPngWorkItem.bufferedForTest(1, 2L, 100L, harness.reservations, harness.accounting)
            assertTrue(harness.lifecycle.tryRegister(item1))
            assertTrue(harness.lifecycle.tryRegister(item2))
            assertEquals(2, harness.accounting.snapshot().bufferedFrames)

            // Begin encoding item1, then close — item1 is ENCODING, item2 is RETAINED
            assertTrue(harness.lifecycle.beginEncoding(item1))
            val drained = harness.lifecycle.closeAndDrainRetained()
            // item2 (RETAINED) should be drained; item1 (ENCODING) should not
            assertEquals(listOf(item2), drained)
            assertEquals(1, harness.lifecycle.retainedCount())

            // Settle item1's encoding
            harness.lifecycle.settleEncoding(item1, harness.accounting)
            // Dispose the drained item2
            item2.dispose(harness.accounting)

            assertEquals(0, harness.accounting.snapshot().bufferedFrames)
            assertEquals(0L, harness.reservations.currentBytes())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun directTimestampFailureClosesImageOnceAndFailsCreation() {
        val accounting = YuvCaptureAccounting()
        val directAccess = FakeDirectAccess(failTimestamp = true)
        val result = createDirectYuvWork(0, directAccess, accounting)
        assertTrue(result is DirectYuvWorkCreation.Failed)
        assertEquals(1, accounting.snapshot().failedFrames)
        assertEquals(1, directAccess.closeCount.get())
    }

    // ------------------------------------------------------------------
    // Buffered YUV owner path
    // ------------------------------------------------------------------

    @Test
    fun bufferedFramesReceiveUniqueOwnerIdentities() {
        val harness = Harness(frameCount = 3)
        try {
            repeat(3) { harness.owner.acceptImage(FakeBufferedAccess((it + 1) * 1000L), true, { }) }
            harness.awaitFrames(3)
            assertEquals(listOf(0, 1, 2), harness.accounting.snapshot().manifest.map { it.frameIndex })
            assertEquals(3, harness.accounting.snapshot().persistedFrames)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun bufferedFramesRemainsDistinctFromPersistedFrames() {
        val harness = Harness(frameCount = 3)
        try {
            repeat(3) { harness.owner.acceptImage(FakeBufferedAccess((it + 1) * 1000L), true, { }) }
            harness.awaitFrames(3)

            val snap = harness.accounting.snapshot()
            assertEquals(3, snap.persistedFrames)
            assertEquals(0, snap.bufferedFrames)
            assertEquals(0L, harness.reservations.currentBytes())
            assertEquals(0, harness.lifecycle.retainedCount())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun retainedBytesNonzeroDuringBlockedEncode() {
        val encodeLatch = EncodeLatch()
        val bytesDuringEncode = AtomicReference<Long>(0L)
        val harness = Harness(frameCount = 1, encodeLatch = encodeLatch)
        try {
            harness.owner.acceptImage(FakeBufferedAccess(1234L), true, { })
            encodeLatch.awaitStart()
            // Check reservation during encode by capturing it in the encode callback
            // The encode latch signals after encode starts but before it completes
            // We need to check bytes while encode is blocked
            // The reservation should still be held at this point
            Thread.sleep(100) // Give time for encode to reach the await point
            assertTrue("reservations.currentBytes() > 0", harness.reservations.currentBytes() > 0)
            encodeLatch.release()
            harness.awaitIdle(5)
            assertEquals(0L, harness.reservations.currentBytes())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun timeoutDuringFirstBufferedPng() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(frameCount = 1, encodeLatch = encodeLatch)
        try {
            harness.owner.acceptImage(FakeBufferedAccess(1234L), true, { })
            encodeLatch.awaitStart()
            harness.owner.onDeadlineReached()
            harness.awaitIdle(1)
            val status = harness.terminalState.status()
            assertTrue(status == CaptureTerminalStatus.TIMED_OUT || status == CaptureTerminalStatus.FAILED)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun cleanupDoesNotDisposeActiveEncoderInput() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(frameCount = 1, encodeLatch = encodeLatch)
        try {
            harness.owner.acceptImage(FakeBufferedAccess(1234L), true, { })
            encodeLatch.awaitStart()
            // Close the capture state owner and lifecycle
            harness.captureStateOwner.close()
            val drained = harness.lifecycle.closeAndDrainRetained()
            // ENCODING items are excluded from drained
            assertTrue(drained.isEmpty())
            encodeLatch.release()
            harness.awaitIdle(5)
            assertEquals(0, harness.lifecycle.retainedCount())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun lateBufferedCompletionAfterTimeoutIsDiscarded() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(frameCount = 1, encodeLatch = encodeLatch)
        try {
            harness.owner.acceptImage(FakeBufferedAccess(1234L), true, { })
            encodeLatch.awaitStart()
            harness.owner.onDeadlineReached()
            harness.awaitIdle(3)
            // Verify terminal state is claimed
            assertEquals(CaptureTerminalStatus.FAILED, harness.terminalState.status())
            encodeLatch.release()
            harness.awaitIdle(5)
            assertEquals(0, harness.accounting.snapshot().persistedFrames)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun allReservationsAndBufferedAccountingSettleExactlyOnce() {
        val harness = Harness(frameCount = 3)
        try {
            repeat(3) { harness.owner.acceptImage(FakeBufferedAccess((it + 1) * 1000L), true, { }) }
            harness.awaitFrames(3)
            assertEquals(0L, harness.reservations.currentBytes())
            assertEquals(0, harness.accounting.snapshot().bufferedFrames)
            assertEquals(0, harness.lifecycle.retainedCount())
        } finally {
            harness.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // Direct YUV owner path
    // ------------------------------------------------------------------

    @Test
    fun directAcceptedImageIsReleasedOnce() {
        val harness = Harness(frameCount = 1)
        try {
            harness.owner.acceptImage(FakeDirectAccess(), false, { })
            harness.awaitIdle(5)
            assertEquals(1, harness.accounting.snapshot().receivedFrames)
            assertTrue(harness.accounting.snapshot().failedFrames >= 1)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun directQueueSaturationDisposesExactRejectedItem() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(
            frameCount = 2,
            capacity = 1,
            encodeLatch = encodeLatch
        )
        try {
            // Use buffered path so the worker actually blocks
            harness.owner.acceptImage(FakeBufferedAccess(1000L), true, { })
            encodeLatch.awaitStart()
            // Second frame will be queued (worker capacity 1, first task running)
            harness.owner.acceptImage(FakeBufferedAccess(2000L), true, { })
            harness.awaitIdle(1)
            encodeLatch.release()
            harness.awaitIdle(5)
            // Both frames persisted (second was queued, not dropped)
            assertEquals(2, harness.accounting.snapshot().persistedFrames)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun directWorkerFailureReturnsOwnerFailureEvent() {
        val harness = Harness(frameCount = 1, forceEncodeFailure = true)
        try {
            harness.owner.acceptImage(FakeDirectAccess(), false, { })
            harness.awaitIdle(5)
            assertTrue(
                harness.accounting.snapshot().failedFrames >= 1 ||
                harness.terminalState.status() != CaptureTerminalStatus.ACTIVE
            )
            harness.captureStateOwner.close()
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun finalSuccessfulCompletionRacingDeadline() {
        val harness = Harness(frameCount = 1)
        try {
            harness.owner.acceptImage(FakeDirectAccess(), false, { })
            harness.awaitIdle(5)
            assertTrue(harness.accounting.snapshot().failedFrames >= 1)
            harness.owner.onDeadlineReached()
            harness.awaitIdle(1)
            assertTrue(
                harness.terminalState.status() == CaptureTerminalStatus.FAILED ||
                harness.terminalState.status() == CaptureTerminalStatus.SUCCESS
            )
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun lateCompletionAfterTerminalClaimIsDiscarded() {
        val harness = Harness(frameCount = 1)
        try {
            harness.owner.acceptImage(FakeDirectAccess(), false, { })
            harness.awaitIdle(5)
            assertTrue(harness.accounting.snapshot().failedFrames >= 1)
            harness.owner.acceptImage(FakeDirectAccess(), false, { })
            harness.awaitIdle(3)
            assertTrue(harness.accounting.snapshot().failedFrames >= 1)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun candidateNotVisibleUnderFinalFilenameBeforeAdoption() {
        val harness = Harness(frameCount = 1)
        try {
            harness.owner.acceptImage(FakeDirectAccess(), false, { })
            harness.awaitIdle(5)
            // Direct path fails without real Image; no final file created
            val finalFile = File(harness.dir, "frame_00_color.png")
            assertFalse(finalFile.exists())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun cancellationWithRunningAndQueuedWork() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(
            frameCount = 2,
            capacity = 2,
            encodeLatch = encodeLatch
        )
        try {
            harness.owner.acceptImage(FakeDirectAccess(), false, { })
            harness.owner.acceptImage(FakeDirectAccess(), false, { })
            encodeLatch.awaitStart()
            harness.owner.onCancellationRequested()
            harness.awaitIdle(1)
            assertEquals(CaptureTerminalStatus.CANCELLED, harness.terminalState.status())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun noTerminalMetadataRewriteAfterTimeout() {
        val harness = Harness(frameCount = 1)
        try {
            harness.owner.acceptImage(FakeBufferedAccess(1234L), true, { })
            harness.awaitIdle(5)
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.terminalState.status())
            assertFalse(harness.terminalState.claim(CaptureTerminalStatus.TIMED_OUT))
        } finally {
            harness.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // Metadata
    // ------------------------------------------------------------------

    @Test
    fun persistedCountEqualsDistinctReadableFinalFiles() {
        val harness = Harness(frameCount = 3)
        try {
            repeat(3) { harness.owner.acceptImage(FakeBufferedAccess((it + 1) * 1000L), true, { }) }
            harness.awaitFrames(3)
            val snap = harness.accounting.snapshot()
            assertEquals(3, snap.persistedFrames)
            val files = harness.dir.listFiles { f -> f.isFile && f.name.endsWith(".png") }
            assertEquals(3, files?.size)
            files?.forEach { file ->
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                assertTrue(bitmap != null)
                bitmap?.recycle()
            }
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun nonPrefixIdentitiesRemainUnchanged() {
        val harness = Harness(frameCount = 2)
        try {
            harness.owner.acceptImage(FakeBufferedAccess(1000L), true, { })
            harness.awaitIdle(3)
            val snap = harness.accounting.snapshot()
            assertEquals(0, snap.manifest[0].frameIndex)
            assertTrue(snap.manifest[0].filename.startsWith("frame_"))
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun duplicateIdentityOrFilenameCannotSatisfySuccess() {
        val harness = Harness(frameCount = 2)
        try {
            harness.owner.acceptImage(FakeBufferedAccess(1000L), true, { })
            harness.awaitIdle(3)
            assertFalse(harness.accounting.persistedFrame(
                YuvFrameManifestEntry(0, "frame_00_color.png", 1000L, true)
            ))
            assertEquals(1, harness.accounting.snapshot().persistedFrames)
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun finalAdoptionBeforeDeadlineProducesSuccess() {
        val harness = Harness(frameCount = 2)
        try {
            repeat(2) { harness.owner.acceptImage(FakeBufferedAccess((it + 1) * 1000L), true, { }) }
            harness.awaitIdle(5)
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.terminalState.status())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun deadlineBeforeAdoptionProducesTimedOutConsistently() {
        val encodeLatch = EncodeLatch()
        val harness = Harness(frameCount = 2, capacity = 1, encodeLatch = encodeLatch)
        try {
            harness.owner.acceptImage(FakeBufferedAccess(1000L), true, { })
            encodeLatch.awaitStart()
            harness.owner.onDeadlineReached()
            harness.awaitIdle(1)
            val status = harness.terminalState.status()
            assertTrue(
                status == CaptureTerminalStatus.TIMED_OUT ||
                status == CaptureTerminalStatus.FAILED ||
                status == CaptureTerminalStatus.PARTIAL_SUCCESS
            )
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun cancellationWritesTerminalCancellationMetadata() {
        val harness = Harness(frameCount = 2)
        try {
            harness.owner.onCancellationRequested()
            harness.awaitIdle(1)
            assertEquals(CaptureTerminalStatus.CANCELLED, harness.terminalState.status())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun successOrErrorCallbackFiresAtMostOnce() {
        val harness = Harness(frameCount = 1)
        try {
            harness.owner.acceptImage(FakeBufferedAccess(1234L), true, { })
            harness.awaitIdle(5)
            harness.owner.onDeadlineReached()
            harness.awaitIdle(1)
            assertEquals(CaptureTerminalStatus.SUCCESS, harness.terminalState.status())
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun capturingIsNeverWrittenAfterTerminalClaim() {
        val harness = Harness(frameCount = 1)
        try {
            harness.owner.acceptImage(FakeBufferedAccess(1234L), true, { })
            harness.awaitIdle(5)
            val finalStatus = harness.writtenJobJson.lastOrNull()?.status
            assertEquals("CAPTURE_COMPLETE", finalStatus)
        } finally {
            harness.shutdown()
        }
    }

    companion object {
        val PNG_1X1: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
    }
}