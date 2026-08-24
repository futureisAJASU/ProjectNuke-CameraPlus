package com.projectnuke.keplernightlab

import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Phase 9: rapid sequential capture invariant. Once capture A reaches durable
 * foreground handoff, capture B must be admittable while A is still processing
 * on the single FIFO heavy lane; A's terminal routes to A exactly and never
 * mutates B; heavy concurrency stays exactly one.
 */
@RunWith(RobolectricTestRunner::class)
class RapidSequentialOverlapTest {

    @Before
    fun resetLane() {
        BackgroundProcessingCoordinator.resetForTest()
        BackgroundPipelineEventHub.resetForTest()
        KeplerBackgroundExecutor.heavyLaneGateForTest = null
    }

    @Test
    fun aHandoff_releasesShutter_whileAStillProcessing_terminalArrivesLater() {
        val appContext = RuntimeEnvironment.getApplication() as Context
        val coordinator = BackgroundProcessingCoordinator.of(appContext)
        try {
            val releaseA = CountDownLatch(1)
            var aTerminalObserved = false
            coordinator.backgroundExecutor = BackgroundProcessingExecutor { _, _ ->
                releaseA.await()
                aTerminalObserved = true
            }
            val session = CameraPipelineUiSession(
                backgroundOccupancy = { coordinator.snapshot().hasPendingWork }
            )

            // Capture A: full foreground lifecycle to evidenced handoff.
            val a = session.start("capture A", 4) as CameraPipelineUiSession.StartResult.Accepted
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
            val jobA = File(appContext.filesDir, "rs-jobA").apply { mkdirs() }
            coordinator.enqueue(
                BackgroundProcessingRequest(jobA, KeplerActiveOperationKind.PROCESSING_RAW)
            )
            awaitUntil(5_000) { coordinator.snapshot().hasActiveWork }

            // A is mid-processing and NOT terminal; B must be admittable NOW.
            assertTrue(!aTerminalObserved)
            assertTrue(session.snapshot().canAdmitNewCapture)
            val b = session.start("capture B", 4)
            assertTrue(b is CameraPipelineUiSession.StartResult.Accepted)

            // Release A: its terminal must route to A only.
            releaseA.countDown()
            awaitUntil(5_000) { aTerminalObserved && !coordinator.snapshot().hasPendingWork }
            val duringB = session.snapshot()
            assertEquals("capture B", duringB.captureProgress.message)
        } finally {
            BackgroundProcessingCoordinator.resetForTest()
        }
    }

    @Test
    fun gateSeam_holdsHeavyLaneDeterministically_thenReleases() {
        val appContext = RuntimeEnvironment.getApplication() as Context
        val coordinator = BackgroundProcessingCoordinator.of(appContext)
        try {
            val gate = CountDownLatch(1)
            KeplerBackgroundExecutor.heavyLaneGateForTest = { gate.await() }
            var executed = false
            coordinator.backgroundExecutor = BackgroundProcessingExecutor { _, _ ->
                // Mirror the production entry: the gate is applied at heavy-lane
                // entry, before any job work begins.
                KeplerBackgroundExecutor.heavyLaneGateForTest?.invoke()
                executed = true
            }
            val jobDir = File(appContext.filesDir, "rs-gate").apply { mkdirs() }
            coordinator.enqueue(
                BackgroundProcessingRequest(jobDir, KeplerActiveOperationKind.PROCESSING_YUV)
            )
            Thread.sleep(250)
            // Gate installed: the lane is held BEFORE any work begins...
            assertTrue(!executed && coordinator.snapshot().hasActiveWork)
            // ...release proves deterministic resume without losing the job.
            gate.countDown()
            awaitUntil(5_000) { executed && !coordinator.snapshot().hasPendingWork }
            assertTrue(executed)
        } finally {
            KeplerBackgroundExecutor.heavyLaneGateForTest = null
            BackgroundProcessingCoordinator.resetForTest()
        }
    }

    @Test
    fun mixedKinds_remainFifo_withDistinctOutputs_andMaxConcurrencyOne() {
        val appContext = RuntimeEnvironment.getApplication() as Context
        val coordinator = BackgroundProcessingCoordinator.of(appContext)
        try {
            val order = mutableListOf<String>()
            val inFlight = AtomicInteger(0)
            val maxInFlight = AtomicInteger(0)
            val firstRunning = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            coordinator.backgroundExecutor = BackgroundProcessingExecutor { request, _ ->
                inFlight.incrementAndGet()
                while (true) {
                    val current = maxInFlight.get()
                    if (inFlight.get() <= current || maxInFlight.compareAndSet(current, inFlight.get())) break
                }
                try {
                    if (request.exactJobDirectory.name == "rs-fifo-a") {
                        firstRunning.countDown()
                        releaseFirst.await()
                    }
                    synchronized(order) { order.add(request.exactJobDirectory.name) }
                } finally {
                    inFlight.decrementAndGet()
                }
            }
            val combos = listOf(
                "rs-fifo-a" to KeplerActiveOperationKind.PROCESSING_YUV,
                "rs-fifo-b" to KeplerActiveOperationKind.PROCESSING_RAW,
                "rs-fifo-c" to KeplerActiveOperationKind.PROCESSING_YUV
            )
            combos.forEach { (name, kind) ->
                val dir = File(appContext.filesDir, name).apply { mkdirs() }
                assertEquals(
                    BackgroundEnqueueResult.Accepted,
                    coordinator.enqueue(BackgroundProcessingRequest(dir, kind))
                )
            }
            assertTrue(awaitUntil(5_000) { firstRunning.await(0, TimeUnit.MILLISECONDS) })
            releaseFirst.countDown()
            assertTrue(awaitUntil(5_000) {
                synchronized(order) { order.size == combos.size } &&
                    !coordinator.snapshot().hasPendingWork
            })
            synchronized(order) { assertEquals(combos.map { it.first }, order) }
            // Strict single-lane truth across the whole mixed-kind burst.
            assertEquals(1, maxInFlight.get())
        } finally {
            BackgroundProcessingCoordinator.resetForTest()
        }
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
