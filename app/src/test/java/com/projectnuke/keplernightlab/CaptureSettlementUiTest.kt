package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4: the capture surface must distinguish real sensor acquisition
 * (CAPTURING, 0..100%) from durable settlement after 100%
 * (SETTLING_CAPTURE), using PERSISTED frames - never camera pair fractions -
 * for settlement text, and must never drop the bar below 100%.
 */
class CaptureSettlementUiTest {

    private fun progress(
        requested: Int,
        received: Int,
        completed: Int,
        saved: Int,
        stage: CaptureStage = CaptureStage.CAPTURING,
        percent: Float = cameraAcquisitionProgressFraction(requested, received, completed)
    ) = CaptureProgressState(
        stage = stage,
        requestedFrames = requested,
        savedFrames = saved,
        receivedImages = received,
        completedResults = completed,
        progressPercent = percent
    )

    @Test
    fun rawAcquisition100_thenPersistencePending_showsSettling() {
        val view = captureSettlementView(
            progress(requested = 4, received = 4, completed = 4, saved = 2)
        )
        assertEquals(CaptureDisplayState.SETTLING_CAPTURE, view.state)
        assertTrue(view.captureComplete)
        assertEquals("100%", view.percentText)
        // Truthful persistence count in the detail line.
        assertEquals("촬영 데이터 저장 중 · 2/4", captureSettlementDetailText(view))
    }

    @Test
    fun settlingProgress_usesPersistedFramesNotCameraFraction() {
        // Camera pairs are complete; only the PERSISTED count may drive the
        // settlement line. saved=1 must render 1/4 even though pairs say 4/4.
        val view = captureSettlementView(
            progress(requested = 4, received = 4, completed = 4, saved = 1)
        )
        assertEquals(CaptureDisplayState.SETTLING_CAPTURE, view.state)
        assertEquals(1, view.persistedFrames)
        assertEquals("촬영 데이터 저장 중 · 1/4", captureSettlementDetailText(view))

        val allPersisted = captureSettlementView(
            progress(requested = 4, received = 4, completed = 4, saved = 4)
        )
        assertEquals(CAPTURE_SETTLING_MESSAGE, captureSettlementDetailText(allPersisted))
    }

    @Test
    fun acquiring_belowHundred_staysCapturing() {
        val view = captureSettlementView(
            progress(requested = 4, received = 2, completed = 2, saved = 1)
        )
        assertEquals(CaptureDisplayState.CAPTURING, view.state)
        assertEquals("", captureSettlementDetailText(view))
        assertEquals("50%", view.percentText)
    }

    @Test
    fun settlingNeverDropsCapturePercentBelow100() {
        val session = CameraPipelineUiSession()
        val started = session.start("start", requestedFrames = 4)
        assertTrue(started is CameraPipelineUiSession.StartResult.Accepted)
        val generation = (started as CameraPipelineUiSession.StartResult.Accepted).operation.generation

        assertEquals(
            CameraPipelineUiSession.EventResult.ACCEPTED,
            session.accept(CameraPipelineEvent.Started(generation))
        )
        // Acquisition reaches the full 4/4 pair count.
        assertEquals(
            CameraPipelineUiSession.EventResult.ACCEPTED,
            session.accept(
                CameraPipelineEvent.CaptureProgress(
                    generation,
                    CameraPipelineProgressCounts(4, 2, 4, 4)
                )
            )
        )
        assertEquals(1f, session.snapshot().captureProgress.progressPercent)

        // A later legacy processing stage implies a lower fraction; the bar
        // must stay at 100% and remain a settling view.
        assertEquals(
            CameraPipelineUiSession.EventResult.ACCEPTED,
            session.accept(
                CameraPipelineEvent.ProcessingStage(
                    generation,
                    CaptureStage.PROCESSING,
                    CameraPipelineProgressCounts(4, 2, 4, 4)
                )
            )
        )
        val snap = session.snapshot()
        assertEquals(1f, snap.captureProgress.progressPercent)
        assertEquals(
            CaptureDisplayState.SETTLING_CAPTURE,
            captureSettlementView(snap.captureProgress).state
        )
        // The shutter stays gated while durable handoff is not yet proven.
        assertFalse(snap.canAdmitNewCapture)
    }

    @Test
    fun captureStageComplete_reEnablesShutter() {
        val session = CameraPipelineUiSession()
        val started = session.start("start", requestedFrames = 4) as
            CameraPipelineUiSession.StartResult.Accepted
        val generation = started.operation.generation
        session.accept(CameraPipelineEvent.Started(generation))
        assertFalse(session.snapshot().canAdmitNewCapture)

        assertEquals(
            CameraPipelineUiSession.EventResult.ACCEPTED,
            session.accept(
                CameraPipelineEvent.CaptureStageComplete(
                    generation,
                    counts = CameraPipelineProgressCounts(4, 4, 4, 4),
                    jobDirectoryPath = "/data/job",
                    captureResourcesSettled = true,
                    processingHandoffDurable = true
                )
            )
        )
        val snap = session.snapshot()
        assertTrue(snap.canAdmitNewCapture)
        assertEquals(
            CaptureDisplayState.RELEASED,
            captureSettlementView(snap.captureProgress, snap.captureResourcesSettled).state
        )
    }

    @Test
    fun backgroundProcessing_doesNotKeepShutterDisabled() {
        // Background occupancy is visible for observability but must NEVER
        // gate shutter admission after durable handoff.
        val session = CameraPipelineUiSession(backgroundOccupancy = { true })
        val started = session.start("start", requestedFrames = 4) as
            CameraPipelineUiSession.StartResult.Accepted
        val generation = started.operation.generation
        session.accept(CameraPipelineEvent.Started(generation))
        session.accept(
            CameraPipelineEvent.CaptureStageComplete(
                generation,
                counts = CameraPipelineProgressCounts(4, 4, 4, 4),
                jobDirectoryPath = "/data/job",
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )
        val snap = session.snapshot()
        assertTrue(snap.backgroundProcessingActive)
        assertTrue(snap.canAdmitNewCapture)
        // And a new capture can actually be admitted while work continues.
        assertTrue(session.start("next", 1) is CameraPipelineUiSession.StartResult.Accepted)
    }

    @Test
    fun backgroundA_terminal_doesNotOverwriteCaptureB() {
        val session = CameraPipelineUiSession()
        // Job A runs as generation 0 (background routing identity).
        // Capture B starts as the current foreground generation.
        val b = session.start("capture B", requestedFrames = 4) as
            CameraPipelineUiSession.StartResult.Accepted
        val generationB = b.operation.generation
        session.accept(CameraPipelineEvent.Started(generationB))
        session.accept(
            CameraPipelineEvent.CaptureProgress(
                generationB,
                CameraPipelineProgressCounts(4, 1, 4, 0),
                "capture B progress"
            )
        )
        val before = session.snapshot()

        // Terminal of job A arrives with A's generation: STALE for foreground.
        assertEquals(
            CameraPipelineUiSession.EventResult.STALE,
            session.accept(
                CameraPipelineEvent.Terminal(
                    generation = 0L,
                    kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                    jobDirectoryPath = "/data/jobA"
                )
            )
        )
        val after = session.snapshot()
        assertEquals(before.phase, after.phase)
        assertEquals(before.captureProgress, after.captureProgress)
        assertEquals(before.canAdmitNewCapture, after.canAdmitNewCapture)
        // B's foreground state was not terminal-claimed by A's stale terminal.
        assertEquals(null, after.terminal)
    }
}
