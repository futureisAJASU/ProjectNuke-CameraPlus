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
import org.junit.Assert.assertNotEquals
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
 *
 * Run identity: each sequential run is PINNED at its own evidenced
 * CaptureStageComplete - BEFORE the next capture starts - through
 * [HardwareE2EStageBRunPinning]. Same-pipeline pairs (YUV->YUV, RAW->RAW) can
 * never be misidentified through a newest-first "latest matching pipeline"
 * scan, and terminal waits read EXACT pinned run ids only.
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
        if (pipelineA == PipelineMode.RAW_NIGHT_FUSION.name ||
            pipelineB == PipelineMode.RAW_NIGHT_FUSION.name
        ) {
            assumeTrue("12MP RAW unsupported on this device", selectedCapability()?.raw12Available == true)
        }
        assumeInteractiveUnlocked()
        ensureActivityReadyForUi()

        // Deterministic harness order (documented contract; executed inline):
        // CONFIGURE_A, CLICK_A, PIN_A, [CONFIGURE_B if mixed], CLICK_B, PIN_B,
        // RELEASE_LANE, TERMINAL_A_BY_ID, TERMINAL_B_BY_ID.
        HardwareE2EStageBRunPinning.stageBPlan(pipelineA, pipelineB)

        // Configure capture A (settings are durable across recreate).
        configureSettings(pipelineA)

        // Every run that already exists before capture A is baseline identity:
        // neither A nor B may ever resolve to one of them.
        val baselineRunIds = HardwareE2EReportStore.readReports(targetContext)
            .mapTo(HashSet()) { it.runId }
        val invocationStart = System.currentTimeMillis()

        // Hold the heavy lane BEFORE any processing begins so A stays
        // non-terminal while B captures - deterministic overlap.
        val releaseLane = java.util.concurrent.CountDownLatch(1)
        KeplerBackgroundExecutor.heavyLaneGateForTest = { releaseLane.await() }

        var queuedPeakObserved = 0
        try {
            // ---- Capture A ----
            val shutterA = awaitUiObject("kepler.camera.shutter", 5_000L)
            assertTrue("shutter A not enabled", shutterA.isEnabled)
            shutterA.click()

            // A reaches its evidenced CaptureStageComplete -> shutter admission
            // returns WHILE A processing is provably held (non-terminal).
            awaitShutterAdmission(timeoutMs = 60_000L)
            assertTrue("lane gate did not hold job A", releaseLane.count > 0)

            // PIN runIdA IMMEDIATELY at A's evidenced handoff - before B exists.
            val reportAHandoff = awaitNewRunAtHandoff(
                excludedRunIds = baselineRunIds,
                invocationStart = invocationStart,
                expectedPipeline = pipelineA,
                timeoutMs = 60_000L
            )
            val runIdA = reportAHandoff.runId
            val aHandoffAt = System.currentTimeMillis()
            assertEquals(pipelineA, reportAHandoff.scenario.selectedPipelineMode)
            val jobDirA = requireNotNull(reportAHandoff.latestJobDirectory) {
                "pinned A handoff carries no exact job directory (runId=$runIdA)"
            }

            // ---- Configure B strictly BEFORE B's capture attempt ----
            // CameraScreen captures the Compose pipelineMode when the shutter
            // click builds the attempt, so persisting B's mode after the click
            // would silently capture B with A's pipeline. Recreating the
            // Activity rebinds Compose state; A's background work is held by
            // the process-scoped lane gate and survives recreation.
            if (HardwareE2EStageBRunPinning.requiresConfigurationBeforeSecondClick(pipelineA, pipelineB)) {
                configureSettings(pipelineB)
                assertEquals(
                    "pipeline B was not actually selected before B's capture attempt",
                    pipelineB,
                    CameraSettingsStore.load(targetContext).pipelineModeName
                )
                awaitShutterAdmission(timeoutMs = 30_000L)
            }

            // ---- Capture B (before A terminal by construction) ----
            val bClickAt = System.currentTimeMillis()
            val shutterB = awaitUiObject("kepler.camera.shutter", 5_000L)
            assertTrue("shutter B not enabled", shutterB.isEnabled)
            shutterB.click()

            // B must reach ITS OWN evidenced handoff too...
            awaitShutterAdmission(timeoutMs = 90_000L)
            // ...and be PINNED at that instant with proof of identity.
            val reportBHandoff = awaitNewRunAtHandoff(
                excludedRunIds = baselineRunIds + runIdA,
                invocationStart = invocationStart,
                expectedPipeline = pipelineB,
                timeoutMs = 90_000L
            )
            val bHandoffAt = System.currentTimeMillis()
            assertTrue(bHandoffAt > bClickAt)
            val runIdB = reportBHandoff.runId
            assertNotEquals("A and B must pin distinct run identities", runIdA, runIdB)
            // B's persisted scenario proves the configuration actually applied;
            // this is checked BEFORE the heavy lane is released.
            assertEquals(
                "B captured with the wrong configured pipeline",
                pipelineB,
                reportBHandoff.scenario.selectedPipelineMode
            )
            val jobDirB = requireNotNull(reportBHandoff.latestJobDirectory) {
                "pinned B handoff carries no exact job directory (runId=$runIdB)"
            }
            assertNotEquals("job dirs must be distinct: $jobDirA vs $jobDirB", jobDirA, jobDirB)

            // While A is held at the gate, the single serialized coordinator is
            // the authority for heavy concurrency: active == A exactly, B queued
            // behind it, queue depth >= 1.
            awaitCondition(30_000L) {
                val snapshot = BackgroundProcessingCoordinator.of(targetContext).snapshot()
                if (snapshot.queuedCount > queuedPeakObserved) {
                    queuedPeakObserved = snapshot.queuedCount
                }
                snapshot.activeJobDirectory == jobDirA &&
                    snapshot.queuedCount >= 1 &&
                    snapshot.queuedJobDirectories.contains(jobDirB)
            }

            // Release A's processing: lane drains FIFO (A then B).
            releaseLane.countDown()

            // ---- Terminals by EXACT pinned run id (never "latest" scans) ----
            val reportA = awaitTerminalByPinnedRunId(runIdA)
            val aTerminalAt = System.currentTimeMillis()
            val reportB = awaitTerminalByPinnedRunId(runIdB)
            val bTerminalAt = System.currentTimeMillis()

            println(
                "STAGE_B_EVIDENCE " +
                    "pipelineA=$pipelineA pipelineB=$pipelineB " +
                    "runIdA=$runIdA jobDirA=$jobDirA " +
                    "runIdB=$runIdB jobDirB=$jobDirB " +
                    "bClickAt=$bClickAt aHandoffAt=$aHandoffAt bHandoffAt=$bHandoffAt " +
                    "aTerminalAt=$aTerminalAt bTerminalAt=$bTerminalAt " +
                    "coordinatorQueuedPeak=$queuedPeakObserved"
            )

            assertSequentialSmoke(reportA, pipelineA)
            assertSequentialSmoke(reportB, pipelineB)

            // Exact correlation, strict outputs, no live lease.
            assertEquals(HardwareE2EJobCorrelation.EXACT, reportA.jobCorrelation)
            assertEquals(HardwareE2EJobCorrelation.EXACT, reportB.jobCorrelation)
            assertFalse(reportA.finalJob?.liveOperationRegistered == true)
            assertFalse(reportB.finalJob?.liveOperationRegistered == true)
            assertTrue(reportA.finalJob?.requiredOutputFilePresent == true)
            assertTrue(reportB.finalJob?.requiredOutputFilePresent == true)
            assertTrue(reportA.terminalFlags["captureResourcesSettled"] == true)
            assertTrue(reportB.terminalFlags["captureResourcesSettled"] == true)

            // Distinct public outputs / no metadata overwrite.
            val dirA = reportA.latestJobDirectory.orEmpty()
            val dirB = reportB.latestJobDirectory.orEmpty()
            assertTrue("job dirs must be distinct: $dirA vs $dirB", dirA.isNotEmpty() && dirB.isNotEmpty())
            assertEquals(jobDirA, dirA)
            assertEquals(jobDirB, dirB)

            // A finalized AFTER B started (deterministic via the gate).
            val aSettledAt = reportA.runEndWallClockTimestamp ?: 0L
            assertTrue(
                "A terminal ($aSettledAt) unexpectedly preceded B click ($bClickAt)",
                aSettledAt <= 0L || aSettledAt >= bClickAt
            )
            assertTrue(
                "coordinator never observed B queued while A was held (peak=$queuedPeakObserved)",
                queuedPeakObserved >= 1
            )
        } finally {
            releaseLane.countDown()
            KeplerBackgroundExecutor.heavyLaneGateForTest = null
            waitForPipelineIdle(30_000L)
        }
    }

    /**
     * Pins ONE new run at its evidenced handoff. Identity is unambiguous HERE:
     * every pre-existing or already-pinned run is excluded, so a same-pipeline
     * successor can never be mistaken for the run being pinned.
     */
    private fun awaitNewRunAtHandoff(
        excludedRunIds: Set<String>,
        invocationStart: Long,
        expectedPipeline: String,
        timeoutMs: Long
    ): HardwareE2ERunReport {
        var pinned: HardwareE2ERunReport? = null
        try {
            awaitCondition(timeoutMs) {
                if (pinned == null) {
                    pinned = HardwareE2EReportStore.findNewHandoffRunAfter(
                        context = targetContext,
                        excludedRunIds = excludedRunIds,
                        invocationStartWallClock = invocationStart,
                        expectedScenario = "production_main_camera_screen",
                        expectedPipeline = expectedPipeline
                    )
                }
                pinned != null
            }
        } catch (timeout: Throwable) {
            throw AssertionError(
                "Timed out waiting for new evidenced hardware run at handoff " +
                    "pipeline=$expectedPipeline excluded=${excludedRunIds.size} runs",
                timeout
            )
        }
        return pinned ?: throw AssertionError("No new evidenced hardware run was observed")
    }

    /** Waits for terminal on the EXACT pinned run id; never searches for another match. */
    private fun awaitTerminalByPinnedRunId(runId: String): HardwareE2ERunReport {
        try {
            awaitCondition(240_000L) {
                HardwareE2EReportStore.read(targetContext, runId)
                    ?.let(HardwareE2EStageBRunPinning::isTerminalReady) == true
            }
        } catch (timeout: Throwable) {
            throw AssertionError(
                "Timed out waiting for pinned run terminal runId=$runId",
                timeout
            )
        }
        return HardwareE2EReportStore.read(targetContext, runId)
            ?: throw AssertionError("Exact hardware report disappeared for pinned runId=$runId")
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
