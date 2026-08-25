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
            val results = mutableListOf<BackgroundEnqueueResult>()
            // First job runs (occupies lane); then fill the queue to the cap.
            repeat(MAX_QUEUED_HEAVY_JOBS + 2) { index ->
                val dir = File(appContext.filesDir, "bp-job-$index").apply { mkdirs() }
                results += coordinator.enqueue(
                    BackgroundProcessingRequest(dir, KeplerActiveOperationKind.PROCESSING_YUV)
                )
            }
            val accepted = results.count { it == BackgroundEnqueueResult.Accepted }
            val rejected = results.count { it == BackgroundEnqueueResult.QueueFull }
            // Exactly one lane job + cap queue slots admitted; the rest bounced.
            assertEquals(MAX_QUEUED_HEAVY_JOBS + 1, accepted)
            assertTrue(rejected >= 1)
            awaitUntil(5_000) { coordinator.snapshot().hasActiveWork }
            assertEquals(
                MAX_QUEUED_HEAVY_JOBS,
                coordinator.snapshot().queuedCount
            )
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
            val dirs = (0 until MAX_QUEUED_HEAVY_JOBS + 1).map { index ->
                File(appContext.filesDir, "bp-keep-$index").apply { mkdirs() }
            }
            // Lane job + full queue.
            dirs.dropLast(1).forEach { dir ->
                assertEquals(
                    BackgroundEnqueueResult.Accepted,
                    coordinator.enqueue(
                        BackgroundProcessingRequest(dir, KeplerActiveOperationKind.PROCESSING_YUV)
                    )
                )
            }
            // The overflowing job is rejected WITHOUT touching the queue.
            val overflow = coordinator.enqueue(
                BackgroundProcessingRequest(dirs.last(), KeplerActiveOperationKind.PROCESSING_RAW)
            )
            assertEquals(BackgroundEnqueueResult.QueueFull, overflow)
            awaitUntil(5_000) { coordinator.snapshot().hasActiveWork }
            val queuedOrder = coordinator.queuedOrder()
            assertEquals(dirs.drop(1).dropLast(1).map { it.absolutePath }, queuedOrder)
            // Every previously accepted durable ref still exists on disk.
            dirs.forEach { assertTrue(it.isDirectory) }
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
        try {
            var gate: CountDownLatch? = CountDownLatch(1)
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
