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
}
