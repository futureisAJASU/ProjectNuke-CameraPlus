package com.projectnuke.keplernightlab

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Exercises the REAL ColorFusion YUV production seam ([YuvColorFusionProductionSeam]):
 * the same production Main-thread dispatchers, pre-session terminal, production
 * resource coordinator, and exactly-once cleanup that `captureYuvBurstColorWithMotion`
 * constructs — no fakes, no camera hardware.  All waits are deterministic
 * (CountDownLatch / looper idle); no sleeps or polling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21])
class ColorFusionProductionSeamTest {

    private class SeamHarness {
        val statusMessages = java.util.Collections.synchronizedList(mutableListOf<String>())
        val errorMessages = java.util.Collections.synchronizedList(mutableListOf<String>())
        val backgroundThread = HandlerThread("seam-bg").apply { start() }
        val backgroundHandler = Handler(backgroundThread.looper)
        val timeoutScheduler = Executors.newSingleThreadScheduledExecutor()
        val sessionCloseCount = AtomicInteger(0)

        val seam = YuvColorFusionProductionSeam(
            mainHandler = Handler(Looper.getMainLooper()),
            timeoutScheduler = timeoutScheduler,
            backgroundHandler = backgroundHandler,
            backgroundThread = backgroundThread,
            onStatus = { statusMessages.add(it) },
            onError = { errorMessages.add(it) }
        ).also { it.sessionClose = { sessionCloseCount.incrementAndGet() } }

        fun idleMain() {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun statusDispatchIsPostedToMainAndNeverInline() {
        val harness = SeamHarness()
        try {
            harness.seam.postStatus("step 1")
            // Never executed inline on the caller thread.
            assertEquals(0, harness.statusMessages.size)
            harness.idleMain()
            assertEquals(listOf("step 1"), harness.statusMessages.toList())
        } finally {
            harness.seam.productionCleanup()
        }
    }

    @Test
    fun callbackDispatchExecutesOnMainExactlyOnce() {
        val harness = SeamHarness()
        try {
            val executed = CountDownLatch(1)
            val accepted = harness.seam.callbackDispatcher.dispatch(Runnable { executed.countDown() })
            assertTrue("callback dispatch must be accepted", accepted)
            assertFalse("callback must NOT run inline", executed.await(200, TimeUnit.MILLISECONDS))
            harness.idleMain()
            assertTrue("callback must run on Main after idle", executed.await(5, TimeUnit.SECONDS))
        } finally {
            harness.seam.productionCleanup()
        }
    }

    @Test
    fun preSessionTerminalClaimsExactlyOnceAndDispatchesStatusAndErrorOnce() {
        val harness = SeamHarness()
        try {
            assertTrue(harness.seam.preSessionTerminal.finish("pre-session boom"))
            // Dispatch is posted to Main, never inline.
            assertEquals(0, harness.statusMessages.size)
            assertEquals(0, harness.errorMessages.size)
            harness.idleMain()
            assertEquals(listOf("pre-session boom"), harness.statusMessages.toList())
            assertEquals(listOf("pre-session boom"), harness.errorMessages.toList())
            // A second finish is a no-op: exactly one claim, no duplicate dispatch.
            assertFalse(harness.seam.preSessionTerminal.finish("pre-session boom"))
            assertTrue(harness.seam.preSessionTerminal.isTerminal())
            harness.idleMain()
            assertEquals(1, harness.statusMessages.size)
            assertEquals(1, harness.errorMessages.size)
        } finally {
            harness.seam.productionCleanup()
        }
    }

    @Test
    fun preSessionTerminalRunsProductionCleanupExactlyOnce() {
        val harness = SeamHarness()
        try {
            harness.seam.preSessionTerminal.finish("pre-session boom")
            harness.idleMain()
            assertEquals(1, harness.seam.productionResourceCoordinator.performCount())
            assertEquals(1, harness.sessionCloseCount.get())
            assertTrue(harness.seam.productionResourceCoordinator.snapshot().isTerminal)
            // Repeated finish never re-runs cleanup.
            harness.seam.preSessionTerminal.finish("again")
            assertEquals(1, harness.seam.productionResourceCoordinator.performCount())
            assertEquals(1, harness.sessionCloseCount.get())
        } finally {
            harness.seam.productionCleanup()
        }
    }

    @Test
    fun productionCleanupIsExactlyOnceAndReleasesAttachedInfrastructure() {
        val harness = SeamHarness()
        try {
            assertEquals(CoordinatorLifecyclePhase.OPEN, harness.seam.productionResourceCoordinator.lifecyclePhase())
            harness.seam.productionCleanup()
            harness.seam.productionCleanup()
            val snap = harness.seam.productionResourceCoordinator.snapshot()
            assertTrue(snap.isTerminal)
            assertEquals(1, snap.performCount)
            // session.close() itself is idempotent (handoff CAS + coordinators), so a
            // repeated productionCleanup invokes it again without double-releasing.
            assertTrue(harness.sessionCloseCount.get() >= 1)
            val tags = harness.seam.productionResourceCoordinator.releasedResourceTags()
            assertTrue(tags.contains("Background.handler"))
            assertTrue(tags.contains("BackgroundThread.quit"))
            assertTrue(tags.contains("TimeoutScheduler.shutdown"))
        } finally {
            harness.seam.productionCleanup()
        }
    }
}
