package com.projectnuke.keplernightlab

import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Phase 5: the background-processing status surface is observational truth
 * derived from the coordinator snapshot. It reports active/queued counts in
 * formal Korean, never blocks shutter admission, and foreground capture always
 * owns the primary surface.
 */
@RunWith(RobolectricTestRunner::class)
class BackgroundQueueUiTest {

    @Before
    fun resetHub() {
        BackgroundPipelineEventHub.resetForTest()
    }

    private fun snapshot(active: String?, queued: Int, kind: KeplerActiveOperationKind? = null) =
        BackgroundProcessingSnapshot(
            activeJobDirectory = active,
            activeJobKind = kind,
            activeSequence = if (active != null) 1L else null,
            queuedCount = queued,
            queuedJobDirectories = (1..queued).map { "/q/$it" }
        )

    @Test
    fun backgroundQueue_labels_areFormalKoreanAndBounded() {
        val idle = backgroundQueueUiModel(snapshot(null, 0))
        assertNull(idle.combinedLabel())
        assertFalse(idle.visible)

        val activeOnly = backgroundQueueUiModel(snapshot("/job/a", 0))
        assertEquals("사진을 처리하고 있습니다.", activeOnly.combinedLabel())

        val activePlusQueued = backgroundQueueUiModel(
            snapshot("/job/a", 2, KeplerActiveOperationKind.PROCESSING_RAW)
        )
        assertEquals("사진을 처리하고 있습니다. · 처리 대기 2건", activePlusQueued.combinedLabel())
        assertEquals("PROCESSING_RAW", requireNotNull(activePlusQueued.activeKindName))

        val completed = backgroundQueueUiModel(snapshot(null, 0), showCompletionFlash = true)
        assertEquals("사진 처리가 완료되었습니다.", completed.combinedLabel())

        // No completion flash while work is still pending.
        val busyFlash = backgroundQueueUiModel(snapshot("/job/a", 1), showCompletionFlash = true)
        assertEquals("사진을 처리하고 있습니다. · 처리 대기 1건", busyFlash.combinedLabel())
    }

    @Test
    fun backgroundQueue_oneActive_shutterStillAdmittable() {
        val appContext = RuntimeEnvironment.getApplication() as Context
        BackgroundProcessingCoordinator.resetForTest()
        val coordinator = BackgroundProcessingCoordinator.of(appContext)
        val release = CountDownLatch(1)
        try {
            var executed = false
            coordinator.backgroundExecutor = BackgroundProcessingExecutor { _, _ ->
                executed = true
                release.await()
            }
            val session = CameraPipelineUiSession(
                backgroundOccupancy = { coordinator.snapshot().hasPendingWork }
            )
            val started = session.start("capture", 4) as
                CameraPipelineUiSession.StartResult.Accepted
            session.accept(CameraPipelineEvent.Started(started.operation.generation))
            session.accept(
                CameraPipelineEvent.CaptureStageComplete(
                    started.operation.generation,
                    counts = CameraPipelineProgressCounts(4, 4, 4, 4),
                    jobDirectoryPath = "/data/job",
                    captureResourcesSettled = true,
                    processingHandoffDurable = true
                )
            )

            val jobDir = File(appContext.filesDir, "bgq-one-active")
            jobDir.mkdirs()
            assertEquals(
                BackgroundEnqueueResult.Accepted,
                coordinator.enqueue(
                    BackgroundProcessingRequest(jobDir, KeplerActiveOperationKind.PROCESSING_RAW)
                )
            )
            assertTrue(awaitUntil(5_000) {
                backgroundQueueUiModel(coordinator.snapshot()).active
            })
            // One active background job: observability yes, shutter gating no.
            assertTrue(session.snapshot().backgroundProcessingActive)
            assertTrue(session.snapshot().canAdmitNewCapture)
            assertTrue(session.start("next", 1) is CameraPipelineUiSession.StartResult.Accepted)
            assertTrue(executed || awaitUntil(5_000) { executed })
        } finally {
            release.countDown()
            awaitUntil(5_000) { !coordinator.snapshot().hasPendingWork }
            BackgroundProcessingCoordinator.resetForTest()
        }
    }

    @Test
    fun backgroundQueue_activePlusQueued_reportsCounts() {
        val appContext = RuntimeEnvironment.getApplication() as Context
        BackgroundProcessingCoordinator.resetForTest()
        val coordinator = BackgroundProcessingCoordinator.of(appContext)
        val firstRunning = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        try {
            coordinator.backgroundExecutor = BackgroundProcessingExecutor { _, _ ->
                firstRunning.countDown()
                releaseFirst.await()
            }
            val dirA = File(appContext.filesDir, "bgq-a").apply { mkdirs() }
            val dirB = File(appContext.filesDir, "bgq-b").apply { mkdirs() }
            coordinator.enqueue(
                BackgroundProcessingRequest(dirA, KeplerActiveOperationKind.PROCESSING_YUV)
            )
            assertTrue(awaitUntil(5_000) { firstRunning.await(0, TimeUnit.MILLISECONDS) })
            coordinator.enqueue(
                BackgroundProcessingRequest(dirB, KeplerActiveOperationKind.PROCESSING_RAW)
            )
            // FIFO: B queues behind A; the surface reports exact counts.
            assertEquals(listOf(dirB.absolutePath), coordinator.queuedOrder())
            val model = backgroundQueueUiModel(coordinator.snapshot())
            assertTrue(model.active)
            assertEquals(1, model.queuedCount)
            assertEquals("사진을 처리하고 있습니다. · 처리 대기 1건", model.combinedLabel())
            assertEquals("PROCESSING_YUV", model.activeKindName)
        } finally {
            releaseFirst.countDown()
            awaitUntil(5_000) { !coordinator.snapshot().hasPendingWork }
            BackgroundProcessingCoordinator.resetForTest()
        }
    }

    @Test
    fun foregroundCapture_hasUiPriorityOverBackgroundJob() {
        val session = CameraPipelineUiSession(backgroundOccupancy = { true })
        val a = session.start("A", 4) as CameraPipelineUiSession.StartResult.Accepted
        session.accept(CameraPipelineEvent.Started(a.operation.generation))
        session.accept(
            CameraPipelineEvent.CaptureStageComplete(
                a.operation.generation,
                counts = CameraPipelineProgressCounts(4, 4, 4, 4),
                jobDirectoryPath = "/data/jobA",
                captureResourcesSettled = true,
                processingHandoffDurable = true
            )
        )
        // A late background stage of job A routes to the BACKGROUND surface.
        assertEquals(
            CameraPipelineUiSession.EventResult.ACCEPTED,
            session.accept(
                CameraPipelineEvent.ProcessingStage(
                    a.operation.generation,
                    CaptureStage.PROCESSING,
                    CameraPipelineProgressCounts(4, 4, 4, 4),
                    "A processing"
                )
            )
        )
        val midBackground = session.snapshot()
        assertEquals("A processing", midBackground.backgroundStatus)
        // A's background status must not leak into the capture status surface.
        assertTrue(midBackground.captureStatus != "A processing")

        // Foreground capture B starts and OWNS the capture surface.
        val b = session.start("B", 4) as CameraPipelineUiSession.StartResult.Accepted
        session.accept(CameraPipelineEvent.Started(b.operation.generation))
        val duringB = session.snapshot()
        assertTrue(duringB.isCapturing)
        assertEquals("B", duringB.captureProgress.message)
    }

    @Test
    fun backgroundTerminal_oldJob_doesNotHijackForeground() {
        val session = CameraPipelineUiSession()
        val b = session.start("capture B", 4) as CameraPipelineUiSession.StartResult.Accepted
        session.accept(CameraPipelineEvent.Started(b.operation.generation))

        val jobDir = createTempDir("bgq-hijack-jobA")
        val refreshedPreview = mutableListOf<Boolean>()
        val dispatcher = BackgroundTerminalUiDispatcher(
            session = session,
            scheduler = object : CameraUiScheduler {
                override fun post(delayMillis: Long, work: Runnable): CameraUiDispatchOutcome {
                    work.run()
                    return CameraUiDispatchOutcome.ACCEPTED
                }

                override fun remove(work: Runnable): Boolean = true
            },
            recordDiagnostic = {},
            refreshResult = { showPreview, _ -> refreshedPreview.add(showPreview) }
        )
        dispatcher.onBackgroundEvent(
            BackgroundPipelineEvent(
                requestJobDirectory = jobDir,
                jobKind = KeplerActiveOperationKind.PROCESSING_RAW,
                event = CameraPipelineEvent.Terminal(
                    generation = 0L,
                    kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                    requiredOutputCommitted = true,
                    publicExportCommitted = true,
                    verified = true,
                    jobDirectoryPath = jobDir.absolutePath
                )
            )
        )
        // Data refresh happened, but B is capturing so NO preview may pop.
        assertEquals(listOf(false), refreshedPreview)
        assertTrue(session.snapshot().isCapturing)
    }

    @Test
    fun disposedScreen_doesNotRemainSubscribed() {
        var delivered = 0
        val subscription = BackgroundPipelineEventHub.subscribe {
            delivered++
        }
        assertFalse(subscription.isDisposed())
        subscription.dispose()
        assertTrue(subscription.isDisposed())
        val before = delivered
        BackgroundPipelineEventHub.publish(
            BackgroundPipelineEvent(
                requestJobDirectory = File("C:/data/jobX"),
                jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
                event = CameraPipelineEvent.Terminal(
                    generation = 0L,
                    kind = CameraPipelineEvent.Terminal.Kind.COMPLETE
                )
            )
        )
        assertEquals(before, delivered)
    }
}

private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(25)
    }
    return condition()
}
