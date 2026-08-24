package com.projectnuke.keplernightlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 5 deterministic timing-ledger tests.  The time source is injectable so
 * every milestone instant and derived duration is exact; no sleeps, no wall
 * clock.  The ledger must be bounded (fixed per-frame arrays), atomic (CAS
 * first-write-wins milestones), and never grow with events.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureTimingLedgerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Deterministic clock: every call returns the next value from [ticks]. */
    private class FakeClock(vararg ticks: Long) : () -> Long {
        private val values = ArrayDeque(ticks.toList())
        override fun invoke(): Long = if (values.size > 1) values.removeFirst() else values.first()
    }

    @Test
    fun milestoneRecorders_areFirstWriteWins() {
        val ledger = CaptureTimingLedger(4) { 100L }
        ledger.recordCaptureRequestSubmitted()
        ledger.recordCaptureRequestSubmitted()
        val snap = ledger.snapshot()
        assertEquals(100L, snap.captureRequestSubmittedAt)
    }

    @Test
    fun derivedMetrics_matchExactDrivenTimes() {
        // requestSubmitted=1000ms; acquisition completes at 3000ms (paired 4/4);
        // drain at 11000ms; handoff/stage complete at 13000ms.
        var current = 1_000_000_000L // ns
        val ledger = CaptureTimingLedger(4) { current }
        ledger.recordCaptureRequestSubmitted()          // t = 1000ms

        current = 2_000_000_000L                        // +1000ms
        ledger.recordImageReceived()
        ledger.recordResultReceived()                   // acquisition not yet complete (1/4)

        current = 3_000_000_000L                        // +2000ms total
        ledger.recordImageReceived(); ledger.recordResultReceived()
        ledger.recordImageReceived(); ledger.recordResultReceived()
        ledger.recordImageReceived(); ledger.recordResultReceived()

        current = 11_000_000_000L                       // +10000ms total
        ledger.recordPersistenceDrainComplete()

        current = 13_000_000_000L                       // +12000ms total
        ledger.recordProcessingHandoffPublished()
        ledger.recordCaptureResourcesSettled()
        ledger.recordCaptureStageComplete()

        val snap = ledger.snapshot()
        assertEquals(4, snap.requestedFrames)
        assertEquals(1_000L, snap.captureRequestSubmittedAt / 1_000_000L)
        assertEquals(2_000L, snap.firstImageReceivedAt / 1_000_000L)
        assertEquals(3_000L, snap.lastCaptureResultAt / 1_000_000L)
        assertEquals(3_000L, snap.cameraAcquisitionCompleteAt / 1_000_000L)
        assertEquals(11_000L, snap.persistenceDrainCompleteAt / 1_000_000L)
        assertEquals(13_000L, snap.captureStageCompleteAt / 1_000_000L)
        // Derived durations:
        assertEquals(2_000L, snap.cameraAcquisitionMs)   // 3000 - 1000
        assertEquals(8_000L, snap.persistenceDrainMs)    // 11000 - 3000
        assertEquals(2_000L, snap.handoffSettlementMs)   // 13000 - 11000
        assertEquals(12_000L, snap.captureStageTotalMs)  // 13000 - 1000
    }

    @Test
    fun acquisitionCompletesOnlyOnPairedEvidence() {
        var current = 0L
        val ledger = CaptureTimingLedger(4) { current }
        // Four results but only three images: NOT complete.
        repeat(4) { ledger.recordResultReceived() }
        repeat(3) { ledger.recordImageReceived() }
        assertEquals(0L, ledger.snapshot().cameraAcquisitionCompleteAt)
        // The fourth image pairs the last result: completes at the CURRENT tick.
        current = 9_999_999L
        ledger.recordImageReceived()
        assertEquals(9_999_999L, ledger.snapshot().cameraAcquisitionCompleteAt)
    }

    @Test
    fun perFrameStorage_isBoundedAndIndexedByFrameIdentity() {
        var current = 0L
        val ledger = CaptureTimingLedger(3) { ++current }
        // Frames beyond requestedFrames are ignored — unbounded histories are
        // forbidden by design.
        ledger.recordCommitted(0)
        ledger.recordCommitted(1)
        ledger.recordCommitted(2)
        ledger.recordCommitted(7)
        ledger.recordCommitted(-3)
        val json = ledger.toJson()
        val frames = json.getJSONArray("frames")
        assertEquals(3, frames.length())
        assertTrue(frames.getJSONObject(0).getLong("committedAt") > 0)
        assertTrue(frames.getJSONObject(1).getLong("committedAt") > 0)
        assertTrue(frames.getJSONObject(2).getLong("committedAt") > 0)
    }

    @Test
    fun unsetEndpointsProduceZeroDurationsWithoutFabricatedTruth() {
        val ledger = CaptureTimingLedger(2) { 5L }
        ledger.recordCaptureRequestSubmitted()
        // No acquisition/drain/stage-complete recorded.
        val snap = ledger.snapshot()
        assertEquals(0L, snap.cameraAcquisitionMs)
        assertEquals(0L, snap.persistenceDrainMs)
        assertEquals(0L, snap.handoffSettlementMs)
        assertEquals(0L, snap.captureStageTotalMs)
    }

    @Test
    fun jsonProjection_containsAllRequiredMilestones() {
        var current = 0L
        val ledger = CaptureTimingLedger(2) { current += 1_000_000L; current }
        ledger.recordCaptureRequestSubmitted()
         ledger.recordImageReceived(0)
         ledger.recordResultReceived(0)
         ledger.recordPersistenceSubmitted(0)
         ledger.recordEncodeFinished(0)
        ledger.recordFsyncFinished(0)
        ledger.recordVerified(0)
        ledger.recordCommitted(0)
        ledger.recordImageReceived(); ledger.recordResultReceived()
        ledger.recordPersistenceDrainComplete()
        ledger.recordProcessingHandoffPublished()
        ledger.recordCaptureResourcesSettled()
        ledger.recordCaptureStageComplete()
        val json = ledger.toJson()
        listOf(
            "captureRequestSubmittedAt", "firstImageReceivedAt", "firstCaptureResultAt",
            "lastImageReceivedAt", "lastCaptureResultAt", "cameraAcquisitionCompleteAt",
            "persistenceDrainCompleteAt", "processingHandoffPublishedAt",
            "captureResourcesSettledAt", "captureStageCompleteAt",
            "cameraAcquisitionMs", "persistenceDrainMs", "handoffSettlementMs",
            "captureStageTotalMs"
        ).forEach { key -> assertTrue("missing $key", json.has(key)) }
        val frame0 = json.getJSONArray("frames").getJSONObject(0)
        listOf(
             "resultAt", "imageAt", "persistenceSubmittedAt", "encodeFinishedAt",
            "fsyncFinishedAt", "verifiedAt", "committedAt"
        ).forEach { key -> assertTrue("missing frame.$key", frame0.has(key) && frame0.getLong(key) > 0L) }
    }

    @Test
    fun persistWritesBoundedFileAndJobMetadataMerge() {
        val jobDir = tmp.newFolder("timing-job")
        java.io.File(jobDir, JOB_JSON_FILE_NAME).writeText(JSONObject().put("requestedFrames", 2).toString())
        var current = 0L
        val ledger = CaptureTimingLedger(2) { current += 1_000_000L; current }
        ledger.recordCaptureRequestSubmitted()
        ledger.recordImageReceived(); ledger.recordResultReceived()
        ledger.recordImageReceived(); ledger.recordResultReceived()
        ledger.recordPersistenceDrainComplete()
        ledger.recordProcessingHandoffPublished()
        ledger.recordCaptureResourcesSettled()
        ledger.recordCaptureStageComplete()

        assertTrue(CaptureTimingLedger.persist(jobDir, ledger))
        val file = java.io.File(jobDir, CaptureTimingLedger.FILE_NAME)
        assertTrue(file.isFile && file.length() > 0L)
        val persisted = JSONObject(file.readText())
        assertTrue(persisted.has("cameraAcquisitionMs"))
        val job = KeplerJobMetadata.read(jobDir)
        assertTrue(job.has("captureTiming"))
        assertFalse(job.getJSONObject("captureTiming").getJSONArray("frames").length() == 0)
    }
}
