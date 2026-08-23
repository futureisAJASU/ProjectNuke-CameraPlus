package com.projectnuke.keplernightlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4: the capture progress bar represents ACTUAL CAMERA ACQUISITION —
 * the paired Camera2 evidence count min(receivedImages, completedResults) over
 * requestedFrames. Persisted frames are durability truth and must never be
 * mislabeled as sensor-capture progress; fusion/export stages never reuse the
 * capture bar after durable handoff.
 */
class CameraAcquisitionProgressTest {

    private fun session(): CameraPipelineUiSession =
        CameraPipelineUiSession()

    private fun start(session: CameraPipelineUiSession, requested: Int = 4): Long =
        (session.start("start", requested) as CameraPipelineUiSession.StartResult.Accepted).operation.generation

    private fun startedEvent(generation: Long) = CameraPipelineEvent.Started(generation, "started")

    private fun progressEvent(
        generation: Long,
        requested: Int,
        received: Int,
        results: Int,
        saved: Int = 0,
        message: String? = null
    ) = CameraPipelineEvent.CaptureProgress(
        generation,
        CameraPipelineProgressCounts(
            requestedFrames = requested,
            savedFrames = saved,
            receivedImages = received,
            completedResults = results
        ),
        message
    )

    private fun evidenceHandoff(generation: Long) = CameraPipelineEvent.CaptureStageComplete(
        generation = generation,
        counts = CameraPipelineProgressCounts(),
        message = "stage complete",
        jobDirectoryPath = "/job",
        captureResourcesSettled = true,
        processingHandoffDurable = true
    )

    @Test
    fun started_thenFirstFrame_movesBeyondPreparing() {
        val session = session()
        val gen = start(session)
        assertEquals(CameraPipelineUiSession.EventResult.ACCEPTED, session.accept(startedEvent(gen)))
        // Started alone keeps the conservative preparing state.
        assertEquals(CaptureStage.PREPARING, session.snapshot().captureProgress.stage)
        assertEquals(0.05f, session.snapshot().captureProgress.progressPercent, 1e-6f)
        // The FIRST acquired frame moves the bar beyond preparing immediately.
        assertEquals(
            CameraPipelineUiSession.EventResult.ACCEPTED,
            session.accept(progressEvent(gen, requested = 4, received = 1, results = 1))
        )
        assertEquals(CaptureStage.CAPTURING, session.snapshot().captureProgress.stage)
        assertEquals(0.25f, session.snapshot().captureProgress.progressPercent, 1e-6f)
    }

    @Test
    fun typedProgress_updates25_50_75_100() {
        val session = session()
        val gen = start(session)
        session.accept(startedEvent(gen))
        listOf(1 to 0.25f, 2 to 0.50f, 3 to 0.75f, 4 to 1.00f).forEach { (frames, expected) ->
            session.accept(progressEvent(gen, requested = 4, received = frames, results = frames))
            assertEquals(expected, session.snapshot().captureProgress.progressPercent, 1e-6f)
        }
    }

    @Test
    fun receivedWithoutResult_doesNotPrematurelyAdvancePairCount() {
        val session = session()
        val gen = start(session)
        session.accept(startedEvent(gen))
        // Two images arrived but only one result paired: acquisition is ONE frame.
        session.accept(progressEvent(gen, requested = 4, received = 2, results = 1))
        assertEquals(0.25f, session.snapshot().captureProgress.progressPercent, 1e-6f)
        assertEquals(
            1,
            cameraAcquisitionPairCount(receivedImages = 2, completedResults = 1)
        )
    }

    @Test
    fun resultWithoutImage_doesNotPrematurelyAdvancePairCount() {
        val session = session()
        val gen = start(session)
        session.accept(startedEvent(gen))
        // Results may outrun images when callbacks arrive out of order.
        session.accept(progressEvent(gen, requested = 4, received = 1, results = 3))
        assertEquals(0.25f, session.snapshot().captureProgress.progressPercent, 1e-6f)
        assertEquals(
            1,
            cameraAcquisitionPairCount(receivedImages = 1, completedResults = 3)
        )
    }

    @Test
    fun captureProgress_isMonotonic() {
        val session = session()
        val gen = start(session)
        session.accept(startedEvent(gen))
        // Jumbled callback order: counters advance unevenly but the shown bar
        // must never regress.
        val samples = listOf(
            Pair(4, 0), Pair(4, 1), Pair(2, 1), Pair(3, 3), Pair(4, 2), Pair(4, 3), Pair(4, 4)
        )
        var previous = 0f
        samples.forEach { (received, results) ->
            session.accept(progressEvent(gen, requested = 4, received = received, results = results))
            val current = session.snapshot().captureProgress.progressPercent
            assertTrue(
                "bar regressed: $current < $previous",
                current >= previous - 1e-6f
            )
            previous = current
        }
        assertEquals(1.00f, session.snapshot().captureProgress.progressPercent, 1e-6f)
    }

    @Test
    fun fourCameraFramesCaptured_onePersisted_stillShowsCapture100Percent() {
        val session = session()
        val gen = start(session)
        session.accept(startedEvent(gen))
        // All four frames acquired by Camera2; persistence has only written one.
        session.accept(progressEvent(gen, requested = 4, received = 4, results = 4, saved = 1))
        assertEquals(1.00f, session.snapshot().captureProgress.progressPercent, 1e-6f)
        // A persistence-only update cannot move the capture bar either way:
        // persisted frames are not sensor-capture progress.
        assertEquals(1.00f, cameraAcquisitionProgressFraction(requestedFrames = 4, receivedImages = 4, completedResults = 4), 1e-6f)
        assertEquals(0.75f, cameraAcquisitionProgressFraction(requestedFrames = 4, receivedImages = 4, completedResults = 3), 1e-6f)
        assertEquals(0f, cameraAcquisitionProgressFraction(requestedFrames = 0, receivedImages = 9, completedResults = 9), 1e-6f)
    }

    @Test
    fun persistenceDrain_afterCapture100_usesSettlingText() {
        val session = session()
        val gen = start(session)
        session.accept(startedEvent(gen))
        session.accept(progressEvent(gen, requested = 4, received = 4, results = 4, saved = 4))
        // Acquisition complete while persistence drains: distinct short state.
        session.accept(
            progressEvent(
                gen,
                requested = 4, received = 4, results = 4, saved = 4,
                message = CAPTURE_SETTLING_MESSAGE
            )
        )
        assertEquals(CAPTURE_SETTLING_MESSAGE, session.snapshot().captureStatus)
        assertEquals(CAPTURE_SETTLING_MESSAGE, session.snapshot().captureProgress.message)
        // The bar HOLDS at 100% during settlement.
        assertEquals(1.00f, session.snapshot().captureProgress.progressPercent, 1e-6f)
    }

    @Test
    fun backgroundProcessing_doesNotReuseCaptureBar() {
        val session = session()
        val gen = start(session)
        session.accept(startedEvent(gen))
        session.accept(progressEvent(gen, requested = 4, received = 4, results = 4, saved = 1))
        // Durable handoff settles the foreground slot (the handoff boundary
        // itself is a legitimate capture-stage transition)...
        assertEquals(CameraPipelineUiSession.EventResult.ACCEPTED, session.accept(evidenceHandoff(gen)))
        val before = session.snapshot().captureProgress
        // ...then background fusion/export stages update ONLY the background
        // surface; the capture progress surface stays untouched.
        assertEquals(
            CameraPipelineUiSession.EventResult.ACCEPTED,
            session.accept(
                CameraPipelineEvent.ProcessingStage(
                    generation = gen,
                    stage = CaptureStage.PROCESSING,
                    counts = CameraPipelineProgressCounts(requestedFrames = 4, savedFrames = 4),
                    message = "Night Fusion 처리 중..."
                )
            )
        )
        assertEquals(before, session.snapshot().captureProgress)
        assertEquals("Night Fusion 처리 중...", session.snapshot().backgroundStatus)
        assertEquals(
            CameraPipelineUiSession.EventResult.ACCEPTED,
            session.accept(
                CameraPipelineEvent.ExportStage(
                    generation = gen,
                    counts = CameraPipelineProgressCounts(requestedFrames = 4, savedFrames = 4),
                    message = "갤러리에 저장 중..."
                )
            )
        )
        assertEquals(before, session.snapshot().captureProgress)
        assertEquals("갤러리에 저장 중...", session.snapshot().backgroundStatus)
    }
}
