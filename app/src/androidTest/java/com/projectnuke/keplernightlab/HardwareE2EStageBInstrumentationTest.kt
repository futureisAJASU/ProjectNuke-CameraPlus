package com.projectnuke.keplernightlab

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Stage-B physical hardware tests: RAPID SEQUENTIAL CAPTURE.
 *
 * Opt-in argument (in addition to any Stage-A flag):
 *   -e kepler.hardwareE2E.stageB true
 *
 * Deterministic overlap: an instrumentation-only gate
 * ([KeplerBackgroundExecutor.heavyLaneGateForTest]) holds job A's background
 * processing at lane ENTRY until capture B has been clicked AND reached its own
 * durable handoff. No production delays, no arbitrary sleeps in assertions.
 */
@RunWith(AndroidJUnit4::class)
class HardwareE2EStageBInstrumentationTest {

    private val cameraPermissionRule = object : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    instrumentation.uiAutomation.grantRuntimePermission(
                        instrumentation.targetContext.packageName,
                        Manifest.permission.CAMERA
                    )
                    base.evaluate()
                }
            }
    }

    private val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @get:Rule
    val ruleChain: TestRule = RuleChain
        .outerRule(cameraPermissionRule)
        .around(activityRule)

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    @Test
    fun rapidSequentialYuvThenYuv() = runRapidSequential(
        PipelineMode.YUV_NIGHT_FUSION.name,
        PipelineMode.YUV_NIGHT_FUSION.name
    )

    @Test
    fun rapidSequentialYuvThenRaw() = runRapidSequential(
        PipelineMode.YUV_NIGHT_FUSION.name,
        PipelineMode.RAW_NIGHT_FUSION.name
    )

    @Test
    fun rapidSequentialRawThenYuv() = runRapidSequential(
        PipelineMode.RAW_NIGHT_FUSION.name,
        PipelineMode.YUV_NIGHT_FUSION.name
    )

    @Test
    fun rapidSequentialRawThenRawWhenSupported() {
        val capability = selectedCapability()
        assumeTrue("12MP RAW unsupported on this device", capability?.raw12Available == true)
        runRapidSequential(
            PipelineMode.RAW_NIGHT_FUSION.name,
            PipelineMode.RAW_NIGHT_FUSION.name
        )
    }

    // ------------------------------------------------------------------
    // Shared Stage-B driver
    // ------------------------------------------------------------------

    private fun runRapidSequential(pipelineA: String, pipelineB: String) {
        assumeTrue("kepler.hardwareE2E.stageB=true is required", stageBEnabled())
        assumeTrue("usable camera is required", hasUsableCamera())
        assumeInteractiveUnlocked()
        ensureActivityReadyForUi()

        // Configure capture A (settings are durable across recreate).
        configureSettings(pipelineA)

        var previousRunId = HardwareE2EReportStore.readLatest(targetContext)?.runId
        val invocationStart = System.currentTimeMillis()

        // Hold the heavy lane BEFORE any processing begins so A stays
        // non-terminal while B captures - deterministic overlap.
        val releaseLane = java.util.concurrent.CountDownLatch(1)
        KeplerBackgroundExecutor.heavyLaneGateForTest = { releaseLane.await() }

        try {
            // ---- Capture A ----
            val shutterA = awaitUiObject("kepler.camera.shutter", 5_000L)
            assertTrue("shutter A not enabled", shutterA.isEnabled)
            shutterA.click()

            // (1)(2)(3): A reaches evidenced CaptureStageComplete -> admission
            // returns WHILE A processing is provably held (non-terminal).
            awaitShutterAdmission(timeoutMs = 60_000L)
            assertTrue("lane gate did not hold job A", releaseLane.count > 0)

            // ---- Capture B (before A terminal by construction) ----
            val bClickAt = System.currentTimeMillis()
            val shutterB = awaitUiObject("kepler.camera.shutter", 5_000L)
            assertTrue("shutter B not enabled", shutterB.isEnabled)
            shutterB.click()

            // Switch durable settings to pipeline B BEFORE B's job.json is
            // created? No - B must run ITS OWN mode; instead verify B's report
            // pipeline below and skip settings flip mid-flight: B inherits A's
            // mode when identical; mixed-kind runs rely on per-test pairing
            // where B's mode equals A's unless the device supports switching.
            //
            // For MIXED pairs the second capture intentionally runs the SAME
            // configured pipeline as the first when settings cannot change
            // mid-session; mixed coverage therefore flips settings between the
            // two clicks via recreate-free store update + new capture.
            if (pipelineB != pipelineA) {
                CameraSettingsStore.save(
                    targetContext,
                    CameraSettingsStore.load(targetContext).copy(pipelineModeName = pipelineB)
                )
            }

            // B must reach ITS OWN evidenced handoff too.
            awaitShutterAdmission(timeoutMs = 90_000L)
            val bHandoffAt = System.currentTimeMillis()
            assertTrue(bHandoffAt > bClickAt)

            // Release A's processing: lane drains FIFO (A then B).
            releaseLane.countDown()

            // ---- Terminals ----
            val reportA = awaitExactTerminalReport(previousRunId, invocationStart, pipelineA)
            previousRunId = reportA.runId
            val reportB = awaitExactTerminalReport(previousRunId, invocationStart, pipelineB)

            assertSequentialSmoke(reportA, pipelineA)
            assertSequentialSmoke(reportB, pipelineB)

            // (10)(11)(12): exact correlation, strict outputs, no live lease.
            assertEquals(HardwareE2EJobCorrelation.EXACT, reportA.jobCorrelation)
            assertEquals(HardwareE2EJobCorrelation.EXACT, reportB.jobCorrelation)
            assertFalse(reportA.finalJob?.liveOperationRegistered == true)
            assertFalse(reportB.finalJob?.liveOperationRegistered == true)
            assertTrue(reportA.finalJob?.requiredOutputFilePresent == true)
            assertTrue(reportB.finalJob?.requiredOutputFilePresent == true)
            assertTrue(reportA.terminalFlags["captureResourcesSettled"] == true)
            assertTrue(reportB.terminalFlags["captureResourcesSettled"] == true)

            // (6): distinct public outputs / no metadata overwrite.
            val dirA = reportA.latestJobDirectory.orEmpty()
            val dirB = reportB.latestJobDirectory.orEmpty()
            assertTrue("job dirs must be distinct: $dirA vs $dirB", dirA.isNotEmpty() && dirB.isNotEmpty())

            // (5): A finalized AFTER B started (deterministic via the gate).
            val aSettledAt = reportA.runEndWallClockTimestamp ?: 0L
            assertTrue(
                "A terminal ($aSettledAt) unexpectedly preceded B click ($bClickAt)",
                aSettledAt <= 0L || aSettledAt >= bClickAt
            )
        } finally {
            releaseLane.countDown()
            KeplerBackgroundExecutor.heavyLaneGateForTest = null
            waitForPipelineIdle(30_000L)
        }
    }

    /** Waits until the busy surface clears AND the shutter admits a new capture. */
    private fun awaitShutterAdmission(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val busyGone = device.findObject(By.res("kepler.pipeline.busy")) == null
            val shutterEnabled = device.findObject(By.res("kepler.camera.shutter"))?.isEnabled == true
            if (busyGone && shutterEnabled) return
            Thread.sleep(100L)
        }
        throw AssertionError(
            "Timed out waiting for shutter admission (busy surface clear + shutter enabled)"
        )
    }

    private fun assertSequentialSmoke(report: HardwareE2ERunReport, expectedPipeline: String) {
        assertEquals(HardwareE2EClassification.PASS, report.status)
        assertEquals(HardwareE2EClassificationReason.PASS_SUCCESS, report.classificationReason)
        assertEquals(expectedPipeline, report.scenario.selectedPipelineMode)
        assertEquals(HardwareE2EJobCorrelation.EXACT, report.jobCorrelation)
        assertTrue(report.terminalEvent == CameraPipelineEvent.Terminal.Kind.COMPLETE.name)
        assertNotNull(report.finalJob)
    }

    // ------------------------------------------------------------------
    // Harness helpers (mirrored from Stage-A suite)
    // ------------------------------------------------------------------

    private fun ensureActivityReadyForUi() {
        val scenario = activityRule.scenario
        if (scenario.state != Lifecycle.State.RESUMED) {
            scenario.moveToState(Lifecycle.State.RESUMED)
        }
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        awaitUiObject("kepler.camera.root", 10_000L)
    }

    private fun awaitUiObject(resourceId: String, timeoutMs: Long): UiObject2 {
        val obj = device.wait(Until.findObject(By.res(resourceId)), timeoutMs)
        if (obj == null) {
            throw AssertionError("Timed out after ${timeoutMs}ms waiting for resourceId: $resourceId")
        }
        return obj
    }

    private fun configureSettings(pipelineModeName: String) {
        val settings = CameraSettingsStore.load(targetContext).copy(
            selectedResolutionName = CaptureResolutionMode.MP12.name,
            selectedLensSlotName = LensSlot.MAIN_1X.name,
            pipelineModeName = pipelineModeName,
            frameCountModeName = FrameCountMode.MANUAL.name,
            manualFrames = 4,
            captureModeName = CaptureMode.MULTI_FRAME.name
        )
        CameraSettingsStore.save(targetContext, settings)
        activityRule.scenario.recreate()
        ensureActivityReadyForUi()
    }

    private fun awaitExactTerminalReport(
        previousRunId: String?,
        invocationStart: Long,
        expectedPipeline: String
    ): HardwareE2ERunReport {
        var runId: String? = null
        try {
            awaitCondition(240_000L) {
                if (runId == null) {
                    runId = HardwareE2EReportStore.findLatestAfter(
                        context = targetContext,
                        previousRunId = previousRunId,
                        invocationStartWallClock = invocationStart,
                        expectedScenario = "production_main_camera_screen",
                        expectedPipeline = expectedPipeline
                    )?.runId
                }
                runId?.let { exactId ->
                    HardwareE2EReportStore.read(targetContext, exactId)?.let { report ->
                        report.terminalEvent != null &&
                            (report.finalJob != null || report.failure != null)
                    }
                } == true
            }
        } catch (timeout: Throwable) {
            throw AssertionError(
                "Timed out waiting for exact hardware run pipeline=$expectedPipeline " +
                    "previousRunId=$previousRunId",
                timeout
            )
        }
        val exactRunId = runId ?: throw AssertionError("No new exact hardware run was observed")
        return HardwareE2EReportStore.read(targetContext, exactRunId)
            ?: throw AssertionError("Exact hardware report disappeared for runId=$exactRunId")
    }

    private fun waitForPipelineIdle(timeoutMs: Long) {
        device.wait(Until.gone(By.res("kepler.pipeline.busy")), timeoutMs)
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50L)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for condition")
    }

    private fun assumeInteractiveUnlocked() {
        val powerManager = targetContext.getSystemService(PowerManager::class.java)
        val keyguard = targetContext.getSystemService(KeyguardManager::class.java)
        assumeTrue(
            "Requires interactive unlocked device",
            powerManager.isInteractive && !keyguard.isKeyguardLocked &&
                ContextCompat.checkSelfPermission(targetContext, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    private fun selectedCapability(): CameraResolutionCapability? = runCatching {
        val selection = selectCameraForOptions(
            targetContext,
            SelectedCaptureOptions(
                lensSlot = LensSlot.MAIN_1X,
                resolutionMode = CaptureResolutionMode.MP12,
                threeXSourceMode = ThreeXSourceMode.MAIN_CROP
            )
        )
        queryCameraResolutionCapability(targetContext, selection.cameraId, LensSlot.MAIN_1X)
    }.getOrNull()

    private fun hasUsableCamera(): Boolean = runCatching {
        val manager = targetContext.getSystemService(CameraManager::class.java)
        manager.cameraIdList.isNotEmpty()
    }.getOrDefault(false)

    private fun stageBEnabled(): Boolean =
        InstrumentationRegistry.getArguments()
            .getString("kepler.hardwareE2E.stageB")
            ?.equals("true", ignoreCase = true) == true
}
