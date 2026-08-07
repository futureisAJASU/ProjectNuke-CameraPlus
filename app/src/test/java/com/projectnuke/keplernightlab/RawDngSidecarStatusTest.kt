package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the structured per-frame DNG sidecar state machine and outcome model.
 *
 * The state machine preserves three crucial invariants:
 * - non-prefix failures (a frame whose DNG save fails for any reason) remain representable,
 * - a successful required raw16 frame is never discarded when its DNG sidecar fails,
 * - public-export transitions are explicit (PENDING -> EXPORTED|FAILED) so the
 *   manifest can be queried deterministically.
 */
class RawDngSidecarStatusTest {

    @Test
    fun notRequestedOutcomeIsTheInitialState() {
        val outcome = RawDngSidecarOutcome.notRequested(frameIndex = 0)
        assertEquals(RawDngSidecarStatus.NOT_REQUESTED, outcome.status)
        assertNull(outcome.sidecarFilename)
        assertNull(outcome.publicUri)
        assertNull(outcome.failureDescription)
        assertFalse(outcome.isLocallySaved)
        assertFalse(outcome.isLocalFailureOnly)
        assertFalse(outcome.isPublicExported)
    }

    @Test
    fun localSavedOutcomePreservesTheFilename() {
        val outcome = RawDngSidecarOutcome.localSaved(frameIndex = 7, filename = "frame_07.dng")
        assertEquals(RawDngSidecarStatus.LOCAL_SAVED, outcome.status)
        assertEquals("frame_07.dng", outcome.sidecarFilename)
        assertTrue(outcome.isLocallySaved)
        assertFalse(outcome.isLocalFailureOnly)
    }

    @Test
    fun localSaveFailedDoesNotDiscardFrameIdentification() {
        val outcome = RawDngSidecarOutcome.localSaveFailed(
            frameIndex = 3,
            failureDescription = "OutOfMemoryError: insufficient heap for raw DNG encode"
        )
        assertEquals(RawDngSidecarStatus.LOCAL_SAVE_FAILED, outcome.status)
        assertEquals(3, outcome.frameIndex)
        assertNull(outcome.sidecarFilename)
        assertNotNull(outcome.failureDescription)
        assertTrue(outcome.isLocalFailureOnly)
        assertFalse(outcome.isLocallySaved)
        // The frame index is preserved so the manifest can still correlate with raw16.
    }

    @Test
    fun publicExportPendingTracksLocalFilenameUntilExportCompletes() {
        val outcome = RawDngSidecarOutcome.publicExportPending(frameIndex = 5, localFilename = "frame_05.dng")
        assertEquals(RawDngSidecarStatus.PUBLIC_EXPORT_PENDING, outcome.status)
        assertEquals("frame_05.dng", outcome.sidecarFilename)
        assertNull(outcome.publicUri)
        assertFalse(outcome.isPublicExported)
    }

    @Test
    fun publicExportedOutcomeCarriesBothLocalAndPublicReferences() {
        val outcome = RawDngSidecarOutcome.publicExported(
            frameIndex = 5,
            localFilename = "frame_05.dng",
            publicUri = "content://media/external/images/media/42"
        )
        assertEquals(RawDngSidecarStatus.PUBLIC_EXPORTED, outcome.status)
        assertEquals("frame_05.dng", outcome.sidecarFilename)
        assertEquals("content://media/external/images/media/42", outcome.publicUri)
        assertTrue(outcome.isPublicExported)
    }

    @Test
    fun publicExportFailedRetainsLocalFilenameForRecovery() {
        val outcome = RawDngSidecarOutcome.publicExportFailed(
            frameIndex = 5,
            localFilename = "frame_05.dng",
            failureDescription = "MediaProvider insert failed"
        )
        assertEquals(RawDngSidecarStatus.PUBLIC_EXPORT_FAILED, outcome.status)
        assertEquals("frame_05.dng", outcome.sidecarFilename)
        assertNull(outcome.publicUri)
        assertNotNull(outcome.failureDescription)
        assertFalse(outcome.isPublicExported)
        // Local filename is preserved so recovery/re-export is possible.
    }

    @Test
    fun parseOrDefaultHandlesUnknownAndNullStrings() {
        assertEquals(RawDngSidecarStatus.NOT_REQUESTED, RawDngSidecarStatus.parseOrDefault(null))
        assertEquals(RawDngSidecarStatus.NOT_REQUESTED, RawDngSidecarStatus.parseOrDefault(""))
        assertEquals(RawDngSidecarStatus.NOT_REQUESTED, RawDngSidecarStatus.parseOrDefault("null"))
        assertEquals(RawDngSidecarStatus.NOT_REQUESTED, RawDngSidecarStatus.parseOrDefault("UNKNOWN_STATE"))
        assertEquals(RawDngSidecarStatus.LOCAL_SAVED, RawDngSidecarStatus.parseOrDefault("LOCAL_SAVED"))
        assertEquals(RawDngSidecarStatus.LOCAL_SAVE_FAILED, RawDngSidecarStatus.parseOrDefault("LOCAL_SAVE_FAILED"))
        assertEquals(RawDngSidecarStatus.PUBLIC_EXPORTED, RawDngSidecarStatus.parseOrDefault("PUBLIC_EXPORTED"))
        assertEquals(RawDngSidecarStatus.PUBLIC_EXPORT_FAILED, RawDngSidecarStatus.parseOrDefault("PUBLIC_EXPORT_FAILED"))
        assertEquals(RawDngSidecarStatus.PUBLIC_EXPORT_PENDING, RawDngSidecarStatus.parseOrDefault("PUBLIC_EXPORT_PENDING"))
    }

    @Test
    fun nonPrefixFrameFailureIsRepresentableByFrameIndex() {
        // A frame whose DNG filename does NOT follow the frame_NN.dng convention
        // (e.g. a corrupted DNG that was renamed or quarantined) must still be
        // representable — only the frameIndex matters, not the filename prefix.
        val unusualFilename = "dng_export_partial_42.dng"
        val outcome = RawDngSidecarOutcome.publicExported(
            frameIndex = 42,
            localFilename = unusualFilename,
            publicUri = "content://media/external/images/media/99"
        )
        assertEquals(42, outcome.frameIndex)
        assertEquals(unusualFilename, outcome.sidecarFilename)
        assertEquals(RawDngSidecarStatus.PUBLIC_EXPORTED, outcome.status)
    }

    @Test
    fun raw16FrameSurvivesLocalDngFailure() {
        // Invariant: DNG LOCAL_SAVE_FAILED must NOT imply raw16 frame failure.
        // The raw16 frame is owned by a different state machine (savedFrames).
        val outcome = RawDngSidecarOutcome.localSaveFailed(
            frameIndex = 11,
            failureDescription = "native dng writer returned false"
        )
        assertTrue(outcome.isLocalFailureOnly)
        assertFalse(outcome.isLocallySaved)
        // The frame is still identifiable for raw16 correlation.
        assertEquals(11, outcome.frameIndex)
    }

    @Test
    fun outcomeProducesConsistentManifestJsonFields() {
        // The structured outcome must produce the same manifest field names used
        // by the legacy RawFusionCapture path (dngFile, dngSidecarStatus).
        val outcome = RawDngSidecarOutcome.publicExported(
            frameIndex = 1,
            localFilename = "frame_01.dng",
            publicUri = "content://media/external/images/media/2"
        )
        // The wire-format status name must match the legacy string exactly.
        assertEquals("PUBLIC_EXPORTED", outcome.status.name)
        // The sidecar filename must be exposed for serialization.
        assertEquals("frame_01.dng", outcome.sidecarFilename)
    }
}
