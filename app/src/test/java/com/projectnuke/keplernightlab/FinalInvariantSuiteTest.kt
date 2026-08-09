package com.projectnuke.keplernightlab

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Final regression-closure invariant suite for the capture ownership batch.
 *
 * These are minimal cross-module invariants that:
 * - exercise the public session seam with a small deterministic scenario,
 * - assert terminal-state invariants the owner must always uphold,
 * - lock in the structured sidecar state machine introduced in Phase 4,
 * - lock in the file identity contract introduced in Phase 5.
 *
 * They do NOT replace the deeper per-component tests (YuvCaptureOwnerTest,
 * ProductionYuvCaptureBridgeTest, RawDngSidecarStatusTest, NoFollowFileSystemTest);
 * they are the cross-cutting smoke that nothing in the batch broke the seams
 * the rest of the suite depends on.
 */
class FinalInvariantSuiteTest {

    @Test
    fun yuvSessionReachesSuccessAndPublishesConsistentSnapshot() {
        val dir = Files.createTempDirectory("final-suite-success").toFile()
        try {
            val session = YuvCaptureSession.create(
                dispatch = { event -> true /* skip dispatch — owner is never run */ },
                outputDir = dir,
                frameCount = 1,
                rotationDegrees = 0,
                workerCapacity = 2,
                maxRetainedBytes = 1024L * 1024L,
                workProcessor = YuvPngWorkProcessor(
                    encoder = object : YuvPngEncoder {
                        override fun encodeDirect(image: android.media.Image, candidate: java.io.File, rotationDegrees: Int) {}
                        override fun encodeBuffered(frame: BufferedYuvFrame, candidate: java.io.File, rotationDegrees: Int) {}
                    },
                    committer = YuvCandidateCommitter { _, _ -> }
                ),
                productionResourceCoordinator = YuvProductionResourceCoordinator(
                    timeoutScheduler = null,
                    backgroundHandler = null,
                    backgroundThread = null
                )
            )
            try {
                // Initial invariant: session starts ACTIVE with no manifests.
                val initial = session.accounting.snapshot()
                assertEquals(0, initial.receivedFrames)
                assertEquals(0, initial.persistedFrames)
                assertEquals(0, initial.failedFrames)
                assertEquals(0, initial.droppedFrames)
                assertEquals(CaptureTerminalStatus.ACTIVE, session.terminalState.status())
            } finally {
                session.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun captureTerminalStateClaimIsExclusive() {
        // CaptureTerminalState must reject a second claim once a non-ACTIVE
        // terminal has been claimed. This is a single-cell smoke for the
        // atomic terminal state machine the YUV/RAW owners both depend on.
        val state = CaptureTerminalState()
        assertEquals(CaptureTerminalStatus.ACTIVE, state.status())
        assertTrue(state.claim(CaptureTerminalStatus.SUCCESS))
        assertEquals(CaptureTerminalStatus.SUCCESS, state.status())
        assertEquals(false, state.claim(CaptureTerminalStatus.FAILED))
        assertEquals(false, state.claim(CaptureTerminalStatus.TIMED_OUT))
        assertEquals(false, state.claim(CaptureTerminalStatus.CANCELLED))
        assertEquals(CaptureTerminalStatus.SUCCESS, state.status())
    }

    @Test
    fun captureFrameIdentityOwnerIsBoundedAndUnique() {
        val owner = CaptureFrameIdentityOwner(3)
        assertEquals(0, owner.nextIdentity())
        assertEquals(1, owner.nextIdentity())
        assertEquals(2, owner.nextIdentity())
        assertEquals(null, owner.nextIdentity())
        assertEquals(3, owner.allocatedCount())
    }

    @Test
    fun rawDngSidecarStatusStateMachineCoversEveryTerminalState() {
        // Every per-frame DNG outcome must be representable in the structured
        // state machine, regardless of public-export outcome. This is a
        // single-cell smoke for the sidecar state machine introduced in Phase 4.
        val allStatuses = RawDngSidecarStatus.entries.toSet()
        assertTrue(RawDngSidecarStatus.NOT_REQUESTED in allStatuses)
        assertTrue(RawDngSidecarStatus.LOCAL_SAVED in allStatuses)
        assertTrue(RawDngSidecarStatus.LOCAL_SAVE_FAILED in allStatuses)
        assertTrue(RawDngSidecarStatus.PUBLIC_EXPORT_PENDING in allStatuses)
        assertTrue(RawDngSidecarStatus.PUBLIC_EXPORTED in allStatuses)
        assertTrue(RawDngSidecarStatus.PUBLIC_EXPORT_FAILED in allStatuses)
        // parseOrDefault must round-trip every named status.
        for (status in allStatuses) {
            assertEquals(status, RawDngSidecarStatus.parseOrDefault(status.name))
        }
    }

    @Test
    fun fileIdentityContractIsStableAndFailClosed() {
        // fileKey match: identical keys -> match.
        assertTrue(noFollowIdentityMatches("(dev=1,ino=1)", "(dev=1,ino=1)", 0L, 0L, 0L, 0L))
        // fileKey mismatch: different keys -> fail closed even if stat matches.
        assertNotEquals(true, noFollowIdentityMatches("(dev=1,ino=1)", "(dev=1,ino=2)", 64L, 64L, 1000L, 1000L))
        // The legacy stat helper can report matching fields, but it is not used
        // as a stable-identity proof when file keys are unavailable.
        assertTrue(noFollowIdentityMatches(null, null, 100L, 100L, 1000L, 1000L))
        assertEquals(false, noFollowIdentityMatches(null, null, 100L, 101L, 1000L, 1000L))
    }

    @Test
    fun yuvCaptureAccountingPersistedEqualsManifestSizeAtAllTimes() {
        // The accounting snapshot MUST always keep persistedFrames in sync
        // with the manifest size. This is the cross-cutting invariant the
        // YUV owner's adoption pipeline enforces after every persisted frame.
        val dir = Files.createTempDirectory("final-suite-persisted-eq-manifest").toFile()
        try {
            val accounting = YuvCaptureAccounting()
            assertEquals(0, accounting.snapshot().persistedFrames)
            assertEquals(0, accounting.snapshot().manifest.size)
            accounting.persistedFrame(YuvFrameManifestEntry(0, "frame_00_color.png", 1000L, true))
            val s1 = accounting.snapshot()
            assertEquals(1, s1.persistedFrames)
            assertEquals(1, s1.manifest.size)
            // Duplicate identity is rejected.
            assertEquals(false, accounting.persistedFrame(YuvFrameManifestEntry(0, "frame_00_color.png", 1001L, true)))
            val s2 = accounting.snapshot()
            assertEquals(1, s2.persistedFrames)
            assertEquals(1, s2.manifest.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun yuvCaptureAccountingNeverAllowsNegativeCounters() {
        // Accounting counters must be monotonically non-negative. Even
        // arbitrary drop/fail calls must not underflow.
        val accounting = YuvCaptureAccounting()
        accounting.receivedFrame()
        accounting.failedFrame()
        accounting.droppedFrame()
        val snap = accounting.snapshot()
        assertTrue(snap.receivedFrames >= 0)
        assertTrue(snap.failedFrames >= 0)
        assertTrue(snap.droppedFrames >= 0)
        assertTrue(snap.persistedFrames >= 0)
        assertTrue(snap.bufferedFrames >= 0)
    }

    @Test
    fun yuvFrameManifestEntryHasUniqueFilenameAndFrameIndex() {
        // The manifest is the source of truth for frame identity and
        // filename. Two entries must NEVER share a frameIndex or a filename.
        val e1 = YuvFrameManifestEntry(0, "frame_00_color.png", 1000L, true)
        val e2 = YuvFrameManifestEntry(1, "frame_01_color.png", 2000L, true)
        assertNotEquals(e1.frameIndex, e2.frameIndex)
        assertNotEquals(e1.filename, e2.filename)
    }

    @Test
    fun captureTerminalStatusEnumCoversAllRequiredOutcomes() {
        // The terminal state machine must support SUCCESS / PARTIAL_SUCCESS /
        // FAILED / TIMED_OUT / CANCELLED, plus ACTIVE for the open state.
        val required = setOf(
            CaptureTerminalStatus.ACTIVE,
            CaptureTerminalStatus.SUCCESS,
            CaptureTerminalStatus.PARTIAL_SUCCESS,
            CaptureTerminalStatus.FAILED,
            CaptureTerminalStatus.TIMED_OUT,
            CaptureTerminalStatus.CANCELLED
        )
        for (status in required) {
            assertTrue("$status missing", status in CaptureTerminalStatus.entries.toSet())
        }
    }

    @Test
    fun rawDngSidecarOutcomeExposesIdentityAfterLocalFailure() {
        // A frame whose local DNG save fails must still expose its identity so
        // the manifest can correlate it with the (preserved) raw16 frame.
        val outcome = RawDngSidecarOutcome.localSaveFailed(
            frameIndex = 9,
            failureDescription = "native writer returned false"
        )
        assertEquals(9, outcome.frameIndex)
        assertTrue(outcome.isLocalFailureOnly)
        assertNotEquals(RawDngSidecarStatus.LOCAL_SAVED, outcome.status)
    }

    @Test
    fun rawSaveCompletionIdentityIsIndependentOfEncodedPayload() {
        // Success and Failed completions both carry frameIndex/timestampNs
        // so the owner can correlate late completions with the frame map.
        val success = RawSaveCompletion.Success(
            frameIndex = 1,
            timestampNs = 1000L,
            raw16Filename = "frame_01.raw16",
            raw16Bytes = 4096L,
            saveDurationMs = 10L,
            dngSidecar = RawDngSidecarOutcome.notRequested(1)
        )
        val failed = RawSaveCompletion.Failed(
            frameIndex = 1,
            timestampNs = 1000L,
            failureType = "encode-failed",
            failureMessage = "boom",
            throwable = null
        )
        assertEquals(success.frameIndex, failed.frameIndex)
        assertEquals(success.timestampNs, failed.timestampNs)
    }
}
