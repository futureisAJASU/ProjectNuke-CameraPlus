package com.projectnuke.keplernightlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 1: RAW timing-authority causality tests. Every milestone below mirrors a
 * REAL production call site in RawFusionCapture (image/result arrival ordinals,
 * save-worker persistence milestones, terminal settlement boundaries). The
 * injected clock makes every derived duration exact.
 */
@RunWith(RobolectricTestRunner::class)
class RawCaptureTimingLedgerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun ms(value: Long): Long = value * 1_000_000L

    /** Drives a truthful 4-frame RAW sequence on an explicit clock. */
    private class RawSequence {
        var nowMs = 100L
        val ledger = CaptureTimingLedger(4) { ms(nowMs) }

        private fun ms(value: Long): Long = value * 1_000_000L

        fun submitRequest(at: Long) { nowMs = at; ledger.recordCaptureRequestSubmitted() }
        fun image(ordinal: Int, at: Long) { nowMs = at; ledger.recordImageReceived(ordinal) }
        fun result(ordinal: Int, at: Long) { nowMs = at; ledger.recordResultReceived(ordinal) }

        fun persistFrame(
            frameIndex: Int,
            workerStartedAt: Long,
            fsyncAt: Long,
            writeFinishedAt: Long,
            verifiedAt: Long,
            committedAt: Long,
            bytes: Long,
            writeMs: Long,
            syncMs: Long
        ) {
            nowMs = workerStartedAt; ledger.recordWorkerStarted(frameIndex)
            nowMs = fsyncAt; ledger.recordFsyncFinished(frameIndex)
            nowMs = writeFinishedAt; ledger.recordWriteFinished(frameIndex)
            nowMs = verifiedAt; ledger.recordVerified(frameIndex)
            ledger.recordRawFrameWriteStats(frameIndex, bytes, writeMs)
            ledger.recordRawFrameSyncStats(syncMs)
            nowMs = committedAt; ledger.recordCommitted(frameIndex)
        }

        fun drain(at: Long) { nowMs = at; ledger.recordPersistenceDrainComplete() }
        fun handoff(at: Long) { nowMs = at; ledger.recordProcessingHandoffPublished() }
        fun settled(at: Long) { nowMs = at; ledger.recordCaptureResourcesSettled() }
        fun stageComplete(at: Long) { nowMs = at; ledger.recordCaptureStageComplete() }
    }

    private fun fourFrameSequence(): RawSequence = RawSequence().apply {
        submitRequest(100)
        // Interleaved Camera2 arrival order: images and results alternate.
        image(0, 110); result(0, 112)
        image(1, 120); result(1, 122)
        image(2, 130); result(2, 132)
        image(3, 140); result(3, 142)
        // Persistence runs after each frame's arrival, last commit well past 100%.
        persistFrame(0, 115, 150, 160, 165, 170, bytes = 25_000_000L, writeMs = 30, syncMs = 8)
        persistFrame(1, 125, 180, 190, 195, 200, bytes = 25_000_000L, writeMs = 28, syncMs = 7)
        persistFrame(2, 135, 210, 220, 225, 230, bytes = 25_000_000L, writeMs = 29, syncMs = 9)
        persistFrame(3, 145, 240, 250, 255, 260, bytes = 25_000_000L, writeMs = 31, syncMs = 6)
        drain(270)
        handoff(280)
        settled(285)
        stageComplete(290)
    }

    @Test
    fun rawTiming_cameraAcquisitionBoundaryIsIndependentOfPersistence() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            image(0, 110); result(0, 112)
            image(1, 120); result(1, 122)
            image(2, 130); result(2, 132)
            image(3, 140); result(3, 142)
        }
        val before = sequence.ledger.snapshot()
        assertEquals(ms(142), before.cameraAcquisitionCompleteAt)
        assertEquals(42L, before.cameraAcquisitionMs)

        // All persistence evidence arrives AFTER the acquisition boundary and
        // must not move the sensor-acquisition truth by even one nanosecond.
        sequence.persistFrame(0, 150, 160, 170, 175, 180, 25_000_000L, 30, 8)
        sequence.persistFrame(1, 155, 185, 195, 200, 205, 25_000_000L, 28, 7)
        sequence.persistFrame(2, 160, 210, 220, 225, 230, 25_000_000L, 29, 9)
        sequence.persistFrame(3, 165, 235, 245, 250, 255, 25_000_000L, 31, 6)
        sequence.drain(265)

        val after = sequence.ledger.snapshot()
        assertEquals(before.cameraAcquisitionCompleteAt, after.cameraAcquisitionCompleteAt)
        assertEquals(42L, after.cameraAcquisitionMs)
        assertEquals(4, after.requestedFrames)
    }

    @Test
    fun rawTiming_persistenceOccursAfterOrDuringAcquisitionTruthfully() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            image(0, 110); result(0, 112)
            // Frame 0 persistence starts while frames 1..3 are still acquiring.
            persistFrame(0, 114, 130, 140, 145, 150, 25_000_000L, 20, 5)
            image(1, 120); result(1, 122)
            image(2, 130); result(2, 132)
            image(3, 140); result(3, 142)
        }
        val snap = sequence.ledger.snapshot()
        // Persistence for frame 0 overlapped acquisition; the boundary is still
        // the LAST pair completion, never the first persisted frame.
        assertEquals(ms(142), snap.cameraAcquisitionCompleteAt)
        // Per-frame persistence milestones are recorded with real frame identity.
        val json = sequence.ledger.toJson()
        val frames = json.getJSONArray("frames")
        assertEquals(4, frames.length())
        val frame0 = frames.getJSONObject(0)
        assertEquals(ms(114), frame0.getLong("workerStartedAt"))
        assertEquals(ms(130), frame0.getLong("fsyncFinishedAt"))
        assertEquals(ms(140), frame0.getLong("writeFinishedAt"))
        assertTrue(frame0.getLong("committedAt") >= frame0.getLong("workerStartedAt"))
        // Arrival-ordinal acquisition instants are bounded to requestedFrames slots.
        assertEquals(ms(110), frames.getJSONObject(0).getLong("imageAt"))
        assertEquals(ms(142), frames.getJSONObject(3).getLong("resultAt"))
    }

    @Test
    fun rawTiming_handoffRecordedAfterRequiredDurability() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index ->
                image(index, 110 + index * 10L)
                result(index, 112 + index * 10L)
                persistFrame(
                    index,
                    workerStartedAt = 115 + index * 40L,
                    fsyncAt = 130 + index * 40L,
                    writeFinishedAt = 140 + index * 40L,
                    verifiedAt = 145 + index * 40L,
                    committedAt = 150 + index * 40L,
                    bytes = 25_000_000L,
                    writeMs = 25,
                    syncMs = 5
                )
            }
        }
        val preHandoff = sequence.ledger.snapshot()
        assertEquals(0L, preHandoff.processingHandoffPublishedAt)
        assertEquals(0L, preHandoff.handoffPublicationMs)

        sequence.drain(275)
        sequence.handoff(300)

        val snap = sequence.ledger.snapshot()
        // Handoff publication happens strictly AFTER the final durable commit.
        assertEquals(ms(270), snap.lastFrameCommittedAt)
        assertTrue(snap.processingHandoffPublishedAt > snap.lastFrameCommittedAt)
        assertEquals(25L, snap.handoffPublicationMs)
        // Terminal metadata settlement spans last-commit -> handoff publication.
        assertEquals(30L, snap.rawMetadataSettlementMs)
        assertEquals(100_000_000L, snap.rawBytesPersisted)
        assertEquals(100L, snap.rawPersistenceWriteMs)
        assertEquals(20L, snap.rawPersistenceSyncMs)
    }

    @Test
    fun rawTiming_captureStageCompleteIsFinalForegroundBoundary() {
        val sequence = fourFrameSequence()
        val snap = sequence.ledger.snapshot()
        // captureStageComplete is the LAST foreground boundary in causal order.
        assertTrue(snap.captureStageCompleteAt >= snap.captureResourcesSettledAt)
        assertTrue(snap.captureResourcesSettledAt >= snap.processingHandoffPublishedAt)
        assertTrue(snap.processingHandoffPublishedAt >= snap.persistenceDrainCompleteAt)
        assertTrue(snap.persistenceDrainCompleteAt >= snap.cameraAcquisitionCompleteAt)
        // postAcquisitionToShutterMs spans 100% -> shutter admission exactly.
        assertEquals(148L, snap.postAcquisitionToShutterMs)
        assertEquals(snap.captureStageTotalMs - snap.cameraAcquisitionMs, snap.postAcquisitionToShutterMs)
        assertEquals(128L, snap.persistenceDrainMs)
        assertEquals(128L, snap.postAcquisitionPersistenceMs)
        assertEquals(10L, snap.handoffPublicationMs)
        assertEquals(5L, snap.captureSettlementMs)
    }

    @Test
    fun rawPersistence_fourFramesStrictlyDurableBeforeHandoff() {
        // Handoff publication is only truthful after EVERY frame reached its
        // durable commit boundary; the ledger must expose that ordering.
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index ->
                image(index, 110 + index * 10L)
                result(index, 112 + index * 10L)
            }
            persistFrame(0, 120, 130, 140, 145, 150, 25_000_000L, 20, 5)
            persistFrame(1, 155, 165, 175, 180, 185, 25_000_000L, 20, 5)
            persistFrame(2, 190, 200, 210, 215, 220, 25_000_000L, 20, 5)
            // Frame 3 NOT yet committed: drain/handoff would be a lie here.
        }
        sequence.drain(240)
        val premature = sequence.ledger.snapshot()
        // Only three frames committed so far; handoff must still be unset.
        assertEquals(ms(220), premature.lastFrameCommittedAt)
        assertEquals(0L, premature.processingHandoffPublishedAt)

        sequence.persistFrame(3, 245, 255, 265, 270, 275, 25_000_000L, 20, 5)
        sequence.handoff(300)

        val snap = sequence.ledger.snapshot()
        assertEquals(ms(275), snap.lastFrameCommittedAt)
        assertTrue(snap.processingHandoffPublishedAt >= snap.lastFrameCommittedAt)
        assertEquals(ms(300), snap.processingHandoffPublishedAt)
    }

    @Test
    fun timingSnapshot_roundTripsNonDefaultRawFields() {
        val sequence = fourFrameSequence()
        val json = sequence.ledger.toJson()
        assertEquals(100_000_000L, json.getLong("rawBytesPersisted"))
        assertEquals(118L, json.getLong("rawPersistenceWriteMs"))
        assertEquals(30L, json.getLong("rawPersistenceSyncMs"))
        assertEquals(148L, json.getLong("postAcquisitionToShutterMs"))

        val timing = HardwareE2ECaptureTiming.fromCaptureTimingJson(json)
        assertEquals(100_000_000L, timing.rawBytesPersisted)
        assertEquals(118L, timing.rawPersistenceWriteMs)
        assertEquals(30L, timing.rawPersistenceSyncMs)
        assertEquals(148L, timing.postAcquisitionToShutterMs)
        assertEquals(128L, timing.postAcquisitionPersistenceMs)
        assertEquals(10L, timing.handoffPublicationMs)
        assertEquals(5L, timing.captureSettlementMs)
        assertEquals(20L, timing.postAcquisitionVerifyOverlapMs)
        // lastCommit 260 -> handoff 280
        assertEquals(20L, timing.rawMetadataSettlementMs)
        // JSON round-trip keeps every non-default raw field.
        val decoded = HardwareE2ECaptureTiming.fromJson(timing.toJson())
        assertEquals(timing, decoded)

        // Persisted capture_timing.json carries the same keys next to job.json.
        val jobDir = tmp.newFolder("raw-timing-job")
        java.io.File(jobDir, JOB_JSON_FILE_NAME)
            .writeText(JSONObject().put("requestedFrames", 4).toString())
        assertTrue(CaptureTimingLedger.persist(jobDir, sequence.ledger))
        val persisted = JSONObject(java.io.File(jobDir, CaptureTimingLedger.FILE_NAME).readText())
        assertEquals(100_000_000L, persisted.getLong("rawBytesPersisted"))
        assertEquals(
            100_000_000L,
            KeplerJobMetadata.read(jobDir).getJSONObject("captureTiming").getLong("rawBytesPersisted")
        )
    }

    @Test
    fun postAcquisitionVerifyOverlap_verifyEntirelyBeforeAcquisition_returnsZero() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index ->
                image(index, 110 + index * 10L)
                result(index, 112 + index * 10L)
            }
            persistFrame(0, 115, 130, 130, 135, 140, 25_000_000L, 20, 5)
            drain(300)
            handoff(310)
            settled(315)
            stageComplete(320)
        }
        // verifyAt=135, acquisitionComplete=142, drainComplete=300
        // overlap = min(135,300) - max(130,142) = 135 - 142 = -7 -> 0
        assertEquals(0L, sequence.ledger.postAcquisitionVerifyOverlapMs())
    }

    @Test
    fun postAcquisitionVerifyOverlap_verifyEntirelyAfterAcquisition_fullDuration() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index ->
                image(index, 110 + index * 10L)
                result(index, 112 + index * 10L)
            }
            persistFrame(0, 115, 130, 160, 170, 180, 25_000_000L, 20, 5)
            persistFrame(1, 155, 165, 190, 200, 210, 25_000_000L, 20, 5)
            persistFrame(2, 195, 205, 220, 230, 240, 25_000_000L, 20, 5)
            persistFrame(3, 235, 245, 250, 260, 270, 25_000_000L, 20, 5)
            drain(300)
            handoff(310)
            settled(315)
            stageComplete(320)
        }
        // frame0: verifyAt=170, acquisitionComplete=142, drainComplete=300 -> 170-160=10
        // frame1: verifyAt=200, acquisitionComplete=142, drainComplete=300 -> 200-190=10
        // frame2: verifyAt=230, acquisitionComplete=142, drainComplete=300 -> 230-220=10
        // frame3: verifyAt=260, acquisitionComplete=142, drainComplete=300 -> 260-250=10
        // total = 40ms
        assertEquals(40L, sequence.ledger.postAcquisitionVerifyOverlapMs())
    }

    @Test
    fun postAcquisitionVerifyOverlap_straddlesAcquisition_clippedDuration() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index ->
                image(index, 110 + index * 10L)
                result(index, 112 + index * 10L)
            }
            persistFrame(0, 115, 130, 130, 160, 170, 25_000_000L, 20, 5)
            persistFrame(1, 155, 165, 190, 210, 220, 25_000_000L, 20, 5)
            persistFrame(2, 195, 205, 220, 240, 250, 25_000_000L, 20, 5)
            persistFrame(3, 235, 245, 250, 270, 280, 25_000_000L, 20, 5)
            drain(300)
            handoff(310)
            settled(315)
            stageComplete(320)
        }
        // frame0: verifyAt=160, acq=142, drain=300 -> max(130,142)=142, min(160,300)=160 -> 18
        // frame1: verifyAt=210, acq=142, drain=300 -> max(190,142)=190, min(210,300)=210 -> 20
        // frame2: verifyAt=240, acq=142, drain=300 -> max(220,142)=220, min(240,300)=240 -> 20
        // frame3: verifyAt=270, acq=142, drain=300 -> max(250,142)=250, min(270,300)=270 -> 20
        // total = 78ms
        assertEquals(78L, sequence.ledger.postAcquisitionVerifyOverlapMs())
    }

    @Test
    fun postAcquisitionVerifyOverlap_runsPastDrain_clippedAtDrain() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index ->
                image(index, 110 + index * 10L)
                result(index, 112 + index * 10L)
            }
            persistFrame(0, 115, 130, 220, 300, 320, 25_000_000L, 20, 5)
            persistFrame(1, 155, 165, 280, 340, 360, 25_000_000L, 20, 5)
            persistFrame(2, 195, 205, 340, 380, 400, 25_000_000L, 20, 5)
            persistFrame(3, 235, 245, 400, 420, 440, 25_000_000L, 20, 5)
            drain(250)
            handoff(260)
            settled(265)
            stageComplete(270)
        }
        // frame0: verifyAt=300, acq=142, drain=250 -> max(220,142)=220, min(300,250)=250 -> 30
        // frame1: verifyAt=340, acq=142, drain=250 -> max(280,142)=280, min(340,250)=250 -> 0
        // frame2: verifyAt=380, acq=142, drain=250 -> max(340,142)=340, min(380,250)=250 -> 0
        // frame3: verifyAt=420, acq=142, drain=250 -> max(400,142)=400, min(420,250)=250 -> 0
        // total = 30ms
        assertEquals(30L, sequence.ledger.postAcquisitionVerifyOverlapMs())
    }

    @Test
    fun postAcquisitionVerifyOverlap_missingTimestamps_returnsZero() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index ->
                image(index, 110 + index * 10L)
                result(index, 112 + index * 10L)
            }
            // No persistence recorded for any frame
            drain(200)
        }
        assertEquals(0L, sequence.ledger.postAcquisitionVerifyOverlapMs())
    }

    @Test
    fun postAcquisitionVerifyOverlap_multipleFrames_boundedSum() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index ->
                image(index, 110 + index * 10L)
                result(index, 112 + index * 10L)
            }
            persistFrame(0, 115, 130, 160, 170, 180, 25_000_000L, 20, 5)
            persistFrame(1, 155, 165, 190, 210, 220, 25_000_000L, 20, 5)
            persistFrame(2, 195, 205, 220, 240, 250, 25_000_000L, 20, 5)
            persistFrame(3, 235, 245, 250, 270, 280, 25_000_000L, 20, 5)
            drain(300)
            handoff(310)
            settled(315)
            stageComplete(320)
        }
        // frame0: 170-160=10
        // frame1: 210-190=20
        // frame2: 240-220=20
        // frame3: 270-250=20
        // total = 70ms
        assertEquals(70L, sequence.ledger.postAcquisitionVerifyOverlapMs())
    }
}
