package com.projectnuke.keplernightlab

import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
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
 * Phase 10: explicit bounded backlog for rapid capture. The durable-ref queue
 * is capped; overflow rejects NEW handoffs cleanly (before capture starts in
 * the UI path), never deletes or reorders existing durable jobs, and capacity
 * recovers exactly when the lane drains.
 */
@RunWith(RobolectricTestRunner::class)
class BackpressureTest {

    @Before
    fun reset() {
        BackgroundProcessingCoordinator.resetForTest()
    }

    @Test
    fun backpressureMessage_isFormalKorean_blockOnlyWhenFull() {
        // Normal small backlog: allowed, no scary message.
        val idle = evaluateBackpressure(queuedCount = 0, active = false)
        assertEquals(BackpressureDecision.ALLOW, idle.decision)
        assertNull(idle.userMessage)

        val oneQueued = evaluateBackpressure(queuedCount = 1, active = true)
        assertEquals(BackpressureDecision.ALLOW, oneQueued.decision)
        assertNull(oneQueued.userMessage)

        // No safe capacity left: formal-polite block notice.
        val full = evaluateBackpressure(
            queuedCount = MAX_QUEUED_HEAVY_JOBS,
            active = false
        )
        assertEquals(BackpressureDecision.BLOCK, full.decision)
        assertEquals("처리 대기 중인 사진이 많습니다. 잠시 후 다시 촬영해 주세요.", full.userMessage)

        val fullWithActive = evaluateBackpressure(
            queuedCount = MAX_QUEUED_HEAVY_JOBS - 1,
            active = true
        )
        assertEquals(BackpressureDecision.BLOCK, fullWithActive.decision)
    }

    @Test
    fun boundedQueue_doesNotGrowWithoutLimit_andFullRejectsCleanly() {
        val appContext = RuntimeEnvironment.getApplication() as Context
        val coordinator = BackgroundProcessingCoordinator.of(appContext)
        val release = CountDownLatch(1)
        try {
            coordinator.backgroundExecutor = BackgroundProcessingExecutor { _, _ ->
                release.await()
            }
            // Occupy the lane deterministically BEFORE filling the queue.
            val laneDir = File(appContext.filesDir, "bp-lane").apply { mkdirs() }
            assertEquals(
                BackgroundEnqueueResult.Accepted,
                coordinator.enqueue(
                    BackgroundProcessingRequest(laneDir, KeplerActiveOperationKind.PROCESSING_YUV)
                )
            )
            assertTrue(awaitUntil(5_000) { coordinator.snapshot().hasActiveWork })

            val results = mutableListOf<BackgroundEnqueueResult>()
            repeat(MAX_QUEUED_HEAVY_JOBS + 1) { index ->
                val dir = File(appContext.filesDir, "bp-job-$index").apply { mkdirs() }
                results += coordinator.enqueue(
                    BackgroundProcessingRequest(dir, KeplerActiveOperationKind.PROCESSING_YUV)
                )
            }
            val accepted = results.count { it == BackgroundEnqueueResult.Accepted }
            val rejected = results.count { it == BackgroundEnqueueResult.QueueFull }
            // The queue admits exactly its cap; every further handoff bounces.
            assertEquals(MAX_QUEUED_HEAVY_JOBS, accepted)
            assertEquals(1, rejected)
            assertEquals(MAX_QUEUED_HEAVY_JOBS, coordinator.snapshot().queuedCount)
        } finally {
            release.countDown()
            awaitUntil(5_000) { !coordinator.snapshot().hasPendingWork }
            BackgroundProcessingCoordinator.resetForTest()
        }
    }

    @Test
    fun queueFull_doesNotDeleteExistingJob_orReorderFifo() {
        val appContext = RuntimeEnvironment.getApplication() as Context
        val coordinator = BackgroundProcessingCoordinator.of(appContext)
        val release = CountDownLatch(1)
        try {
            coordinator.backgroundExecutor = BackgroundProcessingExecutor { _, _ ->
                release.await()
            }
            // Lane job, awaited to RUNNING state deterministically.
            val laneJob = File(appContext.filesDir, "bp-keep-lane").apply { mkdirs() }
            assertEquals(
                BackgroundEnqueueResult.Accepted,
                coordinator.enqueue(
                    BackgroundProcessingRequest(laneJob, KeplerActiveOperationKind.PROCESSING_YUV)
                )
            )
            assertTrue(awaitUntil(5_000) { coordinator.snapshot().hasActiveWork })

            // Fill the whole queue FIFO.
            val queuedDirs = (0 until MAX_QUEUED_HEAVY_JOBS).map { index ->
                File(appContext.filesDir, "bp-keep-$index").apply { mkdirs() }
            }
            queuedDirs.forEach { dir ->
                assertEquals(
                    BackgroundEnqueueResult.Accepted,
                    coordinator.enqueue(
                        BackgroundProcessingRequest(dir, KeplerActiveOperationKind.PROCESSING_YUV)
                    )
                )
            }
            // The overflowing job is rejected WITHOUT touching the queue.
            val overflow = File(appContext.filesDir, "bp-keep-overflow").apply { mkdirs() }
            assertEquals(
                BackgroundEnqueueResult.QueueFull,
                coordinator.enqueue(
                    BackgroundProcessingRequest(overflow, KeplerActiveOperationKind.PROCESSING_RAW)
                )
            )
            assertEquals(queuedDirs.map { it.absolutePath }, coordinator.queuedOrder())
            // Every previously accepted durable ref still exists on disk.
            assertTrue(laneJob.isDirectory)
            (queuedDirs + overflow).forEach { assertTrue(it.isDirectory) }
        } finally {
            release.countDown()
            awaitUntil(5_000) { !coordinator.snapshot().hasPendingWork }
            BackgroundProcessingCoordinator.resetForTest()
        }
    }

    @Test
    fun queueRecoveryTruth_remainsDurable_capacityRecoversAfterDrain() {
        val appContext = RuntimeEnvironment.getApplication() as Context
        val coordinator = BackgroundProcessingCoordinator.of(appContext)
        var gate: CountDownLatch? = CountDownLatch(1)
        try {
            coordinator.backgroundExecutor = BackgroundProcessingExecutor { _, _ ->
                gate?.await()
            }
            val first = File(appContext.filesDir, "bp-rec-0").apply { mkdirs() }
            coordinator.enqueue(
                BackgroundProcessingRequest(first, KeplerActiveOperationKind.PROCESSING_YUV)
            )
            awaitUntil(5_000) { coordinator.snapshot().hasActiveWork }

            // Fill the queue completely.
            repeat(MAX_QUEUED_HEAVY_JOBS) { index ->
                val dir = File(appContext.filesDir, "bp-rec-${index + 1}").apply { mkdirs() }
                assertEquals(
                    BackgroundEnqueueResult.Accepted,
                    coordinator.enqueue(
                        BackgroundProcessingRequest(dir, KeplerActiveOperationKind.PROCESSING_YUV)
                    )
                )
            }

            // Drain everything.
            gate?.countDown()
            gate = null
            awaitUntil(5_000) { !coordinator.snapshot().hasPendingWork }

            // Capacity recovered; a fresh handoff is admittable again and a
            // completed job directory may even be re-enqueued (new sequence).
            val fresh = File(appContext.filesDir, "bp-rec-fresh").apply { mkdirs() }
            assertEquals(
                BackgroundEnqueueResult.Accepted,
                coordinator.enqueue(
                    BackgroundProcessingRequest(fresh, KeplerActiveOperationKind.PROCESSING_RAW)
                )
            )
        } finally {
            gate?.countDown()
            awaitUntil(5_000) { !coordinator.snapshot().hasPendingWork }
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
