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

    // ------------------------------------------------------------------
    // Exact RAW payload-write overlap: [rawPayloadWriteStartedAt, rawPayloadWriteFinishedAt] x [acquisitionComplete, drainComplete]
    // ------------------------------------------------------------------

    @Test
    fun postAcquisitionRawPayloadOverlap_entirelyBeforeAcquisition_returnsZero() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            // payload entirely before acquisitionComplete=142
            nowMs = 120; ledger.recordRawPayloadWriteStarted(0)
            nowMs = 130; ledger.recordRawPayloadWriteFinished(0)
            nowMs = 150; ledger.recordWorkerStarted(0)
            nowMs = 160; ledger.recordWriteFinished(0)
            nowMs = 170; ledger.recordVerified(0)
            nowMs = 180; ledger.recordCommitted(0)
            drain(250); handoff(260); settled(265); stageComplete(270)
        }
        assertEquals(0L, sequence.ledger.postAcquisitionRawPayloadWriteOverlapMs())
    }

    @Test
    fun postAcquisitionRawPayloadOverlap_entirelyInside_returnsFullDuration() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 160; ledger.recordRawPayloadWriteStarted(0)
            nowMs = 175; ledger.recordRawPayloadWriteFinished(0)
            nowMs = 220; ledger.recordRawPayloadWriteStarted(1)
            nowMs = 240; ledger.recordRawPayloadWriteFinished(1)
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        // 15 + 20 = 35
        assertEquals(35L, sequence.ledger.postAcquisitionRawPayloadWriteOverlapMs())
    }

    @Test
    fun postAcquisitionRawPayloadOverlap_straddlesAcquisition_leftClipped() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            // acquisitionComplete=142, payload [130,160] -> clipped to [142,160]=18
            nowMs = 130; ledger.recordRawPayloadWriteStarted(0)
            nowMs = 160; ledger.recordRawPayloadWriteFinished(0)
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        assertEquals(18L, sequence.ledger.postAcquisitionRawPayloadWriteOverlapMs())
    }

    @Test
    fun postAcquisitionRawPayloadOverlap_runsPastDrain_rightClipped() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 280; ledger.recordRawPayloadWriteStarted(0)
            nowMs = 320; ledger.recordRawPayloadWriteFinished(0)
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        // 300-280=20
        assertEquals(20L, sequence.ledger.postAcquisitionRawPayloadWriteOverlapMs())
    }

    @Test
    fun postAcquisitionRawPayloadOverlap_missingTimestamps_returnsZero() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        assertEquals(0L, sequence.ledger.postAcquisitionRawPayloadWriteOverlapMs())
    }

    @Test
    fun postAcquisitionRawPayloadOverlap_multipleFrames_boundedSum() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 150; ledger.recordRawPayloadWriteStarted(0); nowMs = 160; ledger.recordRawPayloadWriteFinished(0) // 10
            nowMs = 170; ledger.recordRawPayloadWriteStarted(1); nowMs = 190; ledger.recordRawPayloadWriteFinished(1) // 20
            nowMs = 200; ledger.recordRawPayloadWriteStarted(2); nowMs = 220; ledger.recordRawPayloadWriteFinished(2) // 20
            nowMs = 230; ledger.recordRawPayloadWriteStarted(3); nowMs = 250; ledger.recordRawPayloadWriteFinished(3) // 20
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        assertEquals(70L, sequence.ledger.postAcquisitionRawPayloadWriteOverlapMs())
    }

    // ------------------------------------------------------------------
    // Exact RAW sync overlap: [rawSyncStartedAt, fsyncFinishedAt] x [acquisitionComplete, drainComplete]
    // ------------------------------------------------------------------

    @Test
    fun postAcquisitionRawSyncOverlap_entirelyBeforeAcquisition_returnsZero() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 120; ledger.recordRawSyncStarted(0)
            nowMs = 130; ledger.recordFsyncFinished(0)
            drain(250); handoff(260); settled(265); stageComplete(270)
        }
        assertEquals(0L, sequence.ledger.postAcquisitionRawSyncOverlapMs())
    }

    @Test
    fun postAcquisitionRawSyncOverlap_entirelyInside_returnsFullDuration() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 160; ledger.recordRawSyncStarted(0); nowMs = 170; ledger.recordFsyncFinished(0) // 10
            nowMs = 190; ledger.recordRawSyncStarted(1); nowMs = 200; ledger.recordFsyncFinished(1) // 10
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        assertEquals(20L, sequence.ledger.postAcquisitionRawSyncOverlapMs())
    }

    @Test
    fun postAcquisitionRawSyncOverlap_straddlesAcquisition_leftClipped() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 135; ledger.recordRawSyncStarted(0)
            nowMs = 150; ledger.recordFsyncFinished(0)
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        // acq 142 -> 150-142=8
        assertEquals(8L, sequence.ledger.postAcquisitionRawSyncOverlapMs())
    }

    @Test
    fun postAcquisitionRawSyncOverlap_runsPastDrain_rightClipped() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 290; ledger.recordRawSyncStarted(0)
            nowMs = 320; ledger.recordFsyncFinished(0)
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        assertEquals(10L, sequence.ledger.postAcquisitionRawSyncOverlapMs())
    }

    @Test
    fun postAcquisitionRawSyncOverlap_missingTimestamps_returnsZero() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        assertEquals(0L, sequence.ledger.postAcquisitionRawSyncOverlapMs())
    }

    @Test
    fun postAcquisitionRawSyncOverlap_multipleFrames_boundedSum() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 150; ledger.recordRawSyncStarted(0); nowMs = 155; ledger.recordFsyncFinished(0) // 5
            nowMs = 165; ledger.recordRawSyncStarted(1); nowMs = 175; ledger.recordFsyncFinished(1) // 10
            nowMs = 185; ledger.recordRawSyncStarted(2); nowMs = 195; ledger.recordFsyncFinished(2) // 10
            nowMs = 205; ledger.recordRawSyncStarted(3); nowMs = 215; ledger.recordFsyncFinished(3) // 10
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        assertEquals(35L, sequence.ledger.postAcquisitionRawSyncOverlapMs())
    }

    // ------------------------------------------------------------------
    // Exact atomic-publish overlap: [rawPublishStartedAt, rawPublishFinishedAt] x [acquisitionComplete, drainComplete]
    // ------------------------------------------------------------------

    @Test
    fun postAcquisitionRawPublishOverlap_entirelyBeforeAcquisition_returnsZero() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 120; ledger.recordRawPublishStarted(0)
            nowMs = 125; ledger.recordRawPublishFinished(0)
            drain(250); handoff(260); settled(265); stageComplete(270)
        }
        assertEquals(0L, sequence.ledger.postAcquisitionRawPublishOverlapMs())
    }

    @Test
    fun postAcquisitionRawPublishOverlap_entirelyInside_returnsFullDuration() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 160; ledger.recordRawPublishStarted(0); nowMs = 162; ledger.recordRawPublishFinished(0)
            nowMs = 170; ledger.recordRawPublishStarted(1); nowMs = 173; ledger.recordRawPublishFinished(1)
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        assertEquals(5L, sequence.ledger.postAcquisitionRawPublishOverlapMs())
    }

    @Test
    fun postAcquisitionRawPublishOverlap_straddlesAcquisition_leftClipped() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 140; ledger.recordRawPublishStarted(0)
            nowMs = 150; ledger.recordRawPublishFinished(0)
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        // 150-142=8
        assertEquals(8L, sequence.ledger.postAcquisitionRawPublishOverlapMs())
    }

    @Test
    fun postAcquisitionRawPublishOverlap_runsPastDrain_rightClipped() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 295; ledger.recordRawPublishStarted(0)
            nowMs = 310; ledger.recordRawPublishFinished(0)
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        assertEquals(5L, sequence.ledger.postAcquisitionRawPublishOverlapMs())
    }

    @Test
    fun postAcquisitionRawPublishOverlap_missingTimestamps_returnsZero() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        assertEquals(0L, sequence.ledger.postAcquisitionRawPublishOverlapMs())
    }

    @Test
    fun postAcquisitionRawPublishOverlap_multipleFrames_boundedSum() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 150; ledger.recordRawPublishStarted(0); nowMs = 152; ledger.recordRawPublishFinished(0) //2
            nowMs = 160; ledger.recordRawPublishStarted(1); nowMs = 163; ledger.recordRawPublishFinished(1) //3
            nowMs = 170; ledger.recordRawPublishStarted(2); nowMs = 174; ledger.recordRawPublishFinished(2) //4
            nowMs = 180; ledger.recordRawPublishStarted(3); nowMs = 185; ledger.recordRawPublishFinished(3) //5
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        assertEquals(14L, sequence.ledger.postAcquisitionRawPublishOverlapMs())
    }

    // ------------------------------------------------------------------
    // Truthful post-verify to adoption residual: [verifiedAt, committedAt] x [acquisitionComplete, drainComplete]
    // ------------------------------------------------------------------

    @Test
    fun postAcquisitionPostVerifyToAdoption_entirelyBeforeAcquisition_returnsZero() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 120; ledger.recordVerified(0)
            nowMs = 130; ledger.recordCommitted(0)
            drain(250); handoff(260); settled(265); stageComplete(270)
        }
        assertEquals(0L, sequence.ledger.postAcquisitionPostVerifyToAdoptionOverlapMs())
    }

    @Test
    fun postAcquisitionPostVerifyToAdoption_entirelyInside_returnsFullDuration() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 160; ledger.recordVerified(0); nowMs = 170; ledger.recordCommitted(0)
            nowMs = 190; ledger.recordVerified(1); nowMs = 200; ledger.recordCommitted(1)
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        assertEquals(20L, sequence.ledger.postAcquisitionPostVerifyToAdoptionOverlapMs())
    }

    @Test
    fun postAcquisitionPostVerifyToAdoption_straddlesAcquisition_leftClipped() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 135; ledger.recordVerified(0)
            nowMs = 150; ledger.recordCommitted(0)
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        assertEquals(8L, sequence.ledger.postAcquisitionPostVerifyToAdoptionOverlapMs())
    }

    @Test
    fun postAcquisitionPostVerifyToAdoption_runsPastDrain_rightClipped() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 290; ledger.recordVerified(0)
            nowMs = 320; ledger.recordCommitted(0)
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        assertEquals(10L, sequence.ledger.postAcquisitionPostVerifyToAdoptionOverlapMs())
    }

    @Test
    fun postAcquisitionPostVerifyToAdoption_missingTimestamps_returnsZero() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        assertEquals(0L, sequence.ledger.postAcquisitionPostVerifyToAdoptionOverlapMs())
    }

    @Test
    fun postAcquisitionPostVerifyToAdoption_multipleFrames_boundedSum() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 150; ledger.recordVerified(0); nowMs = 155; ledger.recordCommitted(0) //5
            nowMs = 165; ledger.recordVerified(1); nowMs = 175; ledger.recordCommitted(1) //10
            nowMs = 185; ledger.recordVerified(2); nowMs = 195; ledger.recordCommitted(2) //10
            nowMs = 205; ledger.recordVerified(3); nowMs = 215; ledger.recordCommitted(3) //10
            drain(300); handoff(310); settled(315); stageComplete(320)
        }
        assertEquals(35L, sequence.ledger.postAcquisitionPostVerifyToAdoptionOverlapMs())
    }

    // ------------------------------------------------------------------
    // Terminal metadata write: exact durable terminal write
    // ------------------------------------------------------------------

    @Test
    fun terminalMetadataWriteMs_returnsDuration() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            drain(270)
            nowMs = 272; ledger.recordTerminalMetadataWriteStarted()
            nowMs = 277; ledger.recordTerminalMetadataWriteFinished()
            handoff(280); settled(285); stageComplete(290)
        }
        assertEquals(5L, sequence.ledger.terminalMetadataWriteMs())
        assertEquals(5L, sequence.ledger.snapshot().terminalMetadataWriteMs)
        assertEquals(5L, sequence.ledger.postAcquisitionTerminalMetadataWriteOverlapMs())
    }

    @Test
    fun postAcquisitionTerminalMetadata_entirelyBeforeAcquisition_returnsZero() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            // terminal write before acquisitionComplete (artificial) -> 0
            nowMs = 80; ledger.recordTerminalMetadataWriteStarted()
            nowMs = 90; ledger.recordTerminalMetadataWriteFinished()
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            drain(270); handoff(280); settled(285); stageComplete(290)
        }
        assertEquals(0L, sequence.ledger.postAcquisitionTerminalMetadataWriteOverlapMs())
    }

    @Test
    fun postAcquisitionTerminalMetadata_straddlesAcquisition_leftClipped() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            // acquisition 142, terminal [138,150] -> 8
            nowMs = 138; ledger.recordTerminalMetadataWriteStarted()
            nowMs = 150; ledger.recordTerminalMetadataWriteFinished()
            drain(270); handoff(280); settled(285); stageComplete(290)
        }
        assertEquals(8L, sequence.ledger.postAcquisitionTerminalMetadataWriteOverlapMs())
    }

    @Test
    fun postAcquisitionTerminalMetadata_runsPastStage_rightClipped() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            drain(270)
            nowMs = 285; ledger.recordTerminalMetadataWriteStarted()
            nowMs = 310; ledger.recordTerminalMetadataWriteFinished()
            handoff(300); settled(310); stageComplete(305)
        }
        // overlap with [142,305] : 305-285=20 (clipped at stageComplete)
        // set stageComplete at 305 explicitly
        assertEquals(20L, sequence.ledger.postAcquisitionTerminalMetadataWriteOverlapMs())
    }

    @Test
    fun postAcquisitionTerminalMetadata_missingTimestamps_returnsZero() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            drain(270); handoff(280); settled(285); stageComplete(290)
        }
        assertEquals(0L, sequence.ledger.postAcquisitionTerminalMetadataWriteOverlapMs())
        assertEquals(0L, sequence.ledger.terminalMetadataWriteMs())
    }

    // ------------------------------------------------------------------
    // Renamed broad metrics retain truthful semantics and legacy aliases
    // ------------------------------------------------------------------

    @Test
    fun renamedBroadMetrics_aliasParity() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            nowMs = 150; ledger.recordWorkerStarted(0); nowMs = 170; ledger.recordWriteFinished(0); nowMs = 180; ledger.recordCommitted(0)
            nowMs = 190; ledger.recordWorkerStarted(1); nowMs = 210; ledger.recordWriteFinished(1); nowMs = 220; ledger.recordCommitted(1)
            drain(250); handoff(260); settled(265); stageComplete(270)
        }
        assertEquals(sequence.ledger.postAcquisitionPreVerifyPersistenceOverlapMs(), sequence.ledger.postAcquisitionRawWriteOverlapMs())
        assertEquals(sequence.ledger.postAcquisitionPostPublishToAdoptionOverlapMs(), sequence.ledger.postAcquisitionMetadataOverlapMs())
    }

    @Test
    fun toJson_fromCaptureTiming_preservesAllNewFields() {
        val sequence = RawSequence().apply {
            submitRequest(100)
            repeat(4) { index -> image(index, 110 + index * 10L); result(index, 112 + index * 10L) }
            // payload
            nowMs = 150; ledger.recordRawPayloadWriteStarted(0); nowMs = 160; ledger.recordRawPayloadWriteFinished(0)
            nowMs = 170; ledger.recordRawPayloadWriteStarted(1); nowMs = 180; ledger.recordRawPayloadWriteFinished(1)
            // sync
            nowMs = 160; ledger.recordRawSyncStarted(0); nowMs = 165; ledger.recordFsyncFinished(0)
            nowMs = 180; ledger.recordRawSyncStarted(1); nowMs = 185; ledger.recordFsyncFinished(1)
            // publish
            nowMs = 165; ledger.recordRawPublishStarted(0); nowMs = 167; ledger.recordRawPublishFinished(0)
            nowMs = 185; ledger.recordRawPublishStarted(1); nowMs = 188; ledger.recordRawPublishFinished(1)
            // write/verify/commit
            nowMs = 167; ledger.recordWriteFinished(0); nowMs = 170; ledger.recordVerified(0); nowMs = 175; ledger.recordCommitted(0)
            nowMs = 188; ledger.recordWriteFinished(1); nowMs = 190; ledger.recordVerified(1); nowMs = 195; ledger.recordCommitted(1)
            // remaining frames minimal
            nowMs = 200; ledger.recordWorkerStarted(2); nowMs = 210; ledger.recordWriteFinished(2); nowMs = 215; ledger.recordVerified(2); nowMs = 220; ledger.recordCommitted(2)
            nowMs = 220; ledger.recordWorkerStarted(3); nowMs = 230; ledger.recordWriteFinished(3); nowMs = 235; ledger.recordVerified(3); nowMs = 240; ledger.recordCommitted(3)
            drain(250)
            nowMs = 252; ledger.recordTerminalMetadataWriteStarted()
            nowMs = 257; ledger.recordTerminalMetadataWriteFinished()
            handoff(260); settled(265); stageComplete(270)
        }
        val json = sequence.ledger.toJson()
        val timing = HardwareE2ECaptureTiming.fromCaptureTimingJson(json)
        // exact overlaps derived from per-frame instants must round-trip via computation
        assertEquals(sequence.ledger.postAcquisitionRawPayloadWriteOverlapMs(), timing.postAcquisitionRawPayloadWriteOverlapMs)
        assertEquals(sequence.ledger.postAcquisitionRawSyncOverlapMs(), timing.postAcquisitionRawSyncOverlapMs)
        assertEquals(sequence.ledger.postAcquisitionRawPublishOverlapMs(), timing.postAcquisitionRawPublishOverlapMs)
        assertEquals(sequence.ledger.postAcquisitionPostVerifyToAdoptionOverlapMs(), timing.postAcquisitionPostVerifyToAdoptionOverlapMs)
        assertEquals(sequence.ledger.terminalMetadataWriteMs(), timing.terminalMetadataWriteMs)
        assertEquals(sequence.ledger.postAcquisitionTerminalMetadataWriteOverlapMs(), timing.postAcquisitionTerminalMetadataWriteOverlapMs)
        // broad renamed also preserved
        assertEquals(sequence.ledger.postAcquisitionPreVerifyPersistenceOverlapMs(), timing.postAcquisitionPreVerifyPersistenceOverlapMs)
        assertEquals(sequence.ledger.postAcquisitionPostPublishToAdoptionOverlapMs(), timing.postAcquisitionPostPublishToAdoptionOverlapMs)
        // legacy aliases parity
        assertEquals(timing.postAcquisitionPreVerifyPersistenceOverlapMs, timing.postAcquisitionRawWriteOverlapMs)
        assertEquals(timing.postAcquisitionPostPublishToAdoptionOverlapMs, timing.postAcquisitionMetadataOverlapMs)
        // JSON round-trip
        val decoded = HardwareE2ECaptureTiming.fromJson(timing.toJson())
        assertEquals(timing, decoded)
    }

    @Test
    fun legacyParserCompatibility_oldKeysOnly() {
        // Simulate already persisted U2.0 local report with only old keys
        val oldJson = JSONObject()
            .put("requestedFrames", 4)
            .put("cameraAcquisitionMs", 50L)
            .put("persistenceDrainMs", 100L)
            .put("handoffSettlementMs", 10L)
            .put("captureStageTotalMs", 160L)
            .put("postAcquisitionRawWriteOverlapMs", 42L)
            .put("postAcquisitionMetadataOverlapMs", 99L)
            .put("postAcquisitionVerifyOverlapMs", 20L)
            .put("postAcquisitionHandoffOverlapMs", 5L)
            .put("frames", org.json.JSONArray())
        val timing = HardwareE2ECaptureTiming.fromJson(oldJson)
        assertEquals(42L, timing.postAcquisitionPreVerifyPersistenceOverlapMs)
        assertEquals(42L, timing.postAcquisitionRawWriteOverlapMs)
        assertEquals(99L, timing.postAcquisitionPostPublishToAdoptionOverlapMs)
        assertEquals(99L, timing.postAcquisitionMetadataOverlapMs)
        // new exact fields default to 0
        assertEquals(0L, timing.postAcquisitionRawPayloadWriteOverlapMs)
        assertEquals(0L, timing.terminalMetadataWriteMs)
    }
}
