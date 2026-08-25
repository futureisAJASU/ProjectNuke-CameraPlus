package com.projectnuke.keplernightlab

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
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
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiObject2
import org.json.JSONArray
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
import java.io.File

/**
 * PACKED_YUV_V1 physical A/B (Phase 7): explicit opt-in, NON-default.
 *
 *   -e kepler.hardwareE2E.packedYuv true
 *
 * The harness itself writes the existing DEBUG strategy SharedPreferences key
 * BEFORE Activity creation/recreation, runs the SAME strict 12MP 4-frame YUV
 * production capture path as Stage-A, then proves the durable job metadata:
 *   - yuvPersistenceStrategy == PACKED_YUV_V1 (survived to terminal);
 *   - captured source manifest uses .yuvpack;
 *   - every packed source passes FULL structural + digest verification;
 *   - background conversion/fusion/export reaches strict PASS classification.
 * The paired PNG reference capture produces a directly comparable
 * HardwareE2E report under the same flag. The SharedPreferences key is always
 * restored to PNG - even when the test fails. Normal Stage-A never depends on
 * this class, and PACKED_YUV_V1 remains out of the production default path.
 */
@RunWith(AndroidJUnit4::class)
class HardwareE2EPackedYuvInstrumentationTest {

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
    fun optInPackedYuvAbPngReferenceCapture() {
        runStrategyCapture(YuvPersistenceStrategy.PNG)
    }

    @Test
    fun optInPackedYuv12MpProductionCapture() {
        runStrategyCapture(YuvPersistenceStrategy.PACKED_YUV_V1)
    }

    // ------------------------------------------------------------------
    // Shared A/B driver
    // ------------------------------------------------------------------

    private fun runStrategyCapture(strategy: YuvPersistenceStrategy) {
        assumeTrue(
            "kepler.hardwareE2E.packedYuv=true is required",
            packedYuvEnabled()
        )
        assumeTrue("usable camera is required", hasUsableCamera())
        assumeTrue(
            "12MP YUV is unsupported on this device",
            selectedCapability()?.yuv12Available == true
        )
        assumeInteractiveUnlocked()

        val settingsPrefs = strategyPreferences()
        // Arm the DEBUG strategy BEFORE Activity creation/recreation so the
        // capture creation seam resolves exactly this strategy.
        settingsPrefs.edit()
            .putString("yuvPersistenceStrategy", strategy.name)
            .commit()
        try {
            ensureActivityReadyForUi()
            configureSettings(PipelineMode.YUV_NIGHT_FUSION.name)

            val previousRunId = HardwareE2EReportStore.readLatest(targetContext)?.runId
            val invocationStart = System.currentTimeMillis()
            val shutter = awaitUiObject("kepler.camera.shutter", 5_000L)
            assertTrue("shutter not enabled", shutter.isEnabled)
            shutter.click()

            val report = awaitExactTerminalReport(
                previousRunId = previousRunId,
                invocationStart = invocationStart,
                expectedPipeline = PipelineMode.YUV_NIGHT_FUSION.name
            )
            assertSuccessfulSmoke(report, PipelineMode.YUV_NIGHT_FUSION.name)

            val job = report.finalJob!!
            assertEquals(4, job.requestedFrames)

            // Durable strategy truth at the TERMINAL job metadata.
            val jobDir = File(report.latestJobDirectory.orEmpty())
            assertTrue("job directory missing: $jobDir", jobDir.isDirectory)
            val jobJson = KeplerJobMetadata.read(jobDir)
            assertEquals(
                "durable yuvPersistenceStrategy must equal the armed strategy",
                strategy.name,
                jobJson.optString(YuvPersistenceStrategy.JOB_KEY)
            )
            assertTrue(
                "packed A/B requires the buffered pipeline (12MP x 4 frames)",
                jobJson.optBoolean("yuvMemoryBufferUsed", false)
            )

            if (strategy == YuvPersistenceStrategy.PACKED_YUV_V1) {
                // Background conversion actually ran on the serialized lane.
                assertTrue(
                    "packed sources were not converted by the background lane",
                    jobJson.optBoolean("packedSourcesConverted", false)
                )
                assertTrue(jobJson.optLong("unpackConvertMs", -1L) >= 0L)
                // Every persisted frame was captured as .yuvpack AND verifies
                // FULLY (structure + streaming SHA-256 digest).
                verifyAllFramesAreFullyVerifiedPackedSources(jobDir, jobJson.optJSONArray("frames"))
            } else {
                assertFalse(
                    "PNG reference must not be treated as a packed-conversion target",
                    jobJson.optBoolean("packedSourcesConverted", false)
                )
                verifyAllFramesArePngSources(jobJson.optJSONArray("frames"))
            }

            assertTrue(job.requiredOutputFilePresent)
            assertTrue(report.terminalFlags["requiredOutputCommitted"] == true)
            assertTrue(job.exportStatus.uppercase() !in setOf("FAILED", "CANCELLED", "ERROR"))
            waitForPipelineIdle(30_000L)
            println(
                "PACKED_YUV_AB_EVIDENCE strategy=${strategy.name} " +
                    "runId=${report.runId} jobDir=$jobDir " +
                    "postAcquisitionToShutterMs=${report.finalJob?.captureTiming?.postAcquisitionToShutterMs} " +
                    "persistenceDrainMs=${report.finalJob?.captureTiming?.persistenceDrainMs} " +
                    "cameraAcquisitionMs=${report.finalJob?.captureTiming?.cameraAcquisitionMs}"
            )
        } finally {
            // ALWAYS restore the production default, even on failure.
            settingsPrefs.edit()
                .putString("yuvPersistenceStrategy", YuvPersistenceStrategy.PNG.name)
                .commit()
        }
    }

    /** Every persisted frame must be a packed source that passes [verifyFull]. */
    private fun verifyAllFramesAreFullyVerifiedPackedSources(jobDir: File, frames: JSONArray?) {
        assertNotNull("job has no frames manifest", frames)
        assertTrue("frames manifest is empty", frames!!.length() > 0)
        for (index in 0 until frames.length()) {
            val frame = frames.getJSONObject(index)
            val packedName = frame.optString("packedSourceFilename")
                .ifBlank { frame.optString("filename") }
                .ifBlank { frame.optString("file") }
            assertTrue(
                "frame $index source is not a .yuvpack: $packedName",
                packedName.endsWith(PackedYuvFrameStore.FILE_EXTENSION)
            )
            val decoded = PackedYuvFrameStore.verifyFull(File(jobDir, packedName))
            assertEquals(decoded.frameIndex, frame.optInt("frameIndex"))
        }
    }

    private fun verifyAllFramesArePngSources(frames: JSONArray?) {
        assertNotNull("job has no frames manifest", frames)
        assertTrue("frames manifest is empty", frames!!.length() > 0)
        for (index in 0 until frames.length()) {
            val name = frames.getJSONObject(index).optString("file")
            assertTrue("frame $index source is not PNG: $name", name.endsWith(".png"))
        }
    }

    // ------------------------------------------------------------------
    // Harness helpers (mirrored from Stage-A suite)
    // ------------------------------------------------------------------

    private fun strategyPreferences(): SharedPreferences =
        targetContext.getSharedPreferences("kepler_camera_settings", Context.MODE_PRIVATE)

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
            awaitCondition(180_000L) {
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
        val report = HardwareE2EReportStore.read(targetContext, exactRunId)
            ?: throw AssertionError("Exact hardware report disappeared for runId=$exactRunId")
        println("HARDWARE_E2E_RUN_ID=$exactRunId")
        return report
    }

    private fun assertSuccessfulSmoke(report: HardwareE2ERunReport, expectedPipeline: String) {
        assertEquals(
            "Production smoke failed: ${report.toJson().toString(2)}",
            HardwareE2EClassification.PASS,
            report.status
        )
        assertEquals(HardwareE2EClassificationReason.PASS_SUCCESS, report.classificationReason)
        assertEquals("production_main_camera_screen", report.scenario.requestedTestScenario)
        assertEquals(expectedPipeline, report.scenario.selectedPipelineMode)
        assertEquals(HardwareE2EJobCorrelation.EXACT, report.jobCorrelation)
        assertTrue(report.terminalEvent == CameraPipelineEvent.Terminal.Kind.COMPLETE.name)
        assertTrue(report.latestJobDirectory?.isNotBlank() == true)
        assertTrue(report.finalJob?.readable == true)
        assertFalse(report.finalJob?.liveOperationRegistered == true)
        assertTrue(report.finalJob?.requiredOutputFilePresent == true)
        assertTrue(report.terminalFlags["captureResourcesSettled"] == true)
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

    private fun packedYuvEnabled(): Boolean =
        InstrumentationRegistry.getArguments()
            .getString("kepler.hardwareE2E.packedYuv")
            ?.equals("true", ignoreCase = true) == true
}
