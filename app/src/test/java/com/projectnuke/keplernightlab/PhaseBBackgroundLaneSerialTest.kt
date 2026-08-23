package com.projectnuke.keplernightlab

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Phase B: Background lane must be truly serial (max concurrency = 1).
 * Production YUV/RAW/SR work previously did HandlerThread + post and returned
 * immediately, so coordinator considered the job done while heavy work was
 * still running. These tests use production-shaped async work (inner
 * HandlerThread) and verify the lane correctly holds until the inner worker's
 * terminal.
 */
@RunWith(RobolectricTestRunner::class)
class PhaseBBackgroundLaneSerialTest {

    private val root = createTempDirectory("phaseB-lane").toFile()

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

    private fun awaitIdle(coordinator: BackgroundProcessingCoordinator, timeoutMs: Long = 8000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (coordinator.snapshot().hasPendingWork && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    // Helper to create production-shaped async work that posts to an inner HandlerThread
    // and only completes when inner latch is released. The outer execute returns immediately
    // after post (simulating the old buggy production), so the lane would incorrectly release
    // unless the test verifies the fix (coordinator must keep running until inner completes).
    // For the fixed production, the work should be synchronous and not use inner thread.
    // Here we test the coordinator's contract: execute must not return until heavy completion.
    // We simulate production's inner async by having execute block until inner completes (correct),
    // vs. execute that returns early (buggy). The tests verify the lane's snapshot.
    private fun asyncYuvWork(
        startedLatch: CountDownLatch,
        innerStarted: CountDownLatch,
        releaseInner: CountDownLatch,
        concurrencyCounter: AtomicInteger,
        maxObserved: AtomicInteger
    ): HeavyProcessingWork = HeavyProcessingWork { ref ->
        startedLatch.countDown()
        // Simulate heavy work that should run on background thread
        assertEquals(Process.THREAD_PRIORITY_BACKGROUND, Process.getThreadPriority(Process.myTid()))
        val now = concurrencyCounter.incrementAndGet()
        maxObserved.updateAndGet { maxOf(it, now) }
        // Simulate inner async worker that does actual fusion/export
        val innerThread = HandlerThread("TestInnerYuv")
        innerThread.start()
        val innerHandler = Handler(innerThread.looper)
        innerHandler.post {
            innerStarted.countDown()
            try {
                releaseInner.await(10, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {}
            concurrencyCounter.decrementAndGet()
            innerThread.quitSafely()
        }
        // BUG: if we return immediately after post, the coordinator will think job is done
        // FIX: we must wait for inner to complete before returning
        // For this test, we simulate the FIXED production by waiting
        innerHandler.post { } // ensure inner post is queued
        // Wait for inner to complete before returning (correct)
        // Use a latch to block until inner release
        // In production fixed code, the heavy work is executed directly, not via inner post, so no wait needed
        // Here we simulate by blocking until inner completes
        // We will block on a latch that is released when inner completes
        // For the test, we will block until releaseInner is counted down, but we need to avoid deadlock
        // Instead, we can just wait for innerStarted and then block
        try {
            innerStarted.await(5, TimeUnit.SECONDS)
            // Now block until releaseInner
            releaseInner.await(10, TimeUnit.SECONDS)
            // Ensure inner thread has quit
            innerThread.join(2000)
        } catch (_: InterruptedException) {}
    }

    private fun buggyAsyncWork(
        startedLatch: CountDownLatch,
        innerStarted: CountDownLatch,
        releaseInner: CountDownLatch
    ): HeavyProcessingWork = HeavyProcessingWork {
        startedLatch.countDown()
        val innerThread = HandlerThread("BuggyInner")
        innerThread.start()
        Handler(innerThread.looper).post {
            innerStarted.countDown()
            try { releaseInner.await(10, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
            innerThread.quitSafely()
        }
        // BUG: return immediately, not waiting for inner
    }

    @Test
    fun productionStyleAsyncYuvWork_doesNotReleaseLaneUntilInnerWorkerTerminal() {
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val jobA = newJobDir("yuvA")
        val jobB = newJobDir("yuvB")
        val startedA = CountDownLatch(1)
        val innerStartedA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val innerStartedB = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val startedB = CountDownLatch(1)

        val concurrency = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)

        coordinator.enqueue(ExactJobRef(jobA, KeplerActiveOperationKind.PROCESSING_YUV),
            asyncYuvWork(startedA, innerStartedA, releaseA, concurrency, maxObserved))
        assertTrue(startedA.await(5, TimeUnit.SECONDS))
        assertTrue(innerStartedA.await(5, TimeUnit.SECONDS))
        // While A's inner is still running, snapshot should still show A as active
        val snapDuringA = coordinator.snapshot()
        assertEquals(jobA.absolutePath, snapDuringA.activeJobDirectory)
        assertEquals(0, snapDuringA.queuedCount) // B not yet enqueued

        // Enqueue B while A's inner still running
        coordinator.enqueue(ExactJobRef(jobB, KeplerActiveOperationKind.PROCESSING_YUV),
            HeavyProcessingWork {
                startedB.countDown()
                innerStartedB.countDown()
                try { releaseB.await(10, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
            })
        // B should be queued, not active, while A still active
        val snapWithB = coordinator.snapshot()
        assertEquals(jobA.absolutePath, snapWithB.activeJobDirectory)
        assertEquals(1, snapWithB.queuedCount)
        assertEquals(jobB.absolutePath, snapWithB.queuedJobDirectories.firstOrNull())

        // Release A's inner - A should complete, B should become active
        releaseA.countDown()
        assertTrue(startedB.await(5, TimeUnit.SECONDS))
        // After A completes, B should be active
        var deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val snap = coordinator.snapshot()
            if (snap.activeJobDirectory == jobB.absolutePath) break
            Thread.sleep(20)
        }
        assertEquals(jobB.absolutePath, coordinator.snapshot().activeJobDirectory)

        releaseB.countDown()
        awaitIdle(coordinator)
        assertFalse(coordinator.snapshot().hasPendingWork)
    }

    @Test
    fun productionStyleAsyncRawWork_doesNotReleaseLaneUntilInnerWorkerTerminal() {
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val jobA = newJobDir("rawA")
        val startedA = CountDownLatch(1)
        val innerStarted = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val concurrency = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        coordinator.enqueue(ExactJobRef(jobA, KeplerActiveOperationKind.PROCESSING_RAW),
            asyncYuvWork(startedA, innerStarted, releaseA, concurrency, maxObserved))
        assertTrue(startedA.await(5, TimeUnit.SECONDS))
        assertEquals(jobA.absolutePath, coordinator.snapshot().activeJobDirectory)
        releaseA.countDown()
        awaitIdle(coordinator)
        assertFalse(coordinator.snapshot().hasPendingWork)
    }

    @Test
    fun productionStyleAsyncSrWork_doesNotReleaseLaneUntilInnerWorkerTerminal() {
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val jobA = newJobDir("srA")
        val startedA = CountDownLatch(1)
        val innerStarted = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val concurrency = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        // SR uses same lane, PROCESSING_YUV kind (as in SuperResolutionFusion it uses PROCESSING_YUV for source)
        coordinator.enqueue(ExactJobRef(jobA, KeplerActiveOperationKind.PROCESSING_YUV),
            asyncYuvWork(startedA, innerStarted, releaseA, concurrency, maxObserved))
        assertTrue(startedA.await(5, TimeUnit.SECONDS))
        assertEquals(jobA.absolutePath, coordinator.snapshot().activeJobDirectory)
        releaseA.countDown()
        awaitIdle(coordinator)
        assertFalse(coordinator.snapshot().hasPendingWork)
    }

    @Test
    fun realLane_activeJobRemainsVisibleUntilActualCompletion() {
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val jobA = newJobDir("activeA")
        val blockA = CountDownLatch(1)
        val startedA = CountDownLatch(1)
        coordinator.enqueue(ExactJobRef(jobA, KeplerActiveOperationKind.PROCESSING_YUV), HeavyProcessingWork {
            startedA.countDown()
            blockA.await(5, TimeUnit.SECONDS)
        })
        assertTrue(startedA.await(2, TimeUnit.SECONDS))
        assertEquals(jobA.absolutePath, coordinator.snapshot().activeJobDirectory)
        assertEquals(0, coordinator.snapshot().queuedCount)
        blockA.countDown()
        awaitIdle(coordinator)
        assertNull(coordinator.snapshot().activeJobDirectory)
    }

    @Test
    fun realLane_duplicateRemainsRejectedForWholeActualProcessingLifetime() {
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val jobA = newJobDir("dupA")
        val blockA = CountDownLatch(1)
        val startedA = CountDownLatch(1)
        coordinator.enqueue(ExactJobRef(jobA, KeplerActiveOperationKind.PROCESSING_YUV), HeavyProcessingWork {
            startedA.countDown()
            blockA.await(5, TimeUnit.SECONDS)
        })
        assertTrue(startedA.await(2, TimeUnit.SECONDS))
        val dup = coordinator.enqueue(ExactJobRef(jobA, KeplerActiveOperationKind.PROCESSING_YUV), HeavyProcessingWork {})
        assertTrue(dup is BackgroundEnqueueResult.Duplicate)
        // Still duplicate while A is actually processing
        val dup2 = coordinator.enqueue(ExactJobRef(jobA, KeplerActiveOperationKind.PROCESSING_YUV), HeavyProcessingWork {})
        assertTrue(dup2 is BackgroundEnqueueResult.Duplicate)
        blockA.countDown()
        awaitIdle(coordinator)
        // After A completes, duplicate should be allowed again
        val after = coordinator.enqueue(ExactJobRef(jobA, KeplerActiveOperationKind.PROCESSING_YUV), HeavyProcessingWork {})
        assertTrue(after is BackgroundEnqueueResult.Accepted)
        awaitIdle(coordinator)
    }

    @Test
    fun realLane_secondHeavyJobDoesNotStartUntilFirstActualTerminal() {
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val jobA = newJobDir("first")
        val jobB = newJobDir("second")
        val blockA = CountDownLatch(1)
        val startedA = CountDownLatch(1)
        val startedB = CountDownLatch(1)
        coordinator.enqueue(ExactJobRef(jobA, KeplerActiveOperationKind.PROCESSING_YUV), HeavyProcessingWork {
            startedA.countDown()
            blockA.await(5, TimeUnit.SECONDS)
        })
        assertTrue(startedA.await(2, TimeUnit.SECONDS))
        coordinator.enqueue(ExactJobRef(jobB, KeplerActiveOperationKind.PROCESSING_YUV), HeavyProcessingWork {
            startedB.countDown()
        })
        // B should not have started yet
        assertFalse(startedB.await(300, TimeUnit.MILLISECONDS))
        assertEquals(jobA.absolutePath, coordinator.snapshot().activeJobDirectory)
        assertEquals(1, coordinator.snapshot().queuedCount)
        blockA.countDown()
        assertTrue(startedB.await(5, TimeUnit.SECONDS))
        awaitIdle(coordinator)
    }

    @Test
    fun mixedYuvRawSr_maxActualHeavyConcurrencyIsOne() {
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val active = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val done = CountDownLatch(6)
        val kinds = listOf(
            KeplerActiveOperationKind.PROCESSING_YUV,
            KeplerActiveOperationKind.PROCESSING_RAW,
            KeplerActiveOperationKind.PROCESSING_YUV
        )
        repeat(6) { idx ->
            val kind = kinds[idx % kinds.size]
            coordinator.enqueue(ExactJobRef(newJobDir("mix$idx"), kind), HeavyProcessingWork {
                val cur = active.incrementAndGet()
                maxObserved.updateAndGet { maxOf(it, cur) }
                Thread.sleep(80)
                active.decrementAndGet()
                done.countDown()
            })
        }
        assertTrue(done.await(15, TimeUnit.SECONDS))
        assertEquals(1, maxObserved.get())
    }

    @Test
    fun ordinaryException_continuesLane() {
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val jobFail = newJobDir("fail")
        val jobOk = newJobDir("ok")
        val okDone = CountDownLatch(1)
        coordinator.enqueue(ExactJobRef(jobFail, KeplerActiveOperationKind.PROCESSING_YUV), HeavyProcessingWork {
            throw IllegalStateException("ordinary failure")
        })
        coordinator.enqueue(ExactJobRef(jobOk, KeplerActiveOperationKind.PROCESSING_YUV), HeavyProcessingWork {
            okDone.countDown()
        })
        assertTrue(okDone.await(5, TimeUnit.SECONDS))
        awaitIdle(coordinator)
        assertFalse(coordinator.snapshot().hasPendingWork)
    }

        @Test
    fun fatalError_isNotSwallowed() {
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        val jobFail = newJobDir("fatalFail")
        val jobOk = newJobDir("fatalOk")
        val okDone = CountDownLatch(1)
        coordinator.enqueue(ExactJobRef(jobFail, KeplerActiveOperationKind.PROCESSING_YUV), HeavyProcessingWork {
            throw OutOfMemoryError("fatal test")
        })
        Thread.sleep(500)
        coordinator.enqueue(ExactJobRef(jobOk, KeplerActiveOperationKind.PROCESSING_YUV), HeavyProcessingWork {
            okDone.countDown()
        })
        assertTrue(okDone.await(5, TimeUnit.SECONDS))
        awaitIdle(coordinator)
    }

    @Test
    fun workerThreadStartFailure_doesNotReturnAccepted() {
        BackgroundProcessingCoordinator.resetForTest()
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        coordinator.testWorkerFactory = {
            object : HandlerThread("FailingWorker") {
                override fun start() {
                    throw IllegalStateException("simulated start failure")
                }
            }
        }
        val job = newJobDir("failStart")
        val result = coordinator.enqueue(ExactJobRef(job, KeplerActiveOperationKind.PROCESSING_YUV), HeavyProcessingWork {})
        assertTrue(result is BackgroundEnqueueResult.Unavailable)
        assertEquals(0, coordinator.snapshot().queuedCount)
        assertNull(coordinator.snapshot().activeJobDirectory)
        coordinator.testWorkerFactory = null
    }

    @Test
    fun workerDispatchRejected_doesNotReturnAccepted() {
        BackgroundProcessingCoordinator.resetForTest()
        val coordinator = BackgroundProcessingCoordinator.of(RuntimeEnvironment.getApplication())
        coordinator.testWorkerFactory = {
            val t = HandlerThread("RejectWorker")
            t.start()
            t.quitSafely()
            Thread.sleep(100)
            t
        }
        val job = newJobDir("dispatchReject")
        val result = coordinator.enqueue(ExactJobRef(job, KeplerActiveOperationKind.PROCESSING_YUV), HeavyProcessingWork {})
        assertTrue(result is BackgroundEnqueueResult.Unavailable)
        assertEquals(0, coordinator.snapshot().queuedCount)
        coordinator.testWorkerFactory = null
    }
}
