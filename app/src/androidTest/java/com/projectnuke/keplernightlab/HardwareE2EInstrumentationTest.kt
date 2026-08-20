package com.projectnuke.keplernightlab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.ContextCompat
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareE2EInstrumentationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val targetContext: Context
        get() = instrumentation.targetContext

    @Test
    fun appLaunchesAndDiagnosticReportCanBeCreatedAndRead() {
        assertEquals("com.projectnuke.keplernightlab", targetContext.packageName)
        grantCameraPermission()
        composeRule.activityRule.scenario.recreate()
        val hasUsableCamera = hasUsableCamera()
        if (hasUsableCamera) {
            composeRule.onNodeWithTag("kepler.camera.root").assertIsDisplayed()
            composeRule.onNodeWithTag("kepler.settings.open").assertIsDisplayed()
            composeRule.onNodeWithTag("kepler.camera.shutter").assertIsDisplayed()
        }

        val recorder = HardwareE2ERunRecorder.forContext(targetContext)
        recorder.start(defaultScenario().copy(requestedTestScenario = "instrumentation_report_smoke"))
        recorder.recordSkipped("basic instrumentation report creation")
        assertTrue(recorder.awaitIdle())
        assertEquals(
            HardwareE2EClassification.SKIPPED_UNSUPPORTED,
            HardwareE2EReportStore.readLatest(targetContext)?.status
        )
        recorder.close()
    }

    @Test
    fun optInYuv12MpMainCameraProductionBurst() {
        assumeTrue("kepler.hardwareE2E=true is required", hardwareE2EEnabled())
        grantCameraPermission()
        composeRule.activityRule.scenario.recreate()
        assumeTrue("usable camera is required", hasUsableCamera())
        val capability = selectedCapability()
        assumeTrue("12MP YUV is unsupported", capability?.yuv12Available == true)

        configureSettings(PipelineMode.YUV_NIGHT_FUSION.name)
        val previousRunId = HardwareE2EReportStore.readLatest(targetContext)?.runId
        val invocationStart = System.currentTimeMillis()
        composeRule.onNodeWithTag("kepler.camera.shutter").performClick()
        val report = awaitExactTerminalReport(
            previousRunId = previousRunId,
            invocationStart = invocationStart,
            expectedPipeline = PipelineMode.YUV_NIGHT_FUSION.name
        )
        assertSuccessfulSmoke(report, PipelineMode.YUV_NIGHT_FUSION.name)

        val job = report.finalJob!!
        assertEquals(4, job.requestedFrames)
        assertTrue(job.attemptedFrames >= job.savedFrames)
        assertTrue(job.savedFrames <= job.requestedFrames)
        assertTrue(job.receivedImages >= job.completedResults)
        assertTrue(job.requiredOutputFilePresent)
        assertTrue(report.terminalFlags["requiredOutputCommitted"] == true)
        assertTrue(job.exportStatus.uppercase() !in setOf("FAILED", "CANCELLED", "ERROR"))
        waitForPipelineIdle()
        composeRule.onNodeWithTag("kepler.camera.shutter").assertIsDisplayed()
    }

    @Test
    fun optInRaw12MpMainCameraProductionBurstWhenSupported() {
        assumeTrue("kepler.hardwareE2E=true is required", hardwareE2EEnabled())
        grantCameraPermission()
        composeRule.activityRule.scenario.recreate()
        assumeTrue("usable camera is required", hasUsableCamera())
        val capability = selectedCapability()
        assumeTrue("12MP RAW is unsupported", capability?.raw12Available == true)

        configureSettings(PipelineMode.RAW_NIGHT_FUSION.name)
        val previousRunId = HardwareE2EReportStore.readLatest(targetContext)?.runId
        val invocationStart = System.currentTimeMillis()
        composeRule.onNodeWithTag("kepler.camera.shutter").performClick()
        val report = awaitExactTerminalReport(
            previousRunId = previousRunId,
            invocationStart = invocationStart,
            expectedPipeline = PipelineMode.RAW_NIGHT_FUSION.name
        )
        assertSuccessfulSmoke(report, PipelineMode.RAW_NIGHT_FUSION.name)

        val job = report.finalJob!!
        assertEquals(4, job.requestedFrames)
        assertTrue(job.attemptedFrames >= job.savedFrames)
        assertTrue(job.receivedImages >= job.completedResults)
        assertTrue(job.frameManifestCount >= job.savedFrames)
        assertTrue(job.rawMetadata["rawWidth"].orEmpty().isNotBlank())
        assertTrue(job.rawMetadata["rawHeight"].orEmpty().isNotBlank())
        assertTrue(job.fileNames.any { it.endsWith(".dng", ignoreCase = true) || it.contains("raw", ignoreCase = true) })
        assertTrue(
            job.dngSidecarSaved == true ||
                job.dngSidecarSkipReason.isNotBlank() ||
                job.dngSidecarStatuses.isNotEmpty()
        )
        assertTrue(job.requiredOutputFilePresent)
        assertTrue(report.terminalFlags["requiredOutputCommitted"] == true)
        assertTrue(job.exportStatus.uppercase() !in setOf("FAILED", "CANCELLED", "ERROR"))
        waitForPipelineIdle()
        composeRule.onNodeWithTag("kepler.camera.shutter").assertIsDisplayed()
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
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag("kepler.camera.root").assertIsDisplayed()
    }

    private fun awaitExactTerminalReport(
        previousRunId: String?,
        invocationStart: Long,
        expectedPipeline: String
    ): HardwareE2ERunReport {
        var runId: String? = null
        try {
            composeRule.waitUntil(180_000L) {
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
                            (report.finalJob != null || report.failure != null || report.status == HardwareE2EClassification.SKIPPED_UNSUPPORTED)
                    }
                } == true
            }
        } catch (timeout: Throwable) {
            throw AssertionError(
                "Timed out waiting for exact hardware run. " +
                    "previousRunId=$previousRunId invocationStart=$invocationStart " +
                    "lockedRunId=$runId latest=${HardwareE2EReportStore.readLatest(targetContext)?.toJson()?.toString(2)}",
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
            "Production smoke failed: ${diagnosticSummary(report)}",
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

    private fun diagnosticSummary(report: HardwareE2ERunReport): String =
        report.toJson().toString(2)

    private fun waitForPipelineIdle() {
        try {
            composeRule.waitUntil(15_000L) {
                composeRule.onAllNodesWithTag("kepler.pipeline.busy").fetchSemanticsNodes().isEmpty()
            }
        } catch (timeout: Throwable) {
            throw AssertionError("Pipeline busy state did not clear: ${HardwareE2EReportStore.readLatest(targetContext)?.toJson()?.toString(2)}", timeout)
        }
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

    private fun grantCameraPermission() {
        instrumentation.uiAutomation.grantRuntimePermission(
            targetContext.packageName,
            Manifest.permission.CAMERA
        )
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(targetContext, Manifest.permission.CAMERA)
        )
    }

    private fun hardwareE2EEnabled(): Boolean =
        InstrumentationRegistry.getArguments()
            .getString("kepler.hardwareE2E")
            ?.equals("true", ignoreCase = true) == true

    private fun defaultScenario() = HardwareE2ERunScenario(
        requestedTestScenario = "instrumentation",
        selectedPipelineMode = PipelineMode.YUV_NIGHT_FUSION.name,
        captureMode = CaptureMode.MULTI_FRAME.name,
        requestedLensSlot = LensSlot.MAIN_1X.name,
        requestedResolution = CaptureResolutionMode.MP12.name,
        frameCountPolicy = FrameCountMode.MANUAL.name,
        effectiveRequestedFrames = 4,
        requestedZoom = 1.0f,
        requestedOutputFormat = FinalOutputFormat.HEIF.name
    )
}
