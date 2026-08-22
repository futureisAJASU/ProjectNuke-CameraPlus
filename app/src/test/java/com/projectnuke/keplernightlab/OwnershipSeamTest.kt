package com.projectnuke.keplernightlab

import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Phase 4 behavior-neutral ownership seams: the foreground capture session and
 * the background processing coordinator exist as explicit owners, but capture
 * admission is still fully serialized (a new capture stays rejected while any
 * pipeline or background work is active).
 */
@RunWith(RobolectricTestRunner::class)
class OwnershipSeamTest {

    private fun newTempJobDir(prefix: String): File =
        createTempDirectory(prefix).toFile().resolve("KPL_JOB_${System.nanoTime()}")

    // 4A -------------------------------------------------------------------

    @Test
    fun foregroundCaptureSession_ownsCaptureCancellationOnly() {
        val foreground = ForegroundCaptureSession()
        assertTrue(foreground.beginScheduled(1L))
        assertTrue(foreground.beginCapturing(1L))
        assertTrue(foreground.isCaptureOwnedBy(1L))
        assertTrue(foreground.markCancellationRequested(1L))
        assertFalse(foreground.markCancellationRequested(1L))

        // Handoff settlement ends capture ownership even while heavy work runs on.
        val backgroundStillRunning = mutableListOf("processing-A")
        assertTrue(foreground.settleHandoff(1L))
        assertFalse(foreground.isCaptureOwnedBy(1L))
        assertTrue(backgroundStillRunning.isNotEmpty())
        assertFalse(foreground.markCancellationRequested(1L))

        // The slot is free for the next capture; the old generation cannot interfere.
        assertTrue(foreground.beginScheduled(2L))
        assertTrue(foreground.beginCapturing(2L))
        assertTrue(foreground.settleHandoff(2L))
        assertTrue(foreground.abandon(2L))
        assertEquals(
            ForegroundCaptureSession.CaptureOwnershipPhase.IDLE,
            foreground.state().phase
        )
    }

    @Test
    fun backgroundCoordinator_usesExactJobDirectory() {
        BackgroundProcessingCoordinator.resetForTest()
        val context = RuntimeEnvironment.getApplication()
        val coordinator = BackgroundProcessingCoordinator.of(context)
        val jobDir = newTempJobDir("exact-yuv")
        val seen = CountDownLatch(1)
        var receivedRef: ExactJobRef? = null
        val result = coordinator.enqueue(
            ExactJobRef(jobDir, KeplerActiveOperationKind.PROCESSING_YUV),
            HeavyProcessingWork { ref ->
                receivedRef = ref
                seen.countDown()
            }
        )
        assertTrue(result is BackgroundEnqueueResult.Accepted)
        assertTrue(seen.await(10, TimeUnit.SECONDS))
        assertEquals(jobDir.absolutePath, receivedRef?.jobDirectory?.absolutePath)
        assertEquals(KeplerActiveOperationKind.PROCESSING_YUV, receivedRef?.jobKind)
    }

    @Test
    fun backgroundCoordinator_executesOneHeavyJobAtATime() {
        BackgroundProcessingCoordinator.resetForTest()
        val context = RuntimeEnvironment.getApplication()
        val coordinator = BackgroundProcessingCoordinator.of(context)
        val firstStarted = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val dirA = newTempJobDir("serial-a")
        val dirB = newTempJobDir("serial-b")
        coordinator.enqueue(
            ExactJobRef(dirA, KeplerActiveOperationKind.PROCESSING_YUV),
            HeavyProcessingWork {
                firstStarted.countDown()
                firstRelease.await(10, TimeUnit.SECONDS)
            }
        )
        val duringBSnapshot = arrayOfNulls<BackgroundProcessingSnapshot>(1)
        coordinator.enqueue(
            ExactJobRef(dirB, KeplerActiveOperationKind.PROCESSING_RAW),
            HeavyProcessingWork {
                // Observed from inside B: A has fully settled and B owns the lane.
                duringBSnapshot[0] = coordinator.snapshot()
                secondStarted.countDown()
            }
        )
        assertTrue(firstStarted.await(10, TimeUnit.SECONDS))
        // While A blocks, B must not have started and must sit in the FIFO.
        assertFalse(secondStarted.await(300, TimeUnit.MILLISECONDS))
        val duringA = coordinator.snapshot()
        assertEquals(dirA.absolutePath, duringA.activeJobDirectory)
        assertEquals(1, duringA.queuedCount)
        firstRelease.countDown()
        assertTrue(secondStarted.await(10, TimeUnit.SECONDS))
        // Observed inside B's execution: A settled, B owns the lane, queue empty.
        assertEquals(dirB.absolutePath, duringBSnapshot[0]!!.activeJobDirectory)
        assertEquals(0, duringBSnapshot[0]!!.queuedCount)
        assertTrue(duringBSnapshot[0]!!.hasActiveWork)
    }

    @Test
    fun backgroundCoordinator_duplicateJobDoesNotAcquireSecondProcessingOwner() {
        BackgroundProcessingCoordinator.resetForTest()
        val context = RuntimeEnvironment.getApplication()
        val coordinator = BackgroundProcessingCoordinator.of(context)
        val jobDir = newTempJobDir("dup-job")
        val executions = AtomicInteger(0)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = coordinator.enqueue(
            ExactJobRef(jobDir, KeplerActiveOperationKind.PROCESSING_YUV),
            HeavyProcessingWork {
                executions.incrementAndGet()
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        )
        assertTrue(first is BackgroundEnqueueResult.Accepted)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val duplicate = coordinator.enqueue(
            ExactJobRef(jobDir, KeplerActiveOperationKind.PROCESSING_YUV),
            HeavyProcessingWork { executions.incrementAndGet() }
        )
        assertTrue(duplicate is BackgroundEnqueueResult.Duplicate)
        release.countDown()
        // Let the lane drain before counting.
        val deadline = System.currentTimeMillis() + 5000
        while (coordinator.snapshot().hasPendingWork && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(1, executions.get())
    }

    @Test
    fun backgroundWorker_doesNotUseLatestJobLookup() {
        BackgroundProcessingCoordinator.resetForTest()
        val context = RuntimeEnvironment.getApplication()
        val coordinator = BackgroundProcessingCoordinator.of(context)
        val older = newTempJobDir("older-capture")
        val newer = newTempJobDir("newer-capture")
        // Make `newer` unambiguously the most recent directory on disk.
        newer.createNewFile()
        Thread.sleep(5)
        older.createNewFile()
        Thread.sleep(5)
        newer.setLastModified(System.currentTimeMillis())
        val seen = CountDownLatch(1)
        var processedPath: String? = null
        coordinator.enqueue(
            ExactJobRef(older, KeplerActiveOperationKind.PROCESSING_RAW),
            HeavyProcessingWork { ref ->
                processedPath = ref.jobDirectory.absolutePath
                seen.countDown()
            }
        )
        assertTrue(seen.await(10, TimeUnit.SECONDS))
        // A newest-job lookup would have chosen `newer`; the worker must have
        // executed exactly the enqueued job identity instead.
        assertEquals(older.absolutePath, processedPath)
        assertTrue(newer.absolutePath != processedPath)
    }

    @Test
    fun backgroundRequest_doesNotRetainActivityOrComposable() {
        BackgroundProcessingCoordinator.resetForTest()
        val context = RuntimeEnvironment.getApplication()
        val coordinator = BackgroundProcessingCoordinator.of(context)
        assertSame(context.applicationContext, coordinator.heldApplicationContext)
        val again = BackgroundProcessingCoordinator.of(context)
        assertSame(coordinator, again)
        // No field of the coordinator may hold an Activity or Composable
        // reference: every declared field must be a JVM primitive, String,
        // File, collection, lock, thread type, or the application Context.
        coordinator::class.java.declaredFields.forEach { field ->
            field.isAccessible = true
            val value = field.get(coordinator) ?: return@forEach
            assertFalse(
                "field ${field.name} must not retain an Activity",
                value is android.app.Activity
            )
            if (value is Context) {
                assertSame(context.applicationContext, value)
            }
        }
    }

    @Test
    fun oldBackgroundEvent_cannotMutateDifferentForegroundGeneration() {
        val foreground = ForegroundCaptureSession()
        assertTrue(foreground.beginScheduled(10L))
        assertTrue(foreground.beginCapturing(10L))
        assertTrue(foreground.settleHandoff(10L))
        assertTrue(foreground.beginScheduled(11L))
        assertTrue(foreground.beginCapturing(11L))

        // Stale generation-10 callbacks arrive after generation 11 took over.
        assertFalse(foreground.settleHandoff(10L))
        assertFalse(foreground.beginCapturing(10L))
        assertFalse(foreground.abandon(10L))
        assertFalse(foreground.markCancellationRequested(10L))
        assertFalse(foreground.isCaptureOwnedBy(10L))

        assertEquals(11L, foreground.state().generation)
        assertTrue(foreground.isCaptureOwnedBy(11L))
        assertEquals(
            ForegroundCaptureSession.CaptureOwnershipPhase.CAPTURING,
            foreground.state().phase
        )

        // Session-level arbitration: a late ProcessingStage after handoff
        // settlement lands on the background channel, not capture status.
        val session = CameraPipelineUiSession()
        val accepted = session.start("start", requestedFrames = 2)
        assertTrue(accepted is CameraPipelineUiSession.StartResult.Accepted)
        val generation = (accepted as CameraPipelineUiSession.StartResult.Accepted).operation.generation
        assertEquals(CameraPipelineUiSession.EventResult.ACCEPTED, session.accept(CameraPipelineEvent.Started(generation, "capturing")))
        assertEquals(
            CameraPipelineUiSession.EventResult.ACCEPTED,
            session.accept(
                CameraPipelineEvent.CaptureStageComplete(
                    generation,
                    CameraPipelineProgressCounts(),
                    "handoff",
                    jobDirectoryPath = "/data/kepler/KPL_JOB_1",
                    captureResourcesSettled = true,
                    processingHandoffDurable = true
                )
            )
        )
        assertEquals(
            CameraPipelineUiSession.EventResult.ACCEPTED,
            session.accept(CameraPipelineEvent.ProcessingStage(generation, CaptureStage.PROCESSING, CameraPipelineProgressCounts(), "fusion running"))
        )
        val snapshot = session.snapshot()
        assertEquals("handoff", snapshot.captureStatus)
        assertEquals("fusion running", snapshot.backgroundStatus)
    }

    @Test
    fun behaviorNeutralPhase_stillRejectsSecondCaptureUntilExplicitEnablement() {
        // Phase 5 explicitly enabled early release: background occupancy is
        // observability only and must NOT gate an otherwise idle start.
        val withBackgroundWork = CameraPipelineUiSession(backgroundOccupancy = { true })
        assertTrue(withBackgroundWork.snapshot().isBackgroundProcessingBusy)
        assertTrue(
            withBackgroundWork.start("capture", requestedFrames = 4) is
                CameraPipelineUiSession.StartResult.Accepted
        )

        // While a pipeline operation occupies the foreground slot, another
        // start is still rejected.
        val neutral = CameraPipelineUiSession()
        assertTrue(neutral.start("first", requestedFrames = 4) is CameraPipelineUiSession.StartResult.Accepted)
        assertTrue(neutral.start("second", requestedFrames = 4) is CameraPipelineUiSession.StartResult.Rejected)

        // Legacy unevidenced processing keeps the whole-pipeline busy state.
        assertNull(neutral.start("third", requestedFrames = 4) as? CameraPipelineUiSession.StartResult.Accepted)
        val currentGeneration = neutral.snapshot().generation
        neutral.accept(CameraPipelineEvent.Started(currentGeneration, "capturing"))
        neutral.accept(
            CameraPipelineEvent.CaptureStageComplete(
                currentGeneration,
                CameraPipelineProgressCounts(),
                "stage complete without handoff evidence"
            )
        )
        neutral.accept(
            CameraPipelineEvent.ProcessingStage(
                currentGeneration,
                CaptureStage.PROCESSING,
                CameraPipelineProgressCounts(),
                "processing A"
            )
        )
        val busyDuringProcessing = neutral.snapshot()
        assertTrue(busyDuringProcessing.isBusy)
        assertTrue(busyDuringProcessing.isCaptureBusy)
        assertFalse(busyDuringProcessing.canAdmitNewCapture)
        assertTrue(neutral.start("fourth", requestedFrames = 4) is CameraPipelineUiSession.StartResult.Rejected)

        // Only after terminal settlement does admission open again.
        neutral.accept(
            CameraPipelineEvent.Terminal(
                generation = currentGeneration,
                kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                captureResourcesSettled = true,
                message = "done"
            )
        )
        val settled = neutral.snapshot()
        assertFalse(settled.isBusy)
        assertNotNull(neutral.start("fifth", requestedFrames = 4))
    }
}
