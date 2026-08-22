package com.projectnuke.keplernightlab

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Phase 6: all production capture modes hand off to ONE serialized background
 * lane at the durable capture-handoff boundary, so a new burst may start while
 * previous fusion/export continues - never two heavy processors at once.
 */
@RunWith(RobolectricTestRunner::class)
class Phase6BackgroundLaneTest {

    private val root = createTempDirectory("phase6-lane").toFile()

    @Before
    fun resetCoordinator() {
        BackgroundProcessingCoordinator.resetForTest()
    }

    @After
    fun cleanup() {
        BackgroundProcessingCoordinator.resetForTest()
        root.deleteRecursively()
    }

    private fun newJobDir(prefix: String): File =
        root.resolve("${prefix}_${System.nanoTime()}").apply { mkdirs() }

    private fun awaitDrain(coordinator: BackgroundProcessingCoordinator, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (coordinator.snapshot().hasPendingWork && System.currentTimeMillis() < deadline) {
            Thread.sleep(15)
        }
    }

    private fun handoffEvent(generation: Long, jobDir: File) =
        CameraPipelineEvent.CaptureStageComplete(
            generation = generation,
            counts = CameraPipelineProgressCounts(),
            message = "handoff",
            jobDirectoryPath = jobDir.absolutePath,
            captureResourcesSettled = true,
            processingHandoffDurable = true
        )

    // Capture N+1 while burst N processes ----------------------------------

    @Test
    fun yuvCapture2_canCompleteWhileYuvProcessing1Runs() {
        val session = CameraPipelineUiSession()
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val jobA = newJobDir("yuv_a")
        val processingAStarted = CountDownLatch(1)
        val releaseA = CountDownLatch(1)

        // Burst A reaches its durable handoff boundary.
        val genA = (session.start("burst A", 4) as CameraPipelineUiSession.StartResult.Accepted).operation.generation
        session.accept(CameraPipelineEvent.Started(genA, "capturing"))
        session.accept(handoffEvent(genA, jobA))
        assertTrue(session.snapshot().canAdmitNewCapture)

        // Processing A starts and blocks on the lane.
        assertTrue(
            coordinator.enqueue(
                ExactJobRef(jobA, KeplerActiveOperationKind.PROCESSING_YUV),
                HeavyProcessingWork {
                    processingAStarted.countDown()
                    releaseA.await(10, TimeUnit.SECONDS)
                }
            ) is BackgroundEnqueueResult.Accepted
        )
        assertTrue(processingAStarted.await(10, TimeUnit.SECONDS))

        // Burst B completes its whole capture stage while A still processes.
        val genB = session.start("burst B", 4)
        assertTrue(genB is CameraPipelineUiSession.StartResult.Accepted)
        val genBValue = (genB as CameraPipelineUiSession.StartResult.Accepted).operation.generation
        session.accept(CameraPipelineEvent.Started(genBValue, "capturing B"))
        session.accept(handoffEvent(genBValue, newJobDir("yuv_b")))
        val afterB = session.snapshot()
        assertTrue(afterB.canAdmitNewCapture)
        assertEquals(KeplerActiveOperationKind.PROCESSING_YUV, coordinator.snapshot().activeJobKind)

        releaseA.countDown()
        awaitDrain(coordinator)
    }

    @Test
    fun rawCapture2_canCompleteWhileRawProcessing1Runs() {
        val session = CameraPipelineUiSession()
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val startedA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)

        val genA = (session.start("raw A", 4) as CameraPipelineUiSession.StartResult.Accepted).operation.generation
        session.accept(CameraPipelineEvent.Started(genA, "capturing"))
        session.accept(handoffEvent(genA, newJobDir("raw_a")))
        assertTrue(
            coordinator.enqueue(
                ExactJobRef(newJobDir("raw_a"), KeplerActiveOperationKind.PROCESSING_RAW),
                HeavyProcessingWork {
                    startedA.countDown()
                    releaseA.await(10, TimeUnit.SECONDS)
                }
            ) is BackgroundEnqueueResult.Accepted
        )
        assertTrue(startedA.await(10, TimeUnit.SECONDS))

        val genB = session.start("raw B", 4)
        assertTrue(genB is CameraPipelineUiSession.StartResult.Accepted)
        val genBValue = (genB as CameraPipelineUiSession.StartResult.Accepted).operation.generation
        session.accept(CameraPipelineEvent.Started(genBValue, "capturing B"))
        session.accept(handoffEvent(genBValue, newJobDir("raw_b")))
        assertTrue(session.snapshot().canAdmitNewCapture)

        releaseA.countDown()
        awaitDrain(coordinator)
    }

    @Test
    fun rawCapture_canRunWhileYuvProcessingActive() {
        val session = CameraPipelineUiSession()
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val startedYuv = CountDownLatch(1)
        val releaseYuv = CountDownLatch(1)
        assertTrue(
            coordinator.enqueue(
                ExactJobRef(newJobDir("yuv_busy"), KeplerActiveOperationKind.PROCESSING_YUV),
                HeavyProcessingWork {
                    startedYuv.countDown()
                    releaseYuv.await(10, TimeUnit.SECONDS)
                }
            ) is BackgroundEnqueueResult.Accepted
        )
        assertTrue(startedYuv.await(10, TimeUnit.SECONDS))

        val generation = session.start("raw burst", 4)
        assertTrue(generation is CameraPipelineUiSession.StartResult.Accepted)
        releaseYuv.countDown()
    }

    @Test
    fun yuvCapture_canRunWhileRawProcessingActive() {
        val session = CameraPipelineUiSession()
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val startedRaw = CountDownLatch(1)
        val releaseRaw = CountDownLatch(1)
        assertTrue(
            coordinator.enqueue(
                ExactJobRef(newJobDir("raw_busy"), KeplerActiveOperationKind.PROCESSING_RAW),
                HeavyProcessingWork {
                    startedRaw.countDown()
                    releaseRaw.await(10, TimeUnit.SECONDS)
                }
            ) is BackgroundEnqueueResult.Accepted
        )
        assertTrue(startedRaw.await(10, TimeUnit.SECONDS))

        val generation = session.start("yuv burst", 4)
        assertTrue(generation is CameraPipelineUiSession.StartResult.Accepted)
        releaseRaw.countDown()
    }

    // Lane ordering / exclusivity ------------------------------------------

    @Test
    fun backgroundJobs_executeFIFO() {
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val order = mutableListOf<String>()
        val done = CountDownLatch(3)
        val gate = CountDownLatch(1)
        val dirs = listOf("first", "second", "third").map { newJobDir(it) }
        dirs.forEach { dir ->
            coordinator.enqueue(
                ExactJobRef(dir, KeplerActiveOperationKind.PROCESSING_YUV),
                HeavyProcessingWork { ref ->
                    if (ref.jobDirectory == dirs[0]) gate.await(10, TimeUnit.SECONDS)
                    synchronized(order) { order.add(ref.jobDirectory.name.substringBefore('_')) }
                    done.countDown()
                }
            )
        }
        gate.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("first", "second", "third"), order)
    }

    @Test
    fun backgroundJobs_neverExecuteHeavyProcessorsConcurrently() {
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val activeCount = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val completed = CountDownLatch(4)
        repeat(4) { index ->
            coordinator.enqueue(
                ExactJobRef(
                    newJobDir("job$index"),
                    if (index % 2 == 0) KeplerActiveOperationKind.PROCESSING_YUV
                    else KeplerActiveOperationKind.PROCESSING_RAW
                ),
                HeavyProcessingWork { _ ->
                    val now = activeCount.incrementAndGet()
                    maxObserved.updateAndGet { current -> maxOf(current, now) }
                    Thread.sleep(40)
                    activeCount.decrementAndGet()
                    completed.countDown()
                }
            )
        }
        assertTrue(completed.await(15, TimeUnit.SECONDS))
        assertEquals(1, maxObserved.get())
    }

    @Test
    fun exactYuvJobProcessed_whenNewerYuvCaptureExists() {
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val older = newJobDir("older_yuv")
        val newer = newJobDir("newer_yuv")
        newer.setLastModified(System.currentTimeMillis())
        older.setLastModified(System.currentTimeMillis() - 60_000)
        var processed: String? = null
        val done = CountDownLatch(1)
        coordinator.enqueue(
            ExactJobRef(older, KeplerActiveOperationKind.PROCESSING_YUV),
            HeavyProcessingWork { ref ->
                processed = ref.jobDirectory.absolutePath
                done.countDown()
            }
        )
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(older.absolutePath, processed)
        assertFalse(newer.absolutePath == processed)
    }

    @Test
    fun exactRawJobProcessed_whenNewerRawCaptureExists() {
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val older = newJobDir("older_raw")
        val newer = newJobDir("newer_raw")
        newer.setLastModified(System.currentTimeMillis())
        older.setLastModified(System.currentTimeMillis() - 60_000)
        var processed: String? = null
        val done = CountDownLatch(1)
        coordinator.enqueue(
            ExactJobRef(older, KeplerActiveOperationKind.PROCESSING_RAW),
            HeavyProcessingWork { ref ->
                processed = ref.jobDirectory.absolutePath
                done.countDown()
            }
        )
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(older.absolutePath, processed)
    }

    // Manual reprocess ------------------------------------------------------

    @Test
    fun manualReprocess_queuesWithoutBlockingCapture() {
        val session = CameraPipelineUiSession()
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val reprocessRunning = CountDownLatch(1)
        val releaseReprocess = CountDownLatch(1)
        val jobDir = newJobDir("reprocess_target")

        val outcome = coordinator.enqueue(
            ExactJobRef(jobDir, KeplerActiveOperationKind.PROCESSING_YUV),
            HeavyProcessingWork {
                reprocessRunning.countDown()
                releaseReprocess.await(10, TimeUnit.SECONDS)
            }
        )
        assertTrue(outcome is BackgroundEnqueueResult.Accepted)
        assertTrue(reprocessRunning.await(10, TimeUnit.SECONDS))

        // The camera remains admittable while manual reprocess runs.
        assertTrue(session.snapshot().canAdmitNewCapture)
        assertTrue(session.start("capture during reprocess", 4) is
            CameraPipelineUiSession.StartResult.Accepted)

        releaseReprocess.countDown()
        awaitDrain(coordinator)
    }

    @Test
    fun duplicateManualReprocess_respectsLeaseAuthority() {
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val jobDir = newJobDir("dup_reprocess")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executions = AtomicInteger(0)
        assertTrue(
            coordinator.enqueue(
                ExactJobRef(jobDir, KeplerActiveOperationKind.PROCESSING_YUV),
                HeavyProcessingWork {
                    executions.incrementAndGet()
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            ) is BackgroundEnqueueResult.Accepted
        )
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val duplicate = coordinator.enqueue(
            ExactJobRef(jobDir, KeplerActiveOperationKind.PROCESSING_YUV),
            HeavyProcessingWork { executions.incrementAndGet() }
        )
        assertTrue(duplicate is BackgroundEnqueueResult.Duplicate)
        release.countDown()
        awaitDrain(coordinator)
        assertEquals(1, executions.get())
    }

    @Test
    fun superResolution_usesSameSerializedProcessingLane() {
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val srStarted = CountDownLatch(1)
        val releaseSr = CountDownLatch(1)
        val yuvRan = CountDownLatch(1)
        val srJob = newJobDir("sr_source")
        val yuvJob = newJobDir("plain_yuv")

        coordinator.enqueue(
            ExactJobRef(srJob, KeplerActiveOperationKind.PROCESSING_YUV),
            HeavyProcessingWork { _ ->
                srStarted.countDown()
                releaseSr.await(10, TimeUnit.SECONDS)
            }
        )
        coordinator.enqueue(
            ExactJobRef(yuvJob, KeplerActiveOperationKind.PROCESSING_YUV),
            HeavyProcessingWork { _ -> yuvRan.countDown() }
        )
        assertTrue(srStarted.await(10, TimeUnit.SECONDS))
        // YUV fusion must wait for Super Resolution - one heavy lane.
        assertFalse(yuvRan.await(300, TimeUnit.MILLISECONDS))
        val snapshot = coordinator.snapshot()
        assertEquals(srJob.absolutePath, snapshot.activeJobDirectory)
        assertEquals(1, snapshot.queuedCount)
        releaseSr.countDown()
        assertTrue(yuvRan.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun singleFrame_releasesCaptureAdmissionBeforeExportTerminal() {
        val session = CameraPipelineUiSession()
        val jobDir = newJobDir("single_frame_job")
        val generation = (session.start("single", 1) as CameraPipelineUiSession.StartResult.Accepted).operation.generation
        session.accept(CameraPipelineEvent.Started(generation, "capturing"))

        // Durable single-frame handoff frees admission...
        session.accept(handoffEvent(generation, jobDir))
        assertTrue(session.snapshot().canAdmitNewCapture)

        // ...even though export has not reached its terminal yet.
        session.accept(
            CameraPipelineEvent.ExportStage(
                generation,
                CaptureStage.EXPORTING,
                CameraPipelineProgressCounts(),
                "exporting"
            )
        )
        assertTrue(session.snapshot().canAdmitNewCapture)
        assertTrue(session.start("next capture", 1) is CameraPipelineUiSession.StartResult.Accepted)

        // Once a newer burst owns the foreground slot, the older generation's
        // terminal is safely dropped as STALE - production truth lives in the
        // exact job's durable metadata, never in mutable capture state.
        assertEquals(
            CameraPipelineUiSession.EventResult.STALE,
            session.accept(
                CameraPipelineEvent.Terminal(
                    generation = generation,
                    kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                    captureResourcesSettled = true,
                    message = "export complete"
                )
            )
        )
    }
}
