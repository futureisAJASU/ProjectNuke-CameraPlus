package com.projectnuke.keplernightlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class HardwareE2ETest {
    private fun scenario() = HardwareE2ERunScenario(
        requestedTestScenario = "test",
        selectedPipelineMode = PipelineMode.YUV_NIGHT_FUSION.name,
        captureMode = CaptureMode.MULTI_FRAME.name,
        requestedLensSlot = LensSlot.MAIN_1X.name,
        requestedResolution = CaptureResolutionMode.MP12.name,
        frameCountPolicy = FrameCountMode.MANUAL.name,
        effectiveRequestedFrames = 4,
        requestedZoom = 1.0f,
        requestedOutputFormat = FinalOutputFormat.JPEG.name
    )

    private fun environment() = HardwareE2EEnvironment(
        runtimeSessionId = "runtime-test",
        processStartTimestamp = 10L,
        appPackage = "test.package",
        appVersion = "1.0-test",
        debugBuild = true,
        androidSdk = 36,
        manufacturer = "test",
        deviceModel = "model",
        buildFingerprint = "fingerprint"
    )

    @Test
    fun reportCodec_roundTripsOrderedEventsAndScenario() {
        val recorder = HardwareE2ERunRecorder.forTest(createTempDir(), environment())
        recorder.start(scenario())
        recorder.recordEvent(
            CameraPipelineEvent.Started(
                generation = 1L,
                counts = CameraPipelineProgressCounts(requestedFrames = 4)
            )
        )
        recorder.recordEvent(
            CameraPipelineEvent.Terminal(
                generation = 1L,
                kind = CameraPipelineEvent.Terminal.Kind.FAILED,
                counts = CameraPipelineProgressCounts(requestedFrames = 4, savedFrames = 2),
                message = "test failure"
            )
        )
        assertTrue(recorder.awaitIdle())
        val report = recorder.snapshot()!!
        val decoded = HardwareE2EReportCodec.decode(HardwareE2EReportCodec.encode(report))

        assertEquals(report.runId, decoded.runId)
        assertEquals("test", decoded.scenario.requestedTestScenario)
        assertEquals(
            listOf("APP_STARTED", "CAPTURE_STARTED", "TERMINAL_FAILED", "OWNER_SETTLED"),
            decoded.eventHistory.map { it.checkpoint }
        )
        assertEquals(HardwareE2EClassification.FAIL, decoded.status)
        assertEquals(2, decoded.eventHistory.first { it.terminalKind == "FAILED" }.savedFrames)
        recorder.close()
    }

    @Test
    fun recorderFinalizesPassFromTerminalAndReadableJob() {
        val root = createTempDir()
        val jobDir = File(root, "job").apply { mkdirs() }
        File(jobDir, JOB_JSON_FILE_NAME).writeText(
            JSONObject()
                .put("jobType", "YUV_FUSION")
                .put("status", "COMPLETE")
                .put("processStatus", "COMPLETE")
                .put("exportStatus", "COMPLETE")
                .put("exportVerified", true)
                .put("requestedFrames", 4)
                .put("attemptedFrames", 4)
                .put("savedFrames", 4)
                .put("receivedImages", 4)
                .put("completedResults", 4)
                .toString()
        )
        val recorder = HardwareE2ERunRecorder.forTest(root, environment()) { listOf(jobDir) }
        recorder.start(scenario())
        recorder.recordEvent(
            CameraPipelineEvent.Terminal(
                generation = 1L,
                kind = CameraPipelineEvent.Terminal.Kind.COMPLETE,
                requiredOutputCommitted = true,
                publicExportCommitted = true,
                verified = true
            )
        )
        assertTrue(recorder.awaitIdle())
        val report = recorder.snapshot()!!

        assertEquals(HardwareE2EClassification.PASS, report.status)
        assertEquals(jobDir.absolutePath, report.latestJobDirectory)
        assertNotNull(report.finalJob)
        assertEquals(4, report.finalJob?.savedFrames)
        assertTrue(File(root, "latest.json").isFile)
        assertNotNull(
            HardwareE2EReportCodec.decode(File(root, "latest.json").readText()).finalJob
        )
        recorder.close()
    }

    @Test
    fun recorderRetentionIsBoundedAndUnsupportedIsExplicit() {
        val root = createTempDir()
        val recorder = HardwareE2ERunRecorder.forTest(root, environment())
        repeat(15) {
            recorder.start(scenario().copy(requestedTestScenario = "run-$it"))
            recorder.recordSkipped("RAW unsupported")
        }
        assertTrue(recorder.awaitIdle())
        val reports = root.listFiles()?.count { it.extension == "json" && it.name != "latest.json" } ?: 0
        assertTrue(reports <= 12)
        assertEquals(HardwareE2EClassification.SKIPPED_UNSUPPORTED, recorder.snapshot()?.status)
        assertFalse(recorder.snapshot()?.failure.isNullOrBlank())
        recorder.close()
    }

    @Test
    fun reportWithoutTerminalRemainsIncomplete() {
        val recorder = HardwareE2ERunRecorder.forTest(createTempDir(), environment())
        recorder.start(scenario())
        assertTrue(recorder.awaitIdle())
        assertEquals(HardwareE2EClassification.INCOMPLETE, recorder.snapshot()?.status)
        assertTrue(recorder.snapshot()?.terminalEvent == null)
        recorder.close()
    }
}
