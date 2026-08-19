package com.projectnuke.keplernightlab

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
        composeRule.onNodeWithTag("kepler.camera.shutter").performClick()
        awaitTerminalReport()

        val report = HardwareE2EReportStore.readLatest(targetContext)
        assertNotNullReport(report)
        assertTrue(report!!.latestJobDirectory?.isNotBlank() == true)
        assertTrue(report.finalJob?.readable == true)
        assertFalse(report.finalJob?.liveOperationRegistered == true)
        assertTrue(report.finalJob!!.requestedFrames >= 2)
        assertTrue(report.finalJob!!.attemptedFrames >= report.finalJob!!.savedFrames)
        assertTrue(report.finalJob!!.receivedImages >= report.finalJob!!.completedResults)
        assertTrue(report.finalJob!!.frameManifestCount >= report.finalJob!!.savedFrames)
        assertTrue(report.status == HardwareE2EClassification.PASS || report.status == HardwareE2EClassification.FAIL)
        assertTrue(
            "YUV report missing actionable terminal/job state: ${report.toJson().toString(2)}",
            report.terminalEvent != null && report.latestJobDirectory != null
        )
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
        composeRule.onNodeWithTag("kepler.camera.shutter").performClick()
        awaitTerminalReport()

        val report = HardwareE2EReportStore.readLatest(targetContext)
        assertNotNullReport(report)
        assertTrue(report!!.finalJob?.readable == true)
        assertFalse(report.finalJob?.liveOperationRegistered == true)
        assertTrue(report.finalJob!!.requestedFrames >= 2)
        assertTrue(report.finalJob!!.attemptedFrames >= report.finalJob!!.savedFrames)
        assertTrue(report.finalJob!!.receivedImages >= report.finalJob!!.completedResults)
        assertTrue(report.finalJob!!.fileNames.any { it.endsWith(".dng", ignoreCase = true) || it.contains("raw", ignoreCase = true) })
        assertTrue(
            report.finalJob!!.dngSidecarSaved == null ||
                report.finalJob!!.dngSidecarSaved == true ||
                report.finalJob!!.dngSidecarSkipReason.isNotBlank()
        )
        assertTrue(
            "RAW report missing actionable terminal/job state: ${report.toJson().toString(2)}",
            report.terminalEvent != null && report.latestJobDirectory != null
        )
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

    private fun awaitTerminalReport() {
        composeRule.waitUntil(180_000L) {
            HardwareE2EReportStore.readLatest(targetContext)?.terminalEvent != null
        }
    }

    private fun assertNotNullReport(report: HardwareE2ERunReport?) {
        assertTrue(
            "No hardware report was produced. latest=${HardwareE2EReportStore.latestFile(targetContext)}",
            report != null
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

    private fun grantCameraPermission() {
        runCatching {
            instrumentation.uiAutomation
                .executeShellCommand("pm grant ${targetContext.packageName} ${Manifest.permission.CAMERA}")
                .close()
        }
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
