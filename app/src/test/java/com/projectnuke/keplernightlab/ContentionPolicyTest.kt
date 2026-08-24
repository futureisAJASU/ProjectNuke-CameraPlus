package com.projectnuke.keplernightlab

import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Phase 6: deterministic foreground/background contention policy. Foreground
 * persistence drives a process-scoped activity signal; the serialized heavy
 * lane only ever yields once per safe stage boundary while it is active. The
 * policy can never cancel, block on locks, or reorder background jobs.
 */
@RunWith(RobolectricTestRunner::class)
class ContentionPolicyTest {

    @Test
    fun foregroundSignal_nestsAndClears() {
        assertFalse(ForegroundCaptureActivitySignal.isForegroundCaptureActive())
        ForegroundCaptureActivitySignal.beginPersistence()
        ForegroundCaptureActivitySignal.beginPersistence()
        assertTrue(ForegroundCaptureActivitySignal.isForegroundCaptureActive())
        ForegroundCaptureActivitySignal.endPersistence()
        assertTrue(ForegroundCaptureActivitySignal.isForegroundCaptureActive())
        ForegroundCaptureActivitySignal.endPersistence()
        assertFalse(ForegroundCaptureActivitySignal.isForegroundCaptureActive())
    }

    @Test
    fun cooperativeYield_isSafeActiveOrIdle() {
        // Idle: pure no-op.
        ForegroundCaptureActivitySignal.cooperativeYieldAtStageBoundary()
        // Active: still just a yield - must return promptly and stay safe.
        ForegroundCaptureActivitySignal.beginPersistence()
        try {
            val startedAt = System.nanoTime()
            ForegroundCaptureActivitySignal.cooperativeYieldAtStageBoundary()
            assertTrue(System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(1))
        } finally {
            ForegroundCaptureActivitySignal.endPersistence()
        }
    }

    @Test
    fun boundedCaptureWorker_drivesSignalAroundTasks() {
        val worker = BoundedCaptureWorker("contention-test", capacity = 1)
        try {
            val ran = CountDownLatch(1)
            var observedInside = false
            worker.submit {
                observedInside = ForegroundCaptureActivitySignal.isForegroundCaptureActive()
                ForegroundCaptureActivitySignal.cooperativeYieldAtStageBoundary()
                ran.countDown()
            }
            assertTrue(ran.await(5, TimeUnit.SECONDS))
            assertTrue(observedInside)
            // Signal is released even though the task itself never ended it.
            awaitUntil(5_000) { !ForegroundCaptureActivitySignal.isForegroundCaptureActive() }
            assertFalse(ForegroundCaptureActivitySignal.isForegroundCaptureActive())
        } finally {
            worker.close()
        }
    }

    @Test
    fun backgroundHeavyWork_doesNotHoldCaptureAdmissionLock() {
        val appContext = RuntimeEnvironment.getApplication() as Context
        BackgroundProcessingCoordinator.resetForTest()
        val coordinator = BackgroundProcessingCoordinator.of(appContext)
        val release = CountDownLatch(1)
        try {
            coordinator.backgroundExecutor = BackgroundProcessingExecutor { _, _ ->
                release.await()
            }
            val dirA = File(appContext.filesDir, "cp-lock-a").apply { mkdirs() }
            assertEquals(
                BackgroundEnqueueResult.Accepted,
                coordinator.enqueue(
                    BackgroundProcessingRequest(dirA, KeplerActiveOperationKind.PROCESSING_YUV)
                )
            )
            awaitUntil(5_000) { coordinator.snapshot().hasActiveWork }
            val startedAt = System.currentTimeMillis()
            // Snapshot (admission truth probe) must answer while heavy work is
            // blocked mid-execution: the lane holds NO coordinator/session lock.
            val snap = coordinator.snapshot()
            assertTrue(snap.hasActiveWork)
            assertTrue(System.currentTimeMillis() - startedAt < 2_000)
            // And admission decisions keep working concurrently.
            assertTrue(CameraPipelineUiSession().start("probe", 1) !=
                CameraPipelineUiSession.StartResult.Rejected)
        } finally {
            release.countDown()
            awaitUntil(5_000) { !coordinator.snapshot().hasPendingWork }
            BackgroundProcessingCoordinator.resetForTest()
        }
    }

    @Test
    fun twoJobs_remainFIFOThroughYield() {
        val appContext = RuntimeEnvironment.getApplication() as Context
        BackgroundProcessingCoordinator.resetForTest()
        val coordinator = BackgroundProcessingCoordinator.of(appContext)
        val firstRunning = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executionOrder = mutableListOf<String>()
        try {
            coordinator.backgroundExecutor = BackgroundProcessingExecutor { request, _ ->
                if (request.exactJobDirectory.name == "cp-fifo-a") {
                    firstRunning.countDown()
                    releaseFirst.await()
                    // Foreground persistence becomes active mid-job-A and ends
                    // before job B starts: B must still follow A exactly.
                    ForegroundCaptureActivitySignal.beginPersistence()
                    ForegroundCaptureActivitySignal.endPersistence()
                } else {
                    ForegroundCaptureActivitySignal.cooperativeYieldAtStageBoundary()
                }
                synchronized(executionOrder) {
                    executionOrder.add(request.exactJobDirectory.name)
                }
            }
            val dirA = File(appContext.filesDir, "cp-fifo-a").apply { mkdirs() }
            val dirB = File(appContext.filesDir, "cp-fifo-b").apply { mkdirs() }
            coordinator.enqueue(
                BackgroundProcessingRequest(dirA, KeplerActiveOperationKind.PROCESSING_YUV)
            )
            assertTrue(awaitUntil(5_000) { firstRunning.await(0, TimeUnit.MILLISECONDS) })
            coordinator.enqueue(
                BackgroundProcessingRequest(dirB, KeplerActiveOperationKind.PROCESSING_RAW)
            )
            releaseFirst.countDown()
            assertTrue(awaitUntil(5_000) {
                synchronized(executionOrder) { executionOrder.size == 2 }
            })
            synchronized(executionOrder) {
                assertEquals(listOf("cp-fifo-a", "cp-fifo-b"), executionOrder)
            }
            assertFalse(ForegroundCaptureActivitySignal.isForegroundCaptureActive())
        } finally {
            releaseFirst.countDown()
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
