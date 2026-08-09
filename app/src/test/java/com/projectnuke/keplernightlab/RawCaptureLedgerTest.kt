package com.projectnuke.keplernightlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Deterministic tests for the RAW serialized owner ([RawCaptureLedger]).
 *
 * The ledger is the single authoritative owner of RAW capture progress: maps,
 * counters, identity, and manifest. Camera2/ImageReader callbacks post owner
 * events and the save worker returns immutable [RawSaveCompletion] objects;
 * neither ever mutates this state directly. Tests drive the ledger from a
 * single thread with opaque payload types (no Camera2 framework objects).
 */
@RunWith(RobolectricTestRunner::class)
class RawCaptureLedgerTest {

    private fun <I, R> RawCaptureLedger<I, R>.takeAllReadyFrames(): List<RawReadyFrame<I, R>> =
        buildList {
            while (true) {
                val frame = takeNextReadyFrame() ?: break
                add(frame)
            }
        }

    private fun ledger(
        requestedFrames: Int = 4,
        onClose: (String) -> Unit = {}
    ) = RawCaptureLedger<String, String>(requestedFrames, onClose)

    @Test
    fun pairingFollowsAscendingTimestampAndAllocatesIdentities() {
        val closed = mutableListOf<String>()
        val owner = ledger(onClose = { closed += it })
        owner.recordImage(3L, "img-3", 30L)
        owner.recordImage(1L, "img-1", 10L)
        owner.recordImage(2L, "img-2", 20L)
        owner.recordResult(2L, "res-2")
        owner.recordResult(1L, "res-1")
        owner.recordResult(3L, "res-3")

        val ready = owner.takeAllReadyFrames()

        assertEquals(listOf(1L, 2L, 3L), ready.map { it.timestampNs })
        assertEquals(listOf(0, 1, 2), ready.map { it.frameIndex })
        assertEquals(listOf("img-1", "img-2", "img-3"), ready.map { it.image })
        assertEquals(listOf("res-1", "res-2", "res-3"), ready.map { it.result })
        assertTrue(closed.isEmpty())
        assertEquals(3, owner.snapshot().receivedImages)
        assertEquals(3, owner.snapshot().completedResults)
    }

    @Test
    fun unmatchedImageOrResultStaysUntilItsPairArrives() {
        val closed = mutableListOf<String>()
        val owner = ledger(onClose = { closed += it })
        owner.recordImage(1L, "img-1", 10L)
        owner.recordResult(2L, "res-2")

        val first = owner.takeAllReadyFrames()

        assertTrue(first.isEmpty())
        owner.recordResult(1L, "res-1")
        owner.recordImage(2L, "img-2", 20L)

        val second = owner.takeAllReadyFrames()

        assertEquals(listOf(1L, 2L), second.map { it.timestampNs })
        assertTrue(closed.isEmpty())
    }

    @Test
    fun emergencyEvictionDropsOnlyOldestUnmatchedAndCountsExactlyOnce() {
        val closed = mutableListOf<String>()
        val owner = ledger(onClose = { closed += it })
        owner.recordImage(1L, "img-1", 10L)
        owner.recordImage(2L, "img-2", 20L)
        owner.recordImage(3L, "img-3", 30L)
        owner.recordResult(3L, "res-3")

        owner.evictEmergencyUnmatchedImages(readerCapacity = 2)

        assertEquals(listOf("img-1"), closed)
        assertEquals(1, owner.snapshot().droppedUnmatchedImages)
        owner.evictEmergencyUnmatchedImages(readerCapacity = 5)
        assertEquals(listOf("img-1"), closed)
        assertEquals(1, owner.snapshot().droppedUnmatchedImages)
    }

    @Test
    fun adoptSuccessAccumulatesCountersManifestAndSaveTimes() {
        val owner = ledger()
        owner.adoptSuccess(
            RawSaveCompletion.Success(
                frameIndex = 0,
                timestampNs = 5L,
                raw16Filename = "frame_00.raw16",
                raw16Bytes = 1024L,
                saveDurationMs = 42L,
                dngSidecar = RawDngSidecarOutcome.notRequested(0)
            )
        )
        owner.adoptSuccess(
            RawSaveCompletion.Success(
                frameIndex = 1,
                timestampNs = 6L,
                raw16Filename = "frame_01.raw16",
                raw16Bytes = 2048L,
                saveDurationMs = 58L,
                dngSidecar = RawDngSidecarOutcome.notRequested(1)
            )
        )

        assertEquals(2, owner.snapshot().savedFrames)
        assertEquals(100L, owner.rawSaveTotalMs())
        assertEquals(50.0, owner.rawAverageSaveMs()!!, 0.0)
        val manifest = owner.frameObjectsSnapshot()
        assertEquals(2, manifest.length())
        assertEquals("frame_00.raw16", manifest.getJSONObject(0).getString("raw16File"))
    }

    @Test
    fun adoptFailureRecordsManifestEntryWithoutSavedFrameAccounting() {
        val owner = ledger()
        owner.adoptFailure(
            RawSaveCompletion.Failed(
                frameIndex = 2,
                timestampNs = 9L,
                failureType = "encode threw",
                failureMessage = "boom",
                throwable = null
            )
        )

        assertEquals(0, owner.snapshot().savedFrames)
        assertEquals(1, owner.frameObjectsSnapshot().length())
    }

    @Test
    fun rejectedSubmissionKeepsItsFrameIdentityOnRetry() {
        val closed = mutableListOf<String>()
        val owner = ledger(onClose = { closed += it })
        owner.recordImage(1L, "img-1", 10L)
        owner.recordResult(1L, "res-1")

        val first = owner.takeNextReadyFrame()
        assertTrue(first != null)
        val frame = first!!
        owner.restoreRejectedSubmission(frame)

        val second = owner.takeNextReadyFrame()
        assertTrue(second != null)
        assertEquals(1L, second!!.timestampNs)
        assertEquals(frame.frameIndex, second.frameIndex)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun identityExhaustionClosesSurplusPairs() {
        val closed = mutableListOf<String>()
        val owner = ledger(requestedFrames = 2, onClose = { closed += it })
        owner.recordImage(1L, "img-1", 10L)
        owner.recordImage(2L, "img-2", 20L)
        owner.recordImage(3L, "img-3", 30L)
        owner.recordResult(1L, "res-1")
        owner.recordResult(2L, "res-2")
        owner.recordResult(3L, "res-3")

        val ready = owner.takeAllReadyFrames()

        assertEquals(listOf(0, 1), ready.map { it.frameIndex })
        assertEquals(listOf("img-1", "img-2"), ready.map { it.image })
        assertEquals(listOf("img-3"), closed)
    }

    @Test
    fun snapshotReflectsEveryCounterMutation() {
        val owner = ledger()
        assertEquals(
            RawCaptureProgressSnapshot(4, 0, 0, 0, 0, 0, 0),
            owner.snapshot()
        )
        owner.recordImage(1L, "img-1", 10L)
        owner.evictEmergencyUnmatchedImages(readerCapacity = 0)
        owner.recordResult(1L, "res-1")
        owner.recordCaptureFailure()
        owner.setAttemptedFrames(3)
        owner.adoptSuccess(
            RawSaveCompletion.Success(
                frameIndex = 0,
                timestampNs = 1L,
                raw16Filename = "frame_00.raw16",
                raw16Bytes = 10L,
                saveDurationMs = 5L,
                dngSidecar = RawDngSidecarOutcome.notRequested(0)
            )
        )
        assertEquals(
            RawCaptureProgressSnapshot(4, 3, 1, 1, 1, 1, 1),
            owner.snapshot()
        )
    }

    @Test
    fun releaseAllImagesClosesEveryHeldImageExactlyOnce() {
        val closed = mutableListOf<String>()
        val owner = ledger(onClose = { closed += it })
        owner.recordImage(1L, "img-1", 10L)
        owner.recordImage(2L, "img-2", 20L)
        owner.recordResult(2L, "res-2")

        owner.releaseAllImages()
        owner.releaseAllImages()

        assertEquals(listOf("img-1", "img-2"), closed)
        assertTrue(owner.takeAllReadyFrames().isEmpty())
        assertEquals(0, owner.frameObjectsSnapshot().length())
    }

    @Test
    fun releaseFailureIsRetainedAsStructuredLedgerOutcome() {
        val failure = IllegalStateException("close failed")
        val owner = ledger(onClose = { throw failure })
        owner.recordImage(1L, "img-1", 10L)
        owner.releaseAllImages()
        val outcomes = owner.imageReleaseOutcomes()
        assertEquals(1, outcomes.size)
        assertFalse(outcomes.single().succeeded)
        assertEquals(failure, outcomes.single().failure)
    }

    @Test
    fun duplicateTimestampReplacesAndClosesPriorImage() {
        val closed = mutableListOf<String>()
        val owner = ledger(onClose = { closed += it })
        owner.recordImage(7L, "img-7-old", 10L)
        owner.recordImage(7L, "img-7-new", 20L)

        assertEquals(listOf("img-7-old"), closed)
        owner.recordResult(7L, "res-7")
        val ready = owner.takeAllReadyFrames()
        assertEquals(listOf("img-7-new"), ready.map { it.image })
    }

    @Test
    fun rawFirstImageDelayIsOwnerRecordedField() {
        val owner = ledger()
        assertNull(owner.rawFirstImageDelayMs)
        owner.rawFirstImageDelayMs = 123L
        assertEquals(123L, owner.rawFirstImageDelayMs)
    }

    @Test
    fun failureCounterDoesNotAdvanceSavedFrames() {
        val owner = ledger()
        owner.recordCaptureFailure()
        owner.recordCaptureFailure()
        assertEquals(2, owner.snapshot().failedCaptures)
        assertEquals(0, owner.snapshot().savedFrames)
        assertFalse(owner.snapshot().attemptedFrames > 0)
    }
}
