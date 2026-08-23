package com.projectnuke.keplernightlab

import java.io.File
import kotlin.io.path.createTempDirectory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 6: exact-job routing of background events through the REAL recorder +
 * orchestrator integration. A background terminal for run A must mutate run A
 * only - never the newer current run B, never latest.json - and finalize the
 * EXACT pinned job with strict classification.
 */
@RunWith(RobolectricTestRunner::class)
class Phase6HardwareE2EOverlapTest {

    private val root = createTempDirectory("phase6-e2e-overlap").toFile()

    @After
    fun cleanup() {
        BackgroundPipelineEventHub.resetForTest()
        BackgroundProcessingCoordinator.resetForTest()
        root.deleteRecursively()
    }

    private fun scenario(name: String) = HardwareE2ERunScenario(
        requestedTestScenario = name,
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
        runtimeSessionId = "runtime-overlap",
        processStartTimestamp = 10L,
        appPackage = "test.package",
        appVersion = "1.0-test",
        debugBuild = true,
        androidSdk = 36,
        manufacturer = "test",
        deviceModel = "model",
        buildFingerprint = "fingerprint"
    )

    private fun recorderFor(): HardwareE2ERunRecorder =
        HardwareE2ERunRecorder.forTest(root, environment()) {
            root.listFiles().orEmpty().filter { it.isDirectory }
        }

    private fun writeVerifiedYuvJob(name: String): File {
        val directory = File(root, name).apply { mkdirs() }
        File(directory, "final.jpg").writeText("output")
        File(directory, JOB_JSON_FILE_NAME).writeText(
            JSONObject()
                .put("jobType", "YUV_NIGHT_FUSION")
                .put("captureMode", CaptureMode.MULTI_FRAME.name)
                .put("requestedResolutionMode", CaptureResolutionMode.MP12.name)
                .put("createdAt", System.currentTimeMillis())
                .put("status", "PIPELINE_COMPLETE")
                .put("processStatus", "PIPELINE_COMPLETE")
                .put("exportStatus", "COMPLETE")
                .put("exportVerified", true)
                .put("requestedFrames", 4)
                .put("attemptedFrames", 4)
                .put("savedFrames", 4)
                .put("receivedImages", 4)
                .put("completedResults", 4)
                .put("finalFile", "final.jpg")
                .toString()
        )
        return directory
    }

    private fun captureStageComplete(job: File): CameraPipelineEvent.CaptureStageComplete =
        CameraPipelineEvent.CaptureStageComplete(
            generation = 0L,
            counts = CameraPipelineProgressCounts(),
            message = "handoff",
            jobDirectoryPath = job.absolutePath,
            captureResourcesSettled = true,
            processingHandoffDurable = true
        )

    private fun backgroundTerminal(
        job: File,
        kind: CameraPipelineEvent.Terminal.Kind = CameraPipelineEvent.Terminal.Kind.COMPLETE
    ) = BackgroundPipelineEvent(
        requestJobDirectory = job,
        jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
        event = CameraPipelineEvent.Terminal(
            generation = 0L,
            kind = kind,
            requiredOutputCommitted = true,
            publicExportCommitted = true,
            verified = true,
            captureResourcesSettled = true,
            message = "background terminal",
            jobDirectoryPath = job.absolutePath
        )
    )

    @Test
    fun captureA_handoffBindsJobAtoRunA() {
        val recorder = recorderFor()
        val runA = recorder.start(scenario("runA"))!!
        val jobA = writeVerifiedYuvJob("jobA")
        val stamped = captureStageComplete(jobA).copy(generation = 1L)

        recorder.recordEvent(CameraPipelineEvent.Started(1L, counts = CameraPipelineProgressCounts()))
        recorder.recordEvent(stamped)
        assertTrue(recorder.awaitIdle())

        val report = HardwareE2EReportStore.read(root, runA)!!
        assertEquals(jobA.absolutePath, report.latestJobDirectory)
        recorder.close()
    }

    @Test
    fun runBStartThenBackgroundTerminalA_routesToRunA() {
        val recorder = recorderFor()
        val runA = recorder.start(scenario("runA"))!!
        val jobA = writeVerifiedYuvJob("jobA")
        recorder.recordEvent(CameraPipelineEvent.Started(1L))
        recorder.recordEvent(captureStageComplete(jobA).copy(generation = 1L))

        val runB = recorder.start(scenario("runB"))!!
        assertTrue(runB != runA)
        recorder.recordEvent(CameraPipelineEvent.Started(2L))

        recorder.recordBackgroundEvent(backgroundTerminal(jobA))
        assertTrue(recorder.awaitIdle())

        val reportA = HardwareE2EReportStore.read(root, runA)!!
        val reportB = HardwareE2EReportStore.read(root, runB)!!
        assertEquals(HardwareE2EClassification.PASS, reportA.status)
        assertEquals(HardwareE2EJobCorrelation.EXACT, reportA.jobCorrelation)
        assertEquals(CameraPipelineEvent.Terminal.Kind.COMPLETE.name, reportA.terminalEvent)
        assertNull("background terminal for A must never terminate current run B", reportB.terminalEvent)
        assertEquals(HardwareE2EClassification.INCOMPLETE, reportB.status)
        recorder.close()
    }

    @Test
    fun backgroundProcessingStageA_routesToRunA() {
        val recorder = recorderFor()
        val runA = recorder.start(scenario("runA"))!!
        val jobA = writeVerifiedYuvJob("jobA")
        recorder.recordEvent(CameraPipelineEvent.Started(1L))
        recorder.recordEvent(captureStageComplete(jobA).copy(generation = 1L))

        val runB = recorder.start(scenario("runB"))!!
        recorder.recordEvent(CameraPipelineEvent.Started(2L))

        recorder.recordBackgroundEvent(
            BackgroundPipelineEvent(
                requestJobDirectory = jobA,
                jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
                event = CameraPipelineEvent.ProcessingStage(
                    generation = 0L,
                    stage = CaptureStage.PROCESSING,
                    counts = CameraPipelineProgressCounts()
                )
            )
        )
        assertTrue(recorder.awaitIdle())

        val reportA = HardwareE2EReportStore.read(root, runA)!!
        val reportB = HardwareE2EReportStore.read(root, runB)!!
        assertTrue(reportA.progressCounts.containsKey("PROCESSING_STARTED"))
        assertFalse(reportB.progressCounts.containsKey("PROCESSING_STARTED"))
        recorder.close()
    }

    @Test
    fun backgroundExportStageA_routesToRunA() {
        val recorder = recorderFor()
        val runA = recorder.start(scenario("runA"))!!
        val jobA = writeVerifiedYuvJob("jobA")
        recorder.recordEvent(CameraPipelineEvent.Started(1L))
        recorder.recordEvent(captureStageComplete(jobA).copy(generation = 1L))

        val runB = recorder.start(scenario("runB"))!!
        recorder.recordEvent(CameraPipelineEvent.Started(2L))

        recorder.recordBackgroundEvent(
            BackgroundPipelineEvent(
                requestJobDirectory = jobA,
                jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
                event = CameraPipelineEvent.ExportStage(
                    generation = 0L,
                    stage = CaptureStage.EXPORTING,
                    counts = CameraPipelineProgressCounts()
                )
            )
        )
        assertTrue(recorder.awaitIdle())

        val reportA = HardwareE2EReportStore.read(root, runA)!!
        val reportB = HardwareE2EReportStore.read(root, runB)!!
        assertTrue(reportA.progressCounts.containsKey("EXPORT_STARTED"))
        assertFalse(reportB.progressCounts.containsKey("EXPORT_STARTED"))
        recorder.close()
    }

    @Test
    fun terminalA_finalizesExactJobAWhileRunBCurrent() {
        val recorder = recorderFor()
        val runA = recorder.start(scenario("runA"))!!
        val jobA = writeVerifiedYuvJob("jobA")
        recorder.recordEvent(CameraPipelineEvent.Started(1L))
        recorder.recordEvent(captureStageComplete(jobA).copy(generation = 1L))

        val runB = recorder.start(scenario("runB"))!!
        val jobB = writeVerifiedYuvJob("jobB")
        recorder.recordEvent(CameraPipelineEvent.Started(2L))
        recorder.recordEvent(captureStageComplete(jobB).copy(generation = 2L))

        recorder.recordBackgroundEvent(backgroundTerminal(jobA))
        assertTrue(recorder.awaitIdle())

        val reportA = HardwareE2EReportStore.read(root, runA)!!
        assertEquals(HardwareE2EClassification.PASS, reportA.status)
        assertEquals(HardwareE2EClassificationReason.PASS_SUCCESS, reportA.classificationReason)
        assertEquals(HardwareE2EJobCorrelation.EXACT, reportA.jobCorrelation)
        assertNotNull(reportA.finalJob)
        assertEquals(jobA.absolutePath, reportA.finalJob?.jobDirectory)
        assertTrue(reportA.finalJob?.requiredOutputFilePresent == true)
        assertNull(HardwareE2EReportStore.read(root, runB)?.terminalEvent)
        recorder.close()
    }

    @Test
    fun terminalA_doesNotChangeRunB() {
        val recorder = recorderFor()
        val runA = recorder.start(scenario("runA"))!!
        val jobA = writeVerifiedYuvJob("jobA")
        recorder.recordEvent(CameraPipelineEvent.Started(1L))
        recorder.recordEvent(captureStageComplete(jobA).copy(generation = 1L))

        val runB = recorder.start(scenario("runB"))!!
        recorder.recordEvent(CameraPipelineEvent.Started(2L))

        recorder.recordBackgroundEvent(backgroundTerminal(jobA))
        assertTrue(recorder.awaitIdle())

        val before = HardwareE2EReportStore.read(root, runB)!!
        recorder.recordBackgroundEvent(backgroundTerminal(writeVerifiedYuvJob("jobA2")))
        assertTrue(recorder.awaitIdle())
        val after = HardwareE2EReportStore.read(root, runB)!!

        assertEquals(before.eventHistory.size, after.eventHistory.size)
        assertEquals(after.status, before.status)
        assertEquals(0, after.progressCounts.getOrDefault("TERMINAL_COMPLETE", 0))
        recorder.close()
    }

    @Test
    fun terminalA_doesNotOverwriteLatestRunB() {
        val recorder = recorderFor()
        val runA = recorder.start(scenario("runA"))!!
        val jobA = writeVerifiedYuvJob("jobA")
        recorder.recordEvent(CameraPipelineEvent.Started(1L))
        recorder.recordEvent(captureStageComplete(jobA).copy(generation = 1L))

        val runB = recorder.start(scenario("runB"))!!
        recorder.recordEvent(CameraPipelineEvent.Started(2L))

        recorder.recordBackgroundEvent(backgroundTerminal(jobA))
        assertTrue(recorder.awaitIdle())

        val latest = HardwareE2EReportCodec.decode(File(root, "latest.json").readText())
        assertEquals(runB, latest.runId)
        recorder.close()
    }

    @Test
    fun backgroundTerminalGenerationZero_neverFallsBackToCurrentWhenExactJobKnown() {
        val recorder = recorderFor()
        val runA = recorder.start(scenario("runA"))!!
        val jobA = writeVerifiedYuvJob("jobA")
        recorder.recordEvent(CameraPipelineEvent.Started(1L))
        recorder.recordEvent(captureStageComplete(jobA).copy(generation = 1L))

        val runB = recorder.start(scenario("runB"))!!
        recorder.recordEvent(CameraPipelineEvent.Started(2L))

        recorder.recordBackgroundEvent(backgroundTerminal(jobA))
        recorder.recordBackgroundEvent(backgroundTerminal(jobA))
        assertTrue(recorder.awaitIdle())

        val reportA = HardwareE2EReportStore.read(root, runA)!!
        val reportB = HardwareE2EReportStore.read(root, runB)!!
        assertEquals(
            "both background terminals route to exact run A",
            2,
            reportA.progressCounts.getOrDefault("TERMINAL_COMPLETE", 0)
        )
        assertEquals(0, reportB.progressCounts.getOrDefault("TERMINAL_COMPLETE", 0))
        recorder.close()
    }

    @Test
    fun unboundBackgroundEnvelope_neverRoutesToCurrentRun() {
        val recorder = recorderFor()
        val runA = recorder.start(scenario("runA"))!!
        recorder.recordEvent(CameraPipelineEvent.Started(1L))

        recorder.recordBackgroundEvent(backgroundTerminal(writeVerifiedYuvJob("orphan")))
        assertTrue(recorder.awaitIdle())

        val reportA = HardwareE2EReportStore.read(root, runA)!!
        assertEquals(0, reportA.progressCounts.getOrDefault("TERMINAL_COMPLETE", 0))
        assertNull(reportA.terminalEvent)
        recorder.close()
    }

    @Test
    fun strictSingleYuvRun_backgroundTerminalCompletesReport() {
        strictFullRun(PipelineMode.YUV_NIGHT_FUSION, "strictYuv", "yuvStrict")
    }

    @Test
    fun strictSingleRawRun_backgroundTerminalCompletesReport() {
        strictFullRun(PipelineMode.RAW_NIGHT_FUSION, "strictRaw", "rawStrict")
    }

    private fun strictFullRun(pipelineMode: PipelineMode, scenarioName: String, jobPrefix: String) {
        val recorder = recorderFor()
        val scenario = HardwareE2ERunScenario(
            requestedTestScenario = scenarioName,
            selectedPipelineMode = pipelineMode.name,
            captureMode = CaptureMode.MULTI_FRAME.name,
            requestedLensSlot = LensSlot.MAIN_1X.name,
            requestedResolution = CaptureResolutionMode.MP12.name,
            frameCountPolicy = FrameCountMode.MANUAL.name,
            effectiveRequestedFrames = 4,
            requestedZoom = 1.0f,
            requestedOutputFormat = FinalOutputFormat.JPEG.name,
            allowPartialCompletion = false,
            requiresExport = true
        )
        val runId = recorder.start(scenario)!!
        val jobType = if (pipelineMode == PipelineMode.RAW_NIGHT_FUSION) "RAW_NIGHT_FUSION" else "YUV_NIGHT_FUSION"
        val job = writeVerifiedYuvJob(jobPrefix)
        val jobJson = File(job, JOB_JSON_FILE_NAME)
        val updated = JSONObject(jobJson.readText()).put("jobType", jobType)
        jobJson.writeText(updated.toString())

        recorder.recordCheckpoint("PIPELINE_REQUEST_ACCEPTED", null, null)
        recorder.recordEvent(CameraPipelineEvent.Started(1L, counts = CameraPipelineProgressCounts(requestedFrames = 4)))
        recorder.recordEvent(captureStageComplete(job).copy(generation = 1L))
        recorder.recordBackgroundEvent(
            BackgroundPipelineEvent(
                requestJobDirectory = job,
                jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
                event = CameraPipelineEvent.ProcessingStage(
                    generation = 0L,
                    stage = CaptureStage.PROCESSING,
                    counts = CameraPipelineProgressCounts(requestedFrames = 4, savedFrames = 4)
                )
            )
        )
        recorder.recordBackgroundEvent(
            BackgroundPipelineEvent(
                requestJobDirectory = job,
                jobKind = KeplerActiveOperationKind.PROCESSING_YUV,
                event = CameraPipelineEvent.ExportStage(
                    generation = 0L,
                    stage = CaptureStage.EXPORTING,
                    counts = CameraPipelineProgressCounts(requestedFrames = 4, savedFrames = 4)
                )
            )
        )
        recorder.recordBackgroundEvent(backgroundTerminal(job))
        assertTrue(recorder.awaitIdle())

        val report = HardwareE2EReportStore.read(root, runId)!!
        assertEquals(
            listOf(
                "RUN_STARTED",
                "PIPELINE_REQUEST_ACCEPTED",
                "CAPTURE_STARTED",
                "CAPTURE_STAGE_COMPLETE",
                "PROCESSING_STARTED",
                "EXPORT_STARTED",
                "TERMINAL_COMPLETE",
                "PUBLIC_OUTPUT_COMMITTED",
                "OWNER_SETTLED"
            ),
            report.eventHistory.map { it.checkpoint }
        )
        assertEquals(HardwareE2EClassification.PASS, report.status)
        assertEquals(HardwareE2EClassificationReason.PASS_SUCCESS, report.classificationReason)
        assertEquals(HardwareE2EJobCorrelation.EXACT, report.jobCorrelation)
        recorder.close()
    }
}
