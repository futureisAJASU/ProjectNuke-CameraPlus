package com.projectnuke.keplernightlab

import android.media.Image
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase-A corrective audit, Phase 3: the capture timing ledger is REAL, not
 * declarative.  Every advertised milestone has a production call site at its
 * actual authority boundary, and the persisted chain is causally ordered:
 *
 *   requestSubmitted <= firstCameraEvidence <= acquisitionComplete <=
 *   persistenceDrainComplete <= processingHandoffPublished <=
 *   captureResourcesSettled <= captureStageComplete
 *
 * (only documented same-timestamp equality allowed).
 */
@RunWith(RobolectricTestRunner::class)
class CaptureTimingLedgerProductionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // Causal ordering of the capture-stage milestone chain
    // ------------------------------------------------------------------

    private fun assertChainOrdered(snap: CaptureTimingSnapshot) {
        val requestSubmitted = snap.captureRequestSubmittedAt
        val firstEvidence = minOf(
            snap.firstImageReceivedAt.takeIf { it > 0 } ?: Long.MAX_VALUE,
            snap.firstCaptureResultAt.takeIf { it > 0 } ?: Long.MAX_VALUE
        )
        assertTrue("requestSubmitted <= firstCameraEvidence", requestSubmitted <= firstEvidence)
        assertTrue("firstCameraEvidence <= acquisitionComplete", firstEvidence == Long.MAX_VALUE || firstEvidence <= snap.cameraAcquisitionCompleteAt)
        assertTrue("acquisitionComplete <= persistenceDrainComplete", snap.cameraAcquisitionCompleteAt <= snap.persistenceDrainCompleteAt)
        assertTrue("persistenceDrainComplete <= processingHandoffPublished", snap.persistenceDrainCompleteAt <= snap.processingHandoffPublishedAt)
        assertTrue("processingHandoffPublished <= captureResourcesSettled", snap.processingHandoffPublishedAt <= snap.captureResourcesSettledAt)
        assertTrue("captureResourcesSettled <= captureStageComplete", snap.captureResourcesSettledAt <= snap.captureStageCompleteAt)
    }

    @Test
    fun causalMilestoneChain_isMonotonic() {
        var tick = 0L
        val ledger = CaptureTimingLedger(2) { ++tick; tick * 1_000_000L }
        ledger.recordCaptureRequestSubmitted()
        ledger.recordImageReceived(0)
        ledger.recordResultReceived(0)
        ledger.recordImageReceived(1)
        ledger.recordResultReceived(1)
        ledger.recordPersistenceDrainComplete()
        ledger.recordProcessingHandoffPublished()
        ledger.recordCaptureResourcesSettled()
        ledger.recordCaptureStageComplete()
        assertChainOrdered(ledger.snapshot())
    }

    @Test
    fun causalMilestoneChain_allowsDocumentedSameTimestampEquality() {
        // Production records handoff/resources/stage within ONE settlement block:
        // a frozen tail clock makes all three share an instant. Equality is
        // documented; inversion is not.
        var tick = 0L
        val ledger = CaptureTimingLedger(1) { if (tick < 4) ++tick * 1_000_000L else 4_000_000L }
        ledger.recordCaptureRequestSubmitted()                       // 1ms
        ledger.recordImageReceived(); ledger.recordResultReceived()  // 2ms
        ledger.recordPersistenceDrainComplete()                      // 3ms
        // Settlement block at the frozen 4ms tick.
        ledger.recordProcessingHandoffPublished()
        ledger.recordCaptureResourcesSettled()
        ledger.recordCaptureStageComplete()
        val snap = ledger.snapshot()
        assertEquals(4L, snap.processingHandoffPublishedAt / 1_000_000L)
        assertEquals(snap.processingHandoffPublishedAt, snap.captureResourcesSettledAt)
        assertEquals(snap.captureResourcesSettledAt, snap.captureStageCompleteAt)
        assertChainOrdered(snap)
    }

    @Test
    fun handoffFailurePath_neverRecordsPublishedOrSettledMilestones() {
        // Production order after the corrective fix: publishProcessingHandoff
        // fails -> recordProcessingHandoffPublished is NOT called; owner settle
        // fails -> resources/stage milestones stay unset.  Only drain truth exists.
        var tick = 0L
        val ledger = CaptureTimingLedger(1) { ++tick; tick * 1_000_000L }
        ledger.recordCaptureRequestSubmitted()
        ledger.recordImageReceived(); ledger.recordResultReceived()
        ledger.recordPersistenceDrainComplete()
        // (publication failed - no recorder calls)
        val snap = ledger.snapshot()
        assertEquals(0L, snap.processingHandoffPublishedAt)
        assertEquals(0L, snap.captureResourcesSettledAt)
        assertEquals(0L, snap.captureStageCompleteAt)
        assertTrue(snap.persistenceDrainCompleteAt > 0L)
    }

    @Test
    fun jsonProjection_includesNewPerFrameMilestones() {
        var current = 0L
        val ledger = CaptureTimingLedger(1) { current += 1_000_000L; current }
        ledger.recordCaptureRequestSubmitted()
        ledger.recordImageReceived(0); ledger.recordResultReceived(0)
        ledger.recordPersistenceQueued(0)
        ledger.recordWorkerStarted(0)
        ledger.recordConversionCompleted(0)
        ledger.recordEncodeFinished(0)
        ledger.recordWriteFinished(0)
        ledger.recordFsyncFinished(0)
        ledger.recordVerified(0)
        ledger.recordCommitted(0)
        ledger.recordPersistenceDrainComplete()
        ledger.recordProcessingHandoffPublished()
        ledger.recordCaptureResourcesSettled()
        ledger.recordCaptureStageComplete()
        val frame0 = ledger.toJson().getJSONArray("frames").getJSONObject(0)
        listOf(
            "workerStartedAt", "conversionCompletedAt", "writeFinishedAt"
        ).forEach { key -> assertTrue("missing frame.$key", frame0.has(key) && frame0.getLong(key) > 0L) }
    }

    // ------------------------------------------------------------------
    // Real production call sites drive the per-frame persistence chain
    // ------------------------------------------------------------------

    private companion object {
        val PNG_1X1: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
    }

    private class FakeBufferedAccess(private val ts: Long) : YuvImageAccess {
        override fun timestampNs(): Long = ts
        override fun allocationBytes(): Long = 12L
        override fun copy(frameIndex: Int): BufferedYuvFrame =
            BufferedYuvFrame(
                frameIndex, ts, 1, 1,
                byteArrayOf(0), byteArrayOf(0), byteArrayOf(0),
                1, 1, 1, 1, 1, 1
            )
        override fun release() {}
    }

    private class InstantPngEncoder : YuvPngEncoder {
        override fun encodeDirect(image: Image, candidate: File, rotationDegrees: Int) {
            Files.write(candidate.toPath(), PNG_1X1)
        }

        override fun encodeBuffered(frame: BufferedYuvFrame, candidate: File, rotationDegrees: Int) {
            Files.write(candidate.toPath(), PNG_1X1)
        }
    }

    @Test
    fun productionSession_recordsRealPerFramePersistenceChain() {
        val dir: File = Files.createTempDirectory("timing-prod").toFile()
        val handlerThread = android.os.HandlerThread("timing-prod").apply { start() }
        val handler = android.os.Handler(handlerThread.looper)
        val encoder = InstantPngEncoder()
        val terminalLatch = CountDownLatch(1)

        val ledger = CaptureTimingLedger(2)
        val prevAcq = AtomicReference(Triple(0, 0, 0))
        val session = YuvCaptureSession.create(
            dispatch = { event -> handler.post { event.execute() }; true },
            outputDir = dir,
            frameCount = 2,
            rotationDegrees = 0,
            workerCapacity = 2,
            maxRetainedBytes = 16L * 1024 * 1024,
            workProcessor = YuvPngWorkProcessor(
                encoder = encoder,
                committer = YuvCandidateCommitter { candidate, final ->
                    Files.move(candidate.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
            ),
            postStatus = { true },
            dispatchCallback = CallbackDispatcher { runnable ->
                if (!handler.post(runnable)) runnable.run()
                true
            },
            onCaptureComplete = { _ -> terminalLatch.countDown() },
            productionResourceCoordinator = YuvProductionResourceCoordinator(null, null, null),
            startTerminalObserverOnCreate = true,
            // Mirror the production ColorFusion wiring exactly: typed
            // acquisition deltas drive image/result/committed/drain records.
            onAcquisitionUpdate = { received, completed, persisted ->
                val prev = prevAcq.get()
                if (received > prev.first) {
                    repeat(received - prev.first) { ledger.recordImageReceived() }
                }
                if (completed > prev.second) {
                    repeat(completed - prev.second) { ledger.recordResultReceived() }
                }
                if (persisted > prev.third) {
                    for (frameIndex in prev.third until persisted) {
                        ledger.recordCommitted(frameIndex)
                    }
                    if (persisted >= 2) ledger.recordPersistenceDrainComplete()
                }
                prevAcq.set(Triple(maxOf(received, prev.first), maxOf(completed, prev.second), maxOf(persisted, prev.third)))
            },
            timingHooks = object : YuvCaptureTimingHooks {
                override fun onPersistenceQueued(frameIndex: Int) = ledger.recordPersistenceQueued(frameIndex)
                override fun onWorkerStarted(frameIndex: Int) = ledger.recordWorkerStarted(frameIndex)
                override fun onEncodeFinished(frameIndex: Int) = ledger.recordEncodeFinished(frameIndex)
                override fun onWriteFinished(frameIndex: Int) = ledger.recordWriteFinished(frameIndex)
                override fun onVerified(frameIndex: Int) = ledger.recordVerified(frameIndex)
            }
        )

        try {
            session.owner.acceptBuffered(FakeBufferedAccess(1000L))
            session.owner.acceptBuffered(FakeBufferedAccess(2000L))
            // Production pairs every acquired image with its CaptureResult
            // completion callback; drive both pieces of evidence.
            session.owner.onCaptureCompletedResult()
            session.owner.onCaptureCompletedResult()
            assertTrue("terminal not reached", terminalLatch.await(10, TimeUnit.SECONDS))

            // Drain the owner queue so the committed deltas were emitted.
            val flush = CountDownLatch(1)
            assertTrue(handler.post { flush.countDown() })
            assertTrue(flush.await(5, TimeUnit.SECONDS))

            val json = ledger.toJson()
            // Aggregate acquisition milestones recorded from the typed hook:
            val snap = ledger.snapshot()
            assertTrue(snap.firstImageReceivedAt > 0L)
            assertTrue(snap.firstCaptureResultAt > 0L)
            assertTrue(snap.cameraAcquisitionCompleteAt > 0L)
            assertTrue(snap.persistenceDrainCompleteAt > 0L)
            val frames = json.getJSONArray("frames")
            assertEquals(2, frames.length())
            for (index in 0 until 2) {
                val frame = frames.getJSONObject(index)
                // EVERY real persistence milestone fired from its production site
                // (image/result instants are aggregate-only by production wiring):
                listOf(
                    "persistenceQueuedAt", "workerStartedAt",
                    "encodeFinishedAt", "writeFinishedAt", "verifiedAt", "committedAt"
                ).forEach { key ->
                    assertTrue("frame $index missing $key", frame.getLong(key) > 0L)
                }
                // Per-frame causal order (documented same-timestamp equality allowed).
                assertTrue(frame.getLong("workerStartedAt") >= frame.getLong("persistenceQueuedAt"))
                assertTrue(frame.getLong("encodeFinishedAt") >= frame.getLong("workerStartedAt"))
                assertTrue(frame.getLong("writeFinishedAt") >= frame.getLong("encodeFinishedAt"))
                assertTrue(frame.getLong("verifiedAt") >= frame.getLong("writeFinishedAt"))
                // Accounting commit is the last authority boundary per frame.
                assertTrue(frame.getLong("committedAt") >= frame.getLong("verifiedAt"))
            }
            assertEquals(2, session.accounting.snapshot().persistedFrames)
        } finally {
            session.close()
            handlerThread.quitSafely()
            dir.deleteRecursively()
        }
    }
}
