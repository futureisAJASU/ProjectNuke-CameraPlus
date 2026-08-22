package com.projectnuke.keplernightlab

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 5: the safe early shutter-release boundary. Admission opens only at
 * the durable capture-handoff boundary (evidenced CaptureStageComplete) and
 * never while a foreground generation owns capture resources.
 */
@RunWith(RobolectricTestRunner::class)
class Phase5EarlyReleaseTest {

    private class ManualScheduler : CameraUiScheduler {
        data class Entry(val delay: Long, val work: Runnable)
        val entries = mutableListOf<Entry>()
        val removed = mutableListOf<Runnable>()

        override fun post(delayMillis: Long, work: Runnable): CameraUiDispatchOutcome {
            entries += Entry(delayMillis, work)
            return CameraUiDispatchOutcome.ACCEPTED
        }

        override fun remove(work: Runnable): Boolean {
            removed += work
            return entries.removeAll { it.work === work }
        }

        fun run(delay: Long) {
            val entry = entries.first { it.delay == delay }
            entries.remove(entry)
            entry.work.run()
        }
    }

    private fun newJobDir(): File =
        createTempDirectory("phase5-release").toFile().resolve("KPL_JOB_${System.nanoTime()}")

    private fun handoffEvent(generation: Long, jobDir: File = newJobDir()): CameraPipelineEvent.CaptureStageComplete =
        CameraPipelineEvent.CaptureStageComplete(
            generation = generation,
            counts = CameraPipelineProgressCounts(),
            message = "capture handed off",
            jobDirectoryPath = jobDir.absolutePath,
            captureResourcesSettled = true,
            processingHandoffDurable = true
        )

    private fun accepted(session: CameraPipelineUiSession, message: String): Long =
        (session.start(message, 4) as CameraPipelineUiSession.StartResult.Accepted).operation.generation

    @Test
    fun secondCapture_rejectedWhileFirstCaptureResourcesOwned() {
        val session = CameraPipelineUiSession()
        val generation = accepted(session, "first")
        assertTrue(session.snapshot().isCaptureBusy)
        assertFalse(session.snapshot().canAdmitNewCapture)
        assertTrue(session.start("second", 4) is CameraPipelineUiSession.StartResult.Rejected)

        // Still owned mid-capture.
        session.accept(CameraPipelineEvent.Started(generation, "capturing"))
        assertFalse(session.snapshot().canAdmitNewCapture)
        assertTrue(session.start("third", 4) is CameraPipelineUiSession.StartResult.Rejected)
    }

    @Test
    fun secondCapture_acceptedAfterFirstDurableHandoffWhileFirstProcessingStillActive() {
        val session = CameraPipelineUiSession()
        val generation = accepted(session, "first")
        session.accept(CameraPipelineEvent.Started(generation, "capturing"))
        session.accept(handoffEvent(generation))

        // Processing for the first burst is still active (no terminal yet).
        val duringProcessing = session.snapshot()
        assertEquals(CameraPipelineUiSession.Phase.POST_CAPTURE_PROCESSING, duringProcessing.phase)
        assertTrue(duringProcessing.isBusy)
        assertFalse(duringProcessing.isCaptureBusy)
        assertTrue(duringProcessing.canAdmitNewCapture)

        // The second burst is admitted while the first keeps processing.
        val secondGeneration = accepted(session, "second")
        assertTrue(secondGeneration > generation)
        assertTrue(session.snapshot().isCaptureBusy)
    }

    @Test
    fun preview_resumesAfterCaptureHandoffBeforeProcessingTerminal() {
        val session = CameraPipelineUiSession()
        val generation = accepted(session, "first")
        session.accept(CameraPipelineEvent.Started(generation, "capturing"))
        assertFalse(session.snapshot().previewAllowed)

        session.accept(handoffEvent(generation))
        val afterHandoff = session.snapshot()
        assertTrue(afterHandoff.previewAllowed)

        // Background stage events must not re-suppress preview.
        session.accept(
            CameraPipelineEvent.ProcessingStage(
                generation,
                CaptureStage.PROCESSING,
                CameraPipelineProgressCounts(),
                "fusion running"
            )
        )
        assertTrue(session.snapshot().previewAllowed)
        session.accept(
            CameraPipelineEvent.ExportStage(
                generation,
                CaptureStage.EXPORTING,
                CameraPipelineProgressCounts(),
                "export running"
            )
        )
        assertTrue(session.snapshot().previewAllowed)
    }

    @Test
    fun processingFailure_doesNotRelockForegroundCapture() {
        val session = CameraPipelineUiSession()
        val generation = accepted(session, "first")
        session.accept(CameraPipelineEvent.Started(generation, "capturing"))
        session.accept(handoffEvent(generation))
        session.accept(
            CameraPipelineEvent.ProcessingStage(
                generation,
                CaptureStage.PROCESSING,
                CameraPipelineProgressCounts(),
                "fusion failed"
            )
        )
        session.accept(
            CameraPipelineEvent.Terminal(
                generation = generation,
                kind = CameraPipelineEvent.Terminal.Kind.FAILED,
                captureResourcesSettled = true,
                message = "processing failed"
            )
        )
        val snapshot = session.snapshot()
        assertTrue(snapshot.canAdmitNewCapture)
        assertFalse(snapshot.isCaptureBusy)
        assertTrue(session.start("next", 4) is CameraPipelineUiSession.StartResult.Accepted)
    }

    @Test
    fun processingTimeout_doesNotBlockNextCapture() {
        val session = CameraPipelineUiSession()
        val scheduler = ManualScheduler()
        var sink: CameraPipelineEventSink? = null
        val orchestrator = CameraPipelineUiOrchestrator(
            session,
            scheduler,
            CameraPipelineUiOrchestrator.Callbacks({}, {}, {})
        )
        assertTrue(orchestrator.start("first", timeoutMillis = 1_000L) { _, _, _, events -> sink = events })
        scheduler.run(250L)
        val generation = session.snapshot().generation
        sink!!.invoke(handoffEvent(generation))
        scheduler.run(0L)

        // The capture watchdog was retired at handoff: no timer remains that
        // could cancel background processing or re-lock admission.
        assertTrue(scheduler.entries.none { it.delay == 1_000L })
        val snapshot = session.snapshot()
        assertTrue(snapshot.canAdmitNewCapture)
        assertTrue(orchestrator.start("second", timeoutMillis = 1_000L) { _, _, _, _ -> })
    }

    @Test
    fun captureCancellation_beforeHandoff_cancelsCapture() {
        val session = CameraPipelineUiSession()
        val operation = (session.start("first", 4) as CameraPipelineUiSession.StartResult.Accepted).operation
        session.accept(CameraPipelineEvent.Started(operation.generation, "capturing"))

        assertTrue(session.requestCancellation(operation.generation, "user cancel"))
        assertTrue(operation.cancellationToken.isCancelled)
        assertTrue(operation.captureCancellation.isCancelled)
        assertFalse(session.snapshot().canAdmitNewCapture)
    }

    @Test
    fun captureCancellation_afterHandoff_doesNotCancelBackgroundJob() {
        val session = CameraPipelineUiSession()
        val operation = (session.start("first", 4) as CameraPipelineUiSession.StartResult.Accepted).operation
        session.accept(CameraPipelineEvent.Started(operation.generation, "capturing"))
        session.accept(handoffEvent(operation.generation))

        // Foreground cancellation ends at settlement; the handed-off worker
        // must keep its cancellation handle untouched.
        assertFalse(session.requestCancellation(operation.generation, "user cancel"))
        assertFalse(operation.cancellationToken.isCancelled)
        assertFalse(operation.captureCancellation.isCancelled)
        assertTrue(session.snapshot().canAdmitNewCapture)
    }

    @Test
    fun screenDispose_beforeHandoff_settlesCaptureSafely() {
        val session = CameraPipelineUiSession()
        val operation = (session.start("first", 4) as CameraPipelineUiSession.StartResult.Accepted).operation
        session.accept(CameraPipelineEvent.Started(operation.generation, "capturing"))

        assertTrue(session.dispose())
        assertTrue(operation.cancellationToken.isCancelled)
        assertTrue(operation.captureCancellation.isCancelled)
        assertFalse(session.foreground.state().isCaptureOwned)
        assertFalse(session.snapshot().canAdmitNewCapture)
    }

    @Test
    fun screenDispose_afterHandoff_doesNotCancelBackgroundJob() {
        val session = CameraPipelineUiSession()
        val operation = (session.start("first", 4) as CameraPipelineUiSession.StartResult.Accepted).operation
        session.accept(CameraPipelineEvent.Started(operation.generation, "capturing"))
        session.accept(handoffEvent(operation.generation))

        assertTrue(session.dispose())
        // Already handed-off background work survives screen disposal.
        assertFalse(operation.cancellationToken.isCancelled)
        assertFalse(operation.captureCancellation.isCancelled)
    }

    @Test
    fun workerSchedulingFailure_afterDurableHandoff_preservesReprocessableJob() {
        BackgroundProcessingCoordinator.resetForTest()
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val coordinator = BackgroundProcessingCoordinator.of(context)
        val root = createTempDirectory("phase5-worker-failure").toFile()
        try {
            val failingJob = root.resolve("KPL_FAILING")
            failingJob.mkdirs()
            val durableMetadata = failingJob.resolve(JOB_JSON_FILE_NAME)
            durableMetadata.writeText("""{"jobType":"YUV_NIGHT_FUSION","status":"CAPTURING"}""")

            val secondRan = CountDownLatch(1)
            val secondJob = root.resolve("KPL_SECOND")
            secondJob.mkdirs()

            coordinator.enqueue(
                ExactJobRef(failingJob, KeplerActiveOperationKind.PROCESSING_YUV),
                HeavyProcessingWork { throw IllegalStateException("worker scheduling/execution failure") }
            )
            coordinator.enqueue(
                ExactJobRef(secondJob, KeplerActiveOperationKind.PROCESSING_RAW),
                HeavyProcessingWork { secondRan.countDown() }
            )

            // One failed job does not poison the serialized lane...
            assertTrue(secondRan.await(10, TimeUnit.SECONDS))
            // ...and the failed exact job keeps its durable, reprocessable state.
            assertTrue(durableMetadata.isFile)
            assertTrue(durableMetadata.readText().contains("YUV_NIGHT_FUSION"))
        } finally {
            BackgroundProcessingCoordinator.resetForTest()
            root.deleteRecursively()
        }
    }

    @Test
    fun handoffPersistenceFailure_doesNotPrematurelyUnlockCapture() {
        val session = CameraPipelineUiSession()
        val generation = accepted(session, "first")
        session.accept(CameraPipelineEvent.Started(generation, "capturing"))

        // A stage-complete marker WITHOUT complete evidence cannot release
        // foreground ownership: the durable handoff was not proven.
        session.accept(
            CameraPipelineEvent.CaptureStageComplete(
                generation = generation,
                counts = CameraPipelineProgressCounts(),
                message = "stage complete but handoff persistence failed",
                jobDirectoryPath = null,
                captureResourcesSettled = false,
                processingHandoffDurable = false
            )
        )
        var snapshot = session.snapshot()
        assertEquals(CameraPipelineUiSession.Phase.POST_CAPTURE_PROCESSING, snapshot.phase)
        assertTrue(snapshot.isCaptureBusy)
        assertFalse(snapshot.canAdmitNewCapture)
        assertFalse(snapshot.previewAllowed)

        // Background-style events in this state must not unlock either.
        session.accept(
            CameraPipelineEvent.ProcessingStage(
                generation,
                CaptureStage.PROCESSING,
                CameraPipelineProgressCounts(),
                "processing"
            )
        )
        snapshot = session.snapshot()
        assertFalse(snapshot.canAdmitNewCapture)
        assertFalse(snapshot.previewAllowed)
        assertTrue(session.start("second", 4) is CameraPipelineUiSession.StartResult.Rejected)

        // Only a settled terminal ends the operation.
        session.accept(
            CameraPipelineEvent.Terminal(
                generation = generation,
                kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                captureResourcesSettled = true,
                message = "done"
            )
        )
        assertTrue(session.snapshot().canAdmitNewCapture)
    }

    @Test
    fun captureOwnerLive_doesNotPrematurelyUnlockCapture() {
        val session = CameraPipelineUiSession()
        val generation = accepted(session, "first")
        // Scheduled but not yet capturing: resources are about to be owned.
        assertFalse(session.snapshot().canAdmitNewCapture)
        session.accept(CameraPipelineEvent.Started(generation, "capturing"))
        assertTrue(session.snapshot().isCaptureBusy)

        // Even a spurious processing event cannot unlock while the owner lives.
        session.accept(
            CameraPipelineEvent.ProcessingStage(
                generation,
                CaptureStage.PROCESSING,
                CameraPipelineProgressCounts(),
                "unexpected early stage event"
            )
        )
        val snapshot = session.snapshot()
        assertTrue(snapshot.isCaptureBusy)
        assertFalse(snapshot.canAdmitNewCapture)
        assertFalse(snapshot.previewAllowed)
        assertTrue(session.start("second", 4) is CameraPipelineUiSession.StartResult.Rejected)
    }
}
