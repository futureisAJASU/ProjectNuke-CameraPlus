package com.projectnuke.keplernightlab

import android.media.FakeYuvImage
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Strategy-aware YUV FINAL verification (pre-physical closure Phase 1).
 *
 * Static-audit blocker: PACKED_YUV_V1 correctly wrote/committed .yuvpack
 * sources, but the terminal final verifier was hardcoded to the PNG signature,
 * so every packed job failed the owner's post-commit verification BEFORE the
 * background converter was ever reached. These tests prove:
 *  - packed sources pass ONLY the strategy-aware packed verifier (full durable
 *    truth: magic/version, structure, exact length, streamed SHA-256, stored
 *    frameIndex) - never extension checks, never PNG logic;
 *  - the PNG-only policy regression is caught end-to-end through the SAME
 *    YuvCaptureOwner post-commit gateway production uses;
 *  - the foreground verifier is BOUNDED-MEMORY streaming (no plane allocation;
 *    proven through the API/result shape, not heap heuristics);
 *  - PNG verification remains exactly [RealYuvFinalFileVerifier].
 */
@RunWith(RobolectricTestRunner::class)
class PackedYuvFinalVerificationTest {

    /** Minimal valid 1x1 PNG (signature-bearing) for PNG-policy verification. */
    private val PNG_1X1_BYTES: ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    )

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private fun frame(
        index: Int,
        width: Int = 8,
        height: Int = 6,
        ySizeOverride: Int? = null
    ): BufferedYuvFrame {
        val ySize = ySizeOverride ?: (width * height)
        val uvSize = (width / 2) * (height / 2)
        return BufferedYuvFrame(
            index = index,
            timestampNs = 1000L + index,
            width = width,
            height = height,
            y = ByteArray(ySize) { it.toByte() },
            u = ByteArray(uvSize) { (it + index).toByte() },
            v = ByteArray(uvSize) { (it * 3 + index).toByte() },
            yRowStride = width,
            yPixelStride = 1,
            uRowStride = width / 2,
            uPixelStride = 1,
            vRowStride = width / 2,
            vPixelStride = 1
        )
    }

    private fun packRealSource(index: Int, file: File): PackedYuvFrameStore.Header =
        PackedYuvFrameStore.pack(frame(index), rotationDegrees = 90, outFile = file).let {
            PackedYuvFrameStore.readHeader(file)
        }

    // ------------------------------------------------------------------
    // verifier-level semantics
    // ------------------------------------------------------------------

    @Test
    fun packedYuv_finalCommittedSource_passesStrategyAwareFinalVerifier() {
        val dir = Files.createTempDirectory("packed-final-ok").toFile()
        val packed = File(dir, "frame_03_color.yuvpack")
        packRealSource(3, packed)

        assertTrue(PackedYuvFinalFileVerifier.verify(packed, frameIndex = 3))
        assertFalse(PackedYuvFinalFileVerifier.verify(packed, frameIndex = 4))

        // The production selector resolves the PACKED strategy to this verifier.
        val selector = yuvTerminalFinalVerifierFor(YuvPersistenceStrategy.PACKED_YUV_V1)
        assertTrue(selector.verify(packed, frameIndex = 3))
    }

    @Test
    fun packedYuv_finalVerifier_rejectsPngOnlyPolicyRegression() {
        val dir = Files.createTempDirectory("packed-final-regression").toFile()
        val packed = File(dir, "frame_00_color.yuvpack")
        packRealSource(0, packed)

        // The OLD policy (PNG signature required) rejects a perfectly valid
        // packed source - this is exactly what broke every real packed capture.
        assertFalse(RealYuvFinalFileVerifier.verify(packed, frameIndex = 0))
        // And the PNG strategy keeps rejecting it (policy mismatch fails closed).
        assertFalse(yuvTerminalFinalVerifierFor(YuvPersistenceStrategy.PNG).verify(packed, 0))
    }

    @Test
    fun packedYuv_streamingVerifier_doesNotAllocatePayloadPlanes() {
        // Bounded-memory proof through the API/result SHAPE: verifyFullStreaming
        // returns ONLY Header metadata - a scalar-only type with NO array fields
        // - so no Y/U/V payload can be retained by construction. unpack()/verifyFull()
        // remain available for consumers that actually need planes.
        val headerFields = PackedYuvFrameStore.Header::class.java.declaredFields.map { it.type }
        assertFalse(headerFields.contains(ByteArray::class.java))
        assertTrue(headerFields.contains(String::class.java)) // digest hex only

        val dir = Files.createTempDirectory("packed-streaming-shape").toFile()
        // Payload larger than one 256KB copy buffer chunk proves the streaming
        // loop (multi-chunk single pass), while the result still carries no planes.
        val wide = frame(index = 9, width = 4096, height = 74, ySizeOverride = 300_000)
        val expectedPayload = wide.y.size.toLong() + wide.u.size.toLong() + wide.v.size.toLong()
        val packed = File(dir, "frame_09_color.yuvpack")
        PackedYuvFrameStore.pack(wide, rotationDegrees = 0, outFile = packed)
        assertEquals(expectedPayload, packed.length() - 12L - headerJsonLength(packed))

        val header = PackedYuvFrameStore.verifyFullStreaming(packed)
        assertEquals(9, header.frameIndex)
        assertEquals(expectedPayload, header.payloadLength)
        // Identical durable truth to the allocating verifier.
        assertEquals(PackedYuvFrameStore.verifyFull(packed).header.payloadDigest, header.payloadDigest)
    }

    private fun headerJsonLength(packed: File): Long {
        java.io.RandomAccessFile(packed, "r").use { raf ->
            raf.seek(8)
            return readLittleEndianInt(raf).toLong()
        }
    }

    private fun readLittleEndianInt(raf: java.io.RandomAccessFile): Int {
        var value = 0
        repeat(4) { shift -> value = value or ((raf.read().and(0xFF)) shl (shift * 8)) }
        return value
    }

    @Test
    fun packedYuv_streamingVerifier_rejectsDigestCorruption() {
        val dir = Files.createTempDirectory("packed-streaming-digest").toFile()
        val packed = File(dir, "frame_01_color.yuvpack")
        packRealSource(1, packed)

        val bytes = packed.readBytes()
        bytes[bytes.size - 8] = (bytes[bytes.size - 8].toInt() xor 0x01).toByte()
        packed.writeBytes(bytes)

        try {
            PackedYuvFrameStore.verifyFullStreaming(packed)
            throw AssertionError("corrupted payload must fail full streaming verification")
        } catch (_: Exception) {
            // expected fail-closed
        }
        assertFalse(PackedYuvFinalFileVerifier.verify(packed, frameIndex = 1))
    }

    @Test
    fun packedYuv_streamingVerifier_rejectsTruncation() {
        val dir = Files.createTempDirectory("packed-streaming-trunc").toFile()
        val packed = File(dir, "frame_02_color.yuvpack")
        packRealSource(2, packed)

        packed.writeBytes(packed.readBytes().copyOfRange(0, packed.length().toInt() - 10))
        try {
            PackedYuvFrameStore.verifyFullStreaming(packed)
            throw AssertionError("truncated container must fail full streaming verification")
        } catch (_: Exception) {
            // expected fail-closed
        }
        assertFalse(PackedYuvFinalFileVerifier.verify(packed, frameIndex = 2))
    }

    @Test
    fun pngStrategy_stillUsesPngSignatureVerifier() {
        val dir = Files.createTempDirectory("png-final").toFile()
        val png = File(dir, "frame_00_color.png")
        png.writeBytes(PNG_1X1_BYTES)
        val packed = File(dir, "frame_01_color.yuvpack")
        packRealSource(1, packed)

        val pngSelector = yuvTerminalFinalVerifierFor(YuvPersistenceStrategy.PNG)
        // Identical behavior to the untouched production PNG verifier.
        assertEquals(RealYuvFinalFileVerifier.verify(png, 0), pngSelector.verify(png, 0))
        assertTrue(pngSelector.verify(png, 0))
        assertFalse(pngSelector.verify(packed, 1))
    }

    // ------------------------------------------------------------------
    // OWNER/SESSION integration through the PRODUCTION post-commit gateway
    // ------------------------------------------------------------------

    private class PackedHarness(
        val frameCount: Int,
        private val strategy: YuvPersistenceStrategy,
        /** Simulates the PRE-FIX bug: packed content under the PNG-only policy. */
        private val legacyPngPolicyMismatch: Boolean = false
    ) {
        val dir: File = Files.createTempDirectory("packed-owner-int").toFile()
        val handlerThread = HandlerThread("packed-owner-int").apply { start() }
        val handler: Handler = Handler(handlerThread.looper)

        val terminalLatch = CountDownLatch(1)
        val completionLatch = CountDownLatch(1)
        val errorLatch = CountDownLatch(1)
        val completeCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val lastErrorMessage = AtomicReference<String?>(null)
        val lastStatus = AtomicReference<String?>(null)
        val lastPersistedFrames = AtomicInteger(-1)
        val lastManifestSize = AtomicInteger(-1)

        val session: YuvCaptureSession = YuvCaptureSession.create(
            dispatch = { event ->
                handler.post { event.execute() }
                true
            },
            outputDir = dir,
            frameCount = frameCount,
            rotationDegrees = 0,
            workerCapacity = 4,
            maxRetainedBytes = 64L * 1024L * 1024L,
            workProcessor = YuvPngWorkProcessor(
                encoder = object : YuvPngEncoder {
                    override fun encodeDirect(image: android.media.Image, candidate: File, rotationDegrees: Int) {
                        throw IllegalStateException("packed A/B is buffered-pipeline only")
                    }

                    override fun encodeBuffered(frame: BufferedYuvFrame, candidate: File, rotationDegrees: Int) {
                        // The EXACT production packed persistence shape.
                        PackedYuvFrameStore.pack(frame, rotationDegrees, candidate)
                    }
                },
                committer = YuvCandidateCommitter { candidate, final ->
                    KeplerJobMetadata.atomicReplace(candidate, final)
                }
            ),
            sourceFrameExtension = strategy.name,
            // THE FIX UNDER TEST: capture-level immutable strategy selects the
            // terminal final verifier at session construction.
            terminalFinalVerifier = if (legacyPngPolicyMismatch) {
                // Pre-fix wiring: PNG signature demanded regardless of strategy.
                YuvTerminalFinalVerifier { file, frameIndex ->
                    RealYuvFinalFileVerifier.verify(file, frameIndex)
                }
            } else {
                yuvTerminalFinalVerifierFor(strategy)
            },
            dispatchCallback = CallbackDispatcher { runnable ->
                if (!handler.post(runnable)) runnable.run()
                true
            },
            writeJobJson = { status, _, manifest, _, persistedFrames, _, _, _, _, _, _, _, _, _, _, _, _, _ ->
                lastStatus.set(status)
                lastPersistedFrames.set(persistedFrames)
                lastManifestSize.set(manifest.size)
                if (status in TERMINAL_STATUSES) {
                    handler.post { terminalLatch.countDown() }
                }
            },
            onCaptureComplete = { _ ->
                completeCount.incrementAndGet()
                completionLatch.countDown()
            },
            onCaptureError = { message, _ ->
                errorCount.incrementAndGet()
                lastErrorMessage.set(message)
                errorLatch.countDown()
            },
            productionResourceCoordinator = YuvProductionResourceCoordinator(
                timeoutScheduler = null,
                backgroundHandler = null,
                backgroundThread = null
            )
        )

        fun awaitTerminal(timeoutSec: Long = 10): CaptureTerminalStatus {
            assertTrue("terminal not reached", terminalLatch.await(timeoutSec, TimeUnit.SECONDS))
            flushHandler()
            return session.terminalState.status()
        }

        fun flushHandler() {
            val drain = CountDownLatch(1)
            handler.post { drain.countDown() }
            assertTrue(drain.await(5, TimeUnit.SECONDS))
        }

        fun shutdown() {
            session.close()
            handlerThread.quitSafely()
        }

        companion object {
            private val TERMINAL_STATUSES = setOf(
                "CAPTURE_COMPLETE",
                "CAPTURE_PARTIAL",
                "CAPTURE_FAILED",
                "CAPTURE_TIMEOUT",
                "CAPTURE_CANCELLED"
            )
        }
    }

    @Test
    fun packedYuv_ownerIntegration_strategyAwareVerification_reachesStrictSuccessAndHandoff() {
        val harness = PackedHarness(frameCount = 2, strategy = YuvPersistenceStrategy.PACKED_YUV_V1)
        try {
            repeat(2) { index ->
                harness.session.owner.acceptBuffered(Camera2YuvImageAccess(FakeYuvImage(timestamp = 1000L + index)))
            }

            assertEquals(CaptureTerminalStatus.SUCCESS, harness.awaitTerminal())
            harness.flushHandler()

            // Worker success -> candidate commit -> STRATEGY-AWARE final
            // verification -> persistedFrames increments -> strict SUCCESS.
            assertEquals("CAPTURE_COMPLETE", harness.lastStatus.get())
            assertTrue("completion callback not dispatched", harness.completionLatch.await(10, TimeUnit.SECONDS))
            harness.flushHandler()
            assertEquals(2, harness.lastPersistedFrames.get())
            assertEquals(2, harness.lastManifestSize.get())
            assertEquals(1, harness.completeCount.get())
            assertEquals(0, harness.errorCount.get())

            // Every committed final source is a REAL packed artifact that still
            // passes FULL durable truth (background conversion can proceed).
            repeat(2) { index ->
                val final = File(harness.dir, yuvFrameFileName(index, YuvPersistenceStrategy.PACKED_YUV_V1))
                assertTrue("final packed source missing: $final", final.isFile)
                assertEquals(index, PackedYuvFrameStore.verifyFull(final).frameIndex)
            }
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun packedYuv_ownerIntegration_pngOnlyPolicy_failsClosedBeforeConverter() {
        // Pre-fix production shape: packed content committed under the PNG-only
        // verifier. The owner's post-commit gateway MUST fail the capture.
        val harness = PackedHarness(
            frameCount = 1,
            strategy = YuvPersistenceStrategy.PNG,
            legacyPngPolicyMismatch = true
        )
        try {
            harness.session.owner.acceptBuffered(Camera2YuvImageAccess(FakeYuvImage(timestamp = 42L)))

            assertEquals(CaptureTerminalStatus.FAILED, harness.awaitTerminal())
            assertTrue("error callback not dispatched", harness.errorLatch.await(10, TimeUnit.SECONDS))
            harness.flushHandler()
            assertNotNull(harness.lastErrorMessage.get())
            assertTrue(
                "expected final-verification failure, got: ${harness.lastErrorMessage.get()}",
                harness.lastErrorMessage.get()!!.contains("Final file verification failed")
            )
            // The unverified frame NEVER committed: zero persisted frames.
            assertEquals(0, harness.lastPersistedFrames.get())
            assertEquals(0, harness.completeCount.get())
        } finally {
            harness.shutdown()
        }
    }
}
